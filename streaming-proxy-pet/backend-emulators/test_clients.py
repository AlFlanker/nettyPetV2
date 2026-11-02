#!/usr/bin/env python3
"""Проверяет клиентские CLI на небольших потоках и настоящих HTTP-соединениях.

Гигабайтные прогоны выполняются отдельно. Здесь важны контрольные суммы,
корректное распознавание неполных ответов и безопасная работа с файлами.
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest

import generate_file
from http_support import BaseScenarioHandler, create_server
from streaming_backend import StreamingHandler
import streaming_client


def expected_bytes(size: int) -> bytes:
    """Независимый эталон допустим в памяти только для маленьких тестовых тел."""
    return bytes(index % 256 for index in range(size))


class TruncatedDiagnosticHandler(BaseScenarioHandler):
    """Возвращает правильные bytes/SHA-256/метод, но нарушает завершение HTTP-тела."""

    framing = "fixed"

    def handle_scenario(self) -> None:
        digest = hashlib.sha256()
        count = 0
        for block in self.iter_request_body(317):
            digest.update(block)
            count += len(block)
        payload = json.dumps({"bytes": count, "sha256": digest.hexdigest(), "method": self.command}).encode()
        if self.framing == "chunked":
            self.begin_response(200, chunked=True)
            self.write_chunk(payload, chunked=True)
            # Завершающий HTTP-чанк намеренно отсутствует, хотя JSON уже полный.
        else:
            declared = 65537 if self.framing == "oversized" else len(payload) + 17
            self.begin_response(200, length=declared)
            self.write_chunk(payload)
        self.abort_connection()


class TruncatedChunkedDiagnosticHandler(TruncatedDiagnosticHandler):
    framing = "chunked"


class OversizedDiagnosticHandler(TruncatedDiagnosticHandler):
    framing = "oversized"


class ClientIntegrationTest(unittest.TestCase):
    """Проверяет код CLI целиком, включая разбор аргументов и коды завершения."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.servers = []
        cls.threads = []
        for handler in (StreamingHandler, TruncatedDiagnosticHandler,
                        TruncatedChunkedDiagnosticHandler, OversizedDiagnosticHandler):
            server = create_server(handler, io_timeout=2, chunk_size=1024)
            thread = threading.Thread(target=lambda current=server: current.serve_forever(poll_interval=0.05),
                                      daemon=True)
            thread.start()
            cls.servers.append(server)
            cls.threads.append(thread)
        cls.streaming, cls.truncated, cls.truncated_chunked, cls.oversized = cls.servers

    @classmethod
    def tearDownClass(cls) -> None:
        for server in cls.servers:
            server.shutdown()
            server.server_close()
        for thread in cls.threads:
            thread.join(timeout=3)
            if thread.is_alive():
                raise AssertionError("Поток тестового HTTP-сервера не остановился")

    def invoke(self, main, arguments):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = main(arguments)
        return code, stdout.getvalue(), stderr.getvalue()

    def base_url(self, server) -> str:
        return f"http://127.0.0.1:{server.server_port}"

    def test_upload_fixed_and_chunked_preserves_bytes_digest_and_method(self):
        size = 8197
        digest = hashlib.sha256(expected_bytes(size)).hexdigest()
        for framing in ("fixed", "chunked"):
            for method in ("POST", "PUT", "PATCH", "GET", "DELETE", "OPTIONS"):
                with self.subTest(framing=framing, method=method):
                    code, output, error = self.invoke(streaming_client.main, [
                        "upload", "--base-url", self.base_url(self.streaming), "--size", str(size),
                        "--chunk-size", "1023", "--framing", framing, "--method", method,
                    ])
                    self.assertEqual(code, 0, error)
                    report = json.loads(output)
                    self.assertEqual(report["bytes"], size)
                    self.assertEqual(report["sha256"], digest)
                    self.assertEqual(report["direction"], "upload")
                    self.assertEqual(report["framing"], framing)
                    self.assertEqual(report["status"], 200)

    def test_download_fixed_and_chunked_checks_non_aligned_last_portion(self):
        size = 10007
        digest = hashlib.sha256(expected_bytes(size)).hexdigest()
        for framing in ("fixed", "chunked"):
            with self.subTest(framing=framing):
                code, output, error = self.invoke(streaming_client.main, [
                    "download", "--base-url", self.base_url(self.streaming), "--size", str(size),
                    "--chunk-size", "777", "--framing", framing,
                ])
                self.assertEqual(code, 0, error)
                report = json.loads(output)
                self.assertEqual(report["bytes"], size)
                self.assertEqual(report["sha256"], digest)
                self.assertEqual(report["direction"], "download")

    def test_download_writes_requested_file_without_silent_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "download.bin"
            args = ["download", "--base-url", self.base_url(self.streaming), "--size", "4099",
                    "--chunk-size", "519", "--output", str(path)]
            code, _, error = self.invoke(streaming_client.main, args)
            self.assertEqual(code, 0, error)
            self.assertEqual(path.read_bytes(), expected_bytes(4099))
            code, _, _ = self.invoke(streaming_client.main, args)
            self.assertNotEqual(code, 0)
            self.assertEqual(path.read_bytes(), expected_bytes(4099))

    def test_generator_writes_expected_file_and_requires_explicit_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "generated.bin"
            args = [str(path), "--size", "4099", "--chunk-size", "519"]
            code, output, error = self.invoke(generate_file.main, args)
            self.assertEqual(code, 0, error)
            expected = expected_bytes(4099)
            self.assertEqual(path.read_bytes(), expected)
            self.assertEqual(json.loads(output)["sha256"], hashlib.sha256(expected).hexdigest())
            code, _, _ = self.invoke(generate_file.main, args)
            self.assertNotEqual(code, 0)
            self.assertEqual(path.read_bytes(), expected)
            code, output, error = self.invoke(generate_file.main, [str(path), "--size", "3", "--overwrite"])
            self.assertEqual(code, 0, error)
            self.assertEqual(path.read_bytes(), b"\x00\x01\x02")
            self.assertEqual(json.loads(output)["bytes"], 3)

    def test_invalid_sizes_and_chunk_bounds_fail_before_network_or_file_work(self):
        cases = (
            (streaming_client.main, ["download", "--chunk-size", "0"]),
            (streaming_client.main, ["upload", "--chunk-size", "2MiB"]),
            (streaming_client.main, ["download", "--size=-1"]),
            (streaming_client.main, ["upload", "--size", "broken"]),
            (generate_file.main, ["unused.bin", "--chunk-size", "0"]),
            (generate_file.main, ["unused.bin", "--size=-1"]),
        )
        for main, args in cases:
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    main(args)
                self.assertEqual(failure.exception.code, 2)

    def test_valid_json_does_not_hide_incomplete_content_length(self):
        code, output, error = self.invoke(streaming_client.main, [
            "upload", "--base-url", self.base_url(self.truncated), "--size", "1027", "--chunk-size", "317",
        ])
        self.assertNotEqual(code, 0)
        self.assertEqual(output, "")
        self.assertIn("неполный диагностический ответ", error)

    def test_valid_json_does_not_hide_missing_last_http_chunk(self):
        code, output, error = self.invoke(streaming_client.main, [
            "upload", "--base-url", self.base_url(self.truncated_chunked), "--size", "1027",
        ])
        self.assertNotEqual(code, 0)
        self.assertEqual(output, "")
        self.assertIn("Ошибка передачи", error)

    def test_large_declared_diagnostic_body_is_rejected_before_read(self):
        code, output, error = self.invoke(streaming_client.main, [
            "upload", "--base-url", self.base_url(self.oversized), "--size", "1027",
        ])
        self.assertNotEqual(code, 0)
        self.assertEqual(output, "")
        self.assertIn("объявленная длина", error)


if __name__ == "__main__":
    unittest.main()
