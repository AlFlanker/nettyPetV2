#!/usr/bin/env python3
"""Целевой сервер с управляемыми задержками, отказами и неполными ответами.

Сценарии намеренно нарушают нормальное завершение HTTP-обмена. Они позволяют
проверять прокси настоящими соединениями, а не подменой его сетевых обработчиков.
По умолчанию сервер слушает 127.0.0.1:9091 и не требует сторонних библиотек.
"""

from __future__ import annotations

import json
import time
from urllib.parse import parse_qs, urlsplit

from http_support import BaseScenarioHandler, pattern_bytes, pattern_chunks, run_server


class FaultHandler(BaseScenarioHandler):
    """Отказ происходит в указанной фазе обмена; тело запроса не накапливается."""

    def handle_expect_100(self) -> bool:
        # Этот метод вызывается ещё при разборе заголовков, до обычного обработчика.
        # Для раннего отказа нельзя сначала разрешить отправку тела ответом 100:
        # окончательный статус должен прийти сразу после заголовков запроса.
        parsed = urlsplit(self.path)
        if parsed.path != "/early-response":
            return super().handle_expect_100()

        query = parse_qs(parsed.query, keep_blank_values=True)
        try:
            code = self._status_code(query.get("code", ["413"])[0])
        except ValueError:
            code = 400
        payload = json.dumps(
            {"scenario": "early-response", "status": code, "request_body_read": False},
            ensure_ascii=False,
        ).encode("utf-8")
        self.close_connection = True
        # Общая диспетчеризация ещё не началась, поэтому здесь используем только
        # стандартный интерфейс BaseHTTPRequestHandler, без состояния общего обработчика.
        self.send_response(code)
        self.send_header("Connection", "close")
        if code not in (204, 304):
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD" and code not in (204, 304):
            self.wfile.write(payload)
            self.wfile.flush()
        return False

    @staticmethod
    def _status_code(value: str) -> int:
        code = int(value)
        if not 200 <= code <= 599:
            raise ValueError("code must be a final HTTP status from 200 through 599")
        return code

    def handle_scenario(self) -> None:
        if self.route == "/health":
            self.send_json(200, {"server": "fault-backend", "status": "ready"})
        elif self.route == "/status":
            code = self._status_code(self.param("code", "503"))
            self.send_json(code, {"scenario": "status", "status": code})
        elif self.route == "/early-response":
            code = self._status_code(self.param("code", "413"))
            # Тело не читаем даже на один байт: прокси должен остановить загрузку
            # после получения окончательного ответа, сохранив этот статус клиенту.
            self.send_json(code, {
                "scenario": "early-response", "status": code, "request_body_read": False,
            })
        elif self.route == "/delay":
            seconds = self.number_param("seconds", 5)
            # Ни статус, ни заголовки до окончания паузы не отправляются.
            # Так проверяется таймаут ожидания ответа до его фиксации на клиенте.
            time.sleep(seconds)
            self.send_json(200, {"scenario": "delay", "seconds": seconds})
        elif self.route == "/stall":
            self._stall()
        elif self.route == "/truncate":
            self._truncate()
        elif self.route == "/disconnect":
            # Клиент увидит закрытие соединения до начальной строки HTTP-ответа.
            self.abort_connection()
        elif self.route == "/redirect":
            self.send_json(302, {"scenario": "redirect"}, headers=(("Location", "/health"),))
        elif self.route == "/headers":
            self.send_json(200, {"scenario": "headers"}, headers=(
                ("Set-Cookie", "first=1; Path=/; HttpOnly"),
                ("Set-Cookie", "second=2; Path=/; SameSite=Lax"),
                ("X-Backend", "fault-backend"),
                # Заголовок X-Hop-Only относится только к этому соединению;
                # прокси должен удалить и его, и соответствующее поле Connection.
                ("Connection", "X-Hop-Only"),
                ("X-Hop-Only", "must-not-reach-client"),
            ))
        else:
            self.send_json(404, {"error": "unknown scenario", "path": self.route})

    def _stall(self) -> None:
        prefix_size = self.size_param("after", "16KiB")
        seconds = self.number_param("seconds", 5)
        tail_size = 16 * 1024
        self.begin_response(200, length=prefix_size + tail_size, headers=(
            ("Content-Type", "application/octet-stream"),
            ("X-Scenario", "stall"),
        ))
        if self.command == "HEAD":
            return
        self._send_pattern(prefix_size)
        self.wfile.flush()
        # Первая порция уже дошла до сети. Ошибка во время этой паузы должна
        # оборвать начатый ответ, а не дописать к его телу новый ответ 502/504.
        time.sleep(seconds)
        self._send_pattern(tail_size, offset=prefix_size)
        self.finish_body()

    def _truncate(self) -> None:
        size = self.size_param("size", "1GiB")
        after = self.size_param("after", "128KiB")
        framing = self.param("framing", "fixed")
        if framing not in ("fixed", "chunked"):
            raise ValueError("framing must be fixed or chunked")
        if not 0 <= after < size:
            raise ValueError("truncate requires 0 <= after < size")
        chunked = framing == "chunked"
        self.begin_response(200, length=None if chunked else size, chunked=chunked, headers=(
            ("Content-Type", "application/octet-stream"),
            ("X-Expected-Bytes", str(size)),
            ("X-Scenario", "truncate"),
        ))
        if self.command == "HEAD":
            return
        self._send_pattern(after, chunked=chunked)
        self.wfile.flush()
        # Для Content-Length недостаёт обещанных байтов; для chunked намеренно
        # отсутствует завершающий нулевой чанк. Оба случая должны считаться обрывом.
        # В варианте chunked поле size — ожидаемая длина сценария, не Content-Length.
        self.abort_connection()

    def _send_pattern(self, size: int, *, offset: int = 0, chunked: bool = False) -> None:
        # Даже многогигабайтный префикс генерируется отдельными порциями.
        # Смещение сохраняет непрерывность шаблона после паузы в середине ответа.
        if offset == 0:
            for chunk in pattern_chunks(size, self.server.chunk_size):
                self.write_chunk(chunk, chunked=chunked)
            return
        end = offset + size
        while offset < end:
            count = min(self.server.chunk_size, end - offset)
            self.write_chunk(pattern_bytes(offset, count), chunked=chunked)
            offset += count


if __name__ == "__main__":
    run_server(FaultHandler, default_port=9091,
               description="Бэкенд для проверки таймаутов, ранних отказов и обрывов HTTP-ответа")
