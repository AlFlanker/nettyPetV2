"""Общие средства локальных HTTP-эмуляторов; только стандартная библиотека Python.

HTTP-заголовки разбирает http.server. Тело читается ограниченными порциями:
даже размер HTTP-чанка, объявленный клиентом, не задаёт размер выделяемого буфера.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


DEFAULT_CHUNK_SIZE = 64 * 1024
MAX_CHUNK_SIZE = 1024 * 1024
_PATTERN = bytes(range(256))
_UNITS = {"": 1, "b": 1, "kb": 1000, "mb": 1000**2, "gb": 1000**3,
          "kib": 1024, "mib": 1024**2, "gib": 1024**3, "tib": 1024**4}


def parse_size(value: str) -> int:
    """Разбирает целое число байтов либо размер с явно указанной единицей."""
    match = re.fullmatch(r"\s*(\d+)\s*([A-Za-z]*)\s*", str(value))
    if match is None or match[2].lower() not in _UNITS:
        raise ValueError("Размер должен быть целым числом байтов или иметь суффикс B/KiB/MiB/GiB/KB/MB/GB")
    return int(match[1]) * _UNITS[match[2].lower()]


def validate_chunk_size(size: int) -> int:
    if not 1 <= size <= MAX_CHUNK_SIZE:
        raise ValueError("Размер порции должен быть от 1 байта до 1 MiB")
    return size


def pattern_bytes(offset: int, length: int) -> bytes:
    """Возвращает порцию проверяемого содержимого: байт по адресу N равен N % 256."""
    if offset < 0 or length < 0 or length > MAX_CHUNK_SIZE:
        raise ValueError("Неверное смещение или размер порции")
    start = offset % len(_PATTERN)
    return (_PATTERN * ((start + length + 255) // 256))[start:start + length]


def pattern_chunks(size: int, chunk_size: int = DEFAULT_CHUNK_SIZE):
    """Генерирует виртуальный файл без выделения памяти под весь размер size."""
    validate_chunk_size(chunk_size)
    if size < 0:
        raise ValueError("Размер файла не может быть отрицательным")
    # При размере порции, кратном 256, переиспользуется один и тот же объект bytes.
    # Неполная последняя порция и произвольный chunk_size сохраняют общий шаблон.
    reusable = pattern_bytes(0, chunk_size) if chunk_size % 256 == 0 else None
    for offset in range(0, size, chunk_size):
        length = min(chunk_size, size - offset)
        yield reusable if reusable is not None and length == chunk_size else pattern_bytes(offset, length)


class BodyError(ValueError):
    """Тело запроса не соответствует объявленным HTTP-границам."""


class BaseScenarioHandler(BaseHTTPRequestHandler):
    """Один запрос на соединение; ошибки сценария не превращаются во второй ответ."""

    protocol_version = "HTTP/1.1"
    server_version = "ProxyScenarioBackend/1.0"

    def handle_scenario(self):
        raise NotImplementedError

    def _dispatch(self):
        self.close_connection = True
        self._response_started = False
        target = urlsplit(self.path)
        self.route = target.path
        self.query = parse_qs(target.query, keep_blank_values=True)
        try:
            self.handle_scenario()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            # Отключение прокси или тестового клиента — ожидаемый исход ряда сценариев.
            self.close_connection = True
        except (ValueError, TimeoutError) as error:
            if not self._response_started:
                try:
                    self.send_json(400, {"error": str(error)})
                except OSError:
                    pass
            else:
                self.abort_connection()

    do_GET = _dispatch
    do_HEAD = _dispatch
    do_POST = _dispatch
    do_PUT = _dispatch
    do_PATCH = _dispatch
    do_DELETE = _dispatch
    do_OPTIONS = _dispatch

    def param(self, name: str, default: str) -> str:
        return self.query.get(name, [default])[0]

    def size_param(self, name: str, default: str) -> int:
        return parse_size(self.param(name, default))

    def number_param(self, name: str, default: float) -> float:
        value = float(self.param(name, str(default)))
        if not math.isfinite(value) or value < 0:
            raise ValueError(f"{name} должен быть конечным неотрицательным числом")
        return value

    def begin_response(self, status: int, *, length=None, chunked=False, headers=()):
        if chunked and length is not None:
            raise ValueError("Content-Length и chunked нельзя использовать одновременно")
        self.close_connection = True
        self._response_started = True
        self._body_allowed = self.command != "HEAD" and status not in (204, 304)
        self.send_response(status)
        self.send_header("Connection", "close")
        if status not in (204, 304):
            if chunked:
                self.send_header("Transfer-Encoding", "chunked")
            elif length is not None:
                self.send_header("Content-Length", str(length))
        for name, value in headers:
            self.send_header(name, str(value))
        self.end_headers()

    def write_chunk(self, data: bytes, *, chunked=False):
        if not self._body_allowed or not data:
            return
        if chunked:
            self.wfile.write(f"{len(data):x}\r\n".encode("ascii"))
        self.wfile.write(data)
        if chunked:
            self.wfile.write(b"\r\n")
        self.wfile.flush()

    def finish_body(self, *, chunked=False):
        if chunked and self._body_allowed:
            self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def send_json(self, status: int, payload, headers=()):
        # JSON содержит только небольшую диагностику, а не загруженные байты.
        data = (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")
        self.begin_response(status, length=len(data),
                            headers=[("Content-Type", "application/json; charset=utf-8"), *headers])
        self.write_chunk(data)

    def abort_connection(self):
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()

    def _body_line(self, limit=8192) -> bytes:
        line = self.rfile.readline(limit + 1)
        if len(line) > limit or not line.endswith(b"\r\n"):
            raise BodyError("Повреждена строка HTTP chunked framing")
        return line

    def _read_portions(self, length: int, chunk_size: int):
        while length:
            part = self.rfile.read(min(length, chunk_size))
            if not part:
                raise BodyError("Соединение закрыто до конца объявленного тела запроса")
            length -= len(part)
            yield part

    def iter_request_body(self, chunk_size=DEFAULT_CHUNK_SIZE):
        """Читает Content-Length или chunked, не используя read() без ограничения."""
        validate_chunk_size(chunk_size)
        lengths = self.headers.get_all("Content-Length", [])
        transfers = self.headers.get_all("Transfer-Encoding", [])
        if transfers and lengths:
            raise BodyError("Одновременные Content-Length и Transfer-Encoding не допускаются")
        if transfers:
            if len(transfers) != 1 or transfers[0].strip().lower() != "chunked":
                raise BodyError("Поддерживается только Transfer-Encoding: chunked")
            while True:
                token = self._body_line()[:-2].split(b";", 1)[0]
                if not re.fullmatch(rb"[0-9a-fA-F]+", token):
                    raise BodyError("Некорректный размер HTTP-чанка")
                remaining = int(token, 16)
                if remaining == 0:
                    # Трейлеры не нужны для digest, но их нужно дочитать до пустой
                    # строки; общий предел не позволяет накопить произвольные заголовки.
                    total = 0
                    while True:
                        line = self._body_line()
                        total += len(line)
                        if total > 64 * 1024:
                            raise BodyError("Слишком большой блок трейлеров")
                        if line == b"\r\n":
                            return
                        if b":" not in line:
                            raise BodyError("Некорректный трейлер")
                yield from self._read_portions(remaining, chunk_size)
                if self.rfile.read(2) != b"\r\n":
                    raise BodyError("Нет разделителя после HTTP-чанка")
        elif lengths:
            if len(lengths) != 1 or re.fullmatch(r"[0-9]+", lengths[0].strip()) is None:
                raise BodyError("Некорректный Content-Length")
            yield from self._read_portions(int(lengths[0]), chunk_size)
        # Отсутствие обоих заголовков означает пустое тело HTTP/1.1-запроса.

    def log_message(self, fmt, *args):
        if not self.server.quiet:
            super().log_message(fmt, *args)


class ScenarioServer(ThreadingHTTPServer):
    """Ограничивает число потоков обслуживания, а не только размер каждой порции."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, *, chunk_size, io_timeout, max_connections, quiet):
        self.chunk_size = validate_chunk_size(chunk_size)
        self.io_timeout = io_timeout
        self.quiet = quiet
        self._slots = threading.BoundedSemaphore(max_connections)
        self._active = set()
        self._active_lock = threading.Lock()
        super().__init__(address, handler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(self.io_timeout)
        return connection, address

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        with self._active_lock:
            self._active.add(request)
        try:
            super().process_request(request, client_address)
        except BaseException:
            with self._active_lock:
                self._active.discard(request)
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self._active_lock:
                self._active.discard(request)
            self._slots.release()

    def server_close(self):
        super().server_close()
        # Разблокируем ожидающие чтения/записи при остановке эмулятора.
        with self._active_lock:
            connections = list(self._active)
        for connection in connections:
            self.shutdown_request(connection)


def create_server(handler, host="127.0.0.1", port=0, *, chunk_size=DEFAULT_CHUNK_SIZE,
                  io_timeout=30.0, max_connections=32, quiet=True):
    if not math.isfinite(io_timeout) or io_timeout <= 0 or max_connections < 1:
        raise ValueError("Таймаут и число соединений должны быть положительными")
    return ScenarioServer((host, port), handler, chunk_size=chunk_size, io_timeout=io_timeout,
                          max_connections=max_connections, quiet=quiet)


def run_server(handler, *, default_port, description):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--host", default="127.0.0.1", help="адрес прослушивания (по умолчанию только localhost)")
    parser.add_argument("--port", type=int, default=default_port, help="порт; 0 выбирает свободный порт")
    parser.add_argument("--chunk-size", type=parse_size, default=DEFAULT_CHUNK_SIZE, help="размер порции, например 64KiB")
    parser.add_argument("--io-timeout", type=float, default=30.0, help="срок отдельной операции сокета, секунды")
    parser.add_argument("--max-connections", type=int, default=32, help="предел одновременно обслуживаемых соединений")
    parser.add_argument("--quiet", action="store_true", help="не выводить журнал отдельных запросов")
    args = parser.parse_args()
    try:
        server = create_server(handler, args.host, args.port, chunk_size=args.chunk_size,
                               io_timeout=args.io_timeout, max_connections=args.max_connections, quiet=args.quiet)
    except (ValueError, OverflowError, OSError) as error:
        parser.error(str(error))
    print(json.dumps({"server": handler.__name__, "host": args.host, "port": server.server_port,
                      "chunk_size": server.chunk_size, "max_connections": args.max_connections}), flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
