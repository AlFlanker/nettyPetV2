#!/usr/bin/env python3
"""Быстрые проверки эмуляторов на настоящих локальных HTTP-соединениях.

Обычный набор использует небольшие тела: он проверяет протокол и управляемые
сбои без гигабайтного трафика. Передачу файлов от 1 ГиБ отдельно проверяют
команды streaming_client.py из README, напрямую или через Java-прокси.
"""

from __future__ import annotations

import concurrent.futures
import hashlib
import http.client
import json
import socket
import threading
import unittest

from fault_backend import FaultHandler
from http_support import create_server
from streaming_backend import StreamingHandler


def expected_bytes(size: int) -> bytes:
    """Независимый эталон для небольших тестовых тел, а не рабочий генератор."""
    return bytes(range(256)) * (size // 256) + bytes(range(size % 256))


def receive_head(connection: socket.socket) -> bytes:
    """Читает только заголовки; нужно различить ранний 413 и промежуточный 100."""
    head = bytearray()
    while not head.endswith(b"\r\n\r\n"):
        block = connection.recv(1)
        if not block:
            raise AssertionError("Соединение закрыто до получения HTTP-заголовков")
        head.extend(block)
        if len(head) > 64 * 1024:
            raise AssertionError("Получены неожиданно большие HTTP-заголовки")
    return bytes(head)


class EmulatorIntegrationTest(unittest.TestCase):
    """Проверяет байты, границы сообщений и преднамеренные нарушения протокола."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.servers = []
        cls.threads = []
        for handler in (StreamingHandler, FaultHandler):
            server = create_server(
                handler,
                host="127.0.0.1",
                port=0,
                chunk_size=1024,
                io_timeout=2,
                max_connections=16,
            )
            thread = threading.Thread(
                target=lambda current=server: current.serve_forever(poll_interval=0.05),
                daemon=True,
            )
            thread.start()
            cls.servers.append(server)
            cls.threads.append(thread)
        cls.streaming, cls.faults = cls.servers

    @classmethod
    def tearDownClass(cls) -> None:
        for server in cls.servers:
            server.shutdown()
            server.server_close()
        for thread in cls.threads:
            thread.join(timeout=3)
            if thread.is_alive():
                raise AssertionError("Поток HTTP-сервера не остановился")

    def connect(self, server, timeout: float = 3) -> http.client.HTTPConnection:
        connection = http.client.HTTPConnection(*server.server_address, timeout=timeout)
        self.addCleanup(connection.close)
        return connection

    def raw_connection(self, server) -> socket.socket:
        connection = socket.create_connection(server.server_address, timeout=3)
        self.addCleanup(connection.close)
        return connection

    def test_health_endpoints(self) -> None:
        for server in self.servers:
            with self.subTest(server=server.server_address):
                connection = self.connect(server)
                connection.request("GET", "/health")
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                self.assertIn(json.loads(response.read())["status"], ("ok", "ready"))

    def test_fixed_and_chunked_download_preserve_bytes(self) -> None:
        # Размер не кратен ни порции сервера, ни периоду шаблона: проверяем хвост.
        expected = expected_bytes(65_793)
        for framing in ("fixed", "chunked"):
            with self.subTest(framing=framing):
                connection = self.connect(self.streaming)
                connection.request("GET", f"/download?size={len(expected)}&chunk=513&framing={framing}")
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(response.getheader("X-Expected-Bytes"), str(len(expected)))
                if framing == "fixed":
                    self.assertEqual(response.getheader("Content-Length"), str(len(expected)))
                    self.assertIsNone(response.getheader("Transfer-Encoding"))
                else:
                    self.assertEqual(response.getheader("Transfer-Encoding"), "chunked")
                    self.assertIsNone(response.getheader("Content-Length"))
                self.assertEqual(response.read(), expected)

    def test_head_describes_more_than_one_gib_without_body(self) -> None:
        size = 1024 ** 3 + 123
        connection = self.connect(self.streaming)
        connection.request("HEAD", f"/download?size={size}&framing=fixed")
        response = connection.getresponse()
        self.assertEqual(response.status, 200)
        self.assertEqual(response.getheader("Content-Length"), str(size))
        self.assertEqual(response.getheader("X-Expected-Bytes"), str(size))
        self.assertEqual(response.read(), b"")

    def test_empty_download_completes_in_both_framings(self) -> None:
        for framing in ("fixed", "chunked"):
            with self.subTest(framing=framing):
                connection = self.connect(self.streaming)
                connection.request("GET", f"/download?size=0&framing={framing}")
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(response.read(), b"")

    def test_upload_methods_preserve_raw_body_and_metadata(self) -> None:
        body = b"x=a+b&x=%FF&binary=\x00\xff\r\n" + expected_bytes(17_003)
        for method in ("POST", "PUT", "PATCH", "GET", "DELETE", "OPTIONS"):
            with self.subTest(method=method):
                connection = self.connect(self.streaming)
                connection.request(method, "/upload?chunk=257&raw=%2F", body, {
                    "Content-Type": "application/octet-stream",
                })
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                result = json.loads(response.read())
                self.assertEqual(result["bytes"], len(body))
                self.assertEqual(result["sha256"], hashlib.sha256(body).hexdigest())
                self.assertEqual(result["method"], method)
                self.assertEqual(result["path"], "/upload?chunk=257&raw=%2F")
                self.assertEqual(result["content_type"], "application/octet-stream")

    def test_chunked_upload_splits_large_wire_chunk_into_small_reads(self) -> None:
        # Один HTTP-чанк заметно больше прикладного буфера. Его размер не должен
        # заставлять принимающий сервер выделять такой же буфер целиком.
        body = expected_bytes(131_089)
        connection = self.raw_connection(self.streaming)
        connection.sendall(
            b"PUT /upload?chunk=257 HTTP/1.1\r\nHost: localhost\r\n"
            b"Transfer-Encoding: chunked\r\nContent-Type: application/octet-stream\r\n"
            b"Connection: close\r\n\r\n"
            + f"{len(body):x};example=value\r\n".encode("ascii")
            + body + b"\r\n0\r\n\r\n"
        )
        response = http.client.HTTPResponse(connection)
        response.begin()
        self.assertEqual(response.status, 200)
        result = json.loads(response.read())
        self.assertEqual(result["bytes"], len(body))
        self.assertEqual(result["sha256"], hashlib.sha256(body).hexdigest())

    def test_invalid_or_incomplete_upload_is_not_reported_as_success(self) -> None:
        requests = {
            "invalid_chunk_size": (
                b"Transfer-Encoding: chunked\r\n", b"not-hex\r\nbody\r\n0\r\n\r\n"
            ),
            "truncated_content_length": (b"Content-Length: 20\r\n", b"short"),
            "truncated_chunk": (b"Transfer-Encoding: chunked\r\n", b"20\r\nshort"),
            "ambiguous_framing": (
                b"Content-Length: 5\r\nTransfer-Encoding: chunked\r\n", b"0\r\n\r\n"
            ),
        }
        for scenario, (headers, body) in requests.items():
            with self.subTest(scenario=scenario):
                connection = self.raw_connection(self.streaming)
                connection.sendall(
                    b"POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + headers + b"\r\n" + body
                )
                # Полузакрытие обозначает настоящий EOF тела, сохраняя возможность
                # получить диагностический ответ. Ожидание клиентского timeout не нужно.
                connection.shutdown(socket.SHUT_WR)
                response = http.client.HTTPResponse(connection)
                response.begin()
                self.assertEqual(response.status, 400)
                response.read()

    def test_invalid_download_parameters_are_rejected_before_streaming(self) -> None:
        for query in ("size=-1", "framing=unknown", "chunk=2GiB", "delay_ms=nan"):
            with self.subTest(query=query):
                connection = self.connect(self.streaming)
                connection.request("GET", "/download?" + query)
                response = connection.getresponse()
                self.assertEqual(response.status, 400)
                response.read()

    def test_early_response_does_not_wait_for_declared_request_body(self) -> None:
        connection = self.raw_connection(self.faults)
        connection.sendall(
            b"POST /early-response?code=413 HTTP/1.1\r\nHost: localhost\r\n"
            b"Content-Length: 1073741825\r\nConnection: close\r\n\r\n"
        )
        # Не отправляем ни одного байта объявленного большого тела: финальный
        # ответ должен быть доступен, пока производитель ещё не завершил загрузку.
        self.assertTrue(receive_head(connection).startswith(b"HTTP/1.1 413 "))

    def test_early_response_rejects_expect_without_sending_continue(self) -> None:
        connection = self.raw_connection(self.faults)
        connection.sendall(
            b"POST /early-response?code=413 HTTP/1.1\r\nHost: localhost\r\n"
            b"Content-Length: 1073741825\r\nExpect: 100-continue\r\n"
            b"Connection: close\r\n\r\n"
        )
        # HTTPResponse пропускает ответы 100 автоматически, поэтому здесь
        # проверяем именно первую строку, прочитанную непосредственно из сокета.
        self.assertTrue(receive_head(connection).startswith(b"HTTP/1.1 413 "))

    def test_truncated_responses_raise_incomplete_read(self) -> None:
        for framing in ("fixed", "chunked"):
            with self.subTest(framing=framing):
                connection = self.connect(self.faults)
                connection.request("GET", f"/truncate?size=32768&after=4097&framing={framing}")
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                with self.assertRaises(http.client.IncompleteRead) as failure:
                    response.read()
                self.assertEqual(failure.exception.partial, expected_bytes(4097))

    def test_stall_delivers_prefix_before_delayed_tail(self) -> None:
        connection = self.connect(self.faults)
        connection.request("GET", "/stall?after=1024&seconds=1")
        response = connection.getresponse()
        self.assertEqual(response.status, 200)
        self.assertEqual(response.read(1024), expected_bytes(1024))
        # Проверяем две отдельные фазы передачи. Чтение целого тела после ответа
        # не обнаружило бы ошибочную задержку или накопление префикса сервером.
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as workers:
            tail = workers.submit(response.read)
            with self.assertRaises(concurrent.futures.TimeoutError):
                tail.result(timeout=0.1)
            self.assertEqual(tail.result(timeout=3), expected_bytes(1024 + 16 * 1024)[1024:])

    def test_delay_holds_response_headers(self) -> None:
        connection = self.connect(self.faults, timeout=0.05)
        connection.request("GET", "/delay?seconds=0.3")
        with self.assertRaises(TimeoutError):
            connection.getresponse()

    def test_disconnect_has_no_http_response(self) -> None:
        connection = self.connect(self.faults)
        connection.request("GET", "/disconnect")
        with self.assertRaises(http.client.RemoteDisconnected):
            connection.getresponse()

    def test_backend_status_and_redirect_are_visible(self) -> None:
        connection = self.connect(self.faults)
        connection.request("GET", "/status?code=503")
        response = connection.getresponse()
        self.assertEqual(response.status, 503)
        response.read()

        connection = self.connect(self.faults)
        connection.request("GET", "/redirect")
        response = connection.getresponse()
        self.assertEqual(response.status, 302)
        self.assertEqual(response.getheader("Location"), "/health")
        response.read()

    def test_header_fixture_preserves_repeated_and_connection_scoped_fields(self) -> None:
        connection = self.connect(self.faults)
        connection.request("GET", "/headers")
        response = connection.getresponse()
        self.assertEqual(response.status, 200)
        cookies = [value for name, value in response.getheaders() if name.lower() == "set-cookie"]
        self.assertEqual(cookies, ["first=1; Path=/; HttpOnly", "second=2; Path=/; SameSite=Lax"])
        # Сам эмулятор должен отправить служебное поле. При проверке через прокси
        # ожидается обратное: поле X-Hop-Only должно быть удалено согласно Connection.
        self.assertIn("X-Hop-Only", response.getheader("Connection"))
        self.assertEqual(response.getheader("X-Hop-Only"), "must-not-reach-client")
        response.read()


if __name__ == "__main__":
    unittest.main(verbosity=2)
