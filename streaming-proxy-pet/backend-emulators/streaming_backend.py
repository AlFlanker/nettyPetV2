#!/usr/bin/env python3
"""Потоковый целевой сервер для больших загрузок и проверки противодавления.

Тело скачиваемого файла не хранится на диске и не собирается целиком в памяти:
байт с номером N равен N % 256. Проверяющий клиент воспроизводит содержимое
по смещению и проверяет длину и байты ответа. Перестановка одинаковых 256-байтовых
блоков не меняет этот шаблон и такой проверкой не различается.
"""

from __future__ import annotations

import hashlib
import time

from http_support import (
    BaseScenarioHandler,
    pattern_chunks,
    run_server,
    validate_chunk_size,
)


class StreamingHandler(BaseScenarioHandler):
    """Отдаёт виртуальные файлы и считает длину/SHA-256 принятых потоков."""

    def handle_scenario(self) -> None:
        if self.route == "/health":
            self.send_json(200, {"status": "ok", "backend": "streaming"})
        elif self.route == "/download":
            self._download()
        elif self.route == "/upload":
            self._upload()
        else:
            self.send_json(404, {"error": "Unknown route", "path": self.route})

    def _chunk_size(self) -> int:
        # Ограничиваем размер отдельной порции независимо от размера файла.
        # Настройка действует и на генерацию ответа, и на чтение входящего тела.
        return validate_chunk_size(self.size_param("chunk", str(self.server.chunk_size)))

    def _download(self) -> None:
        if self.command not in ("GET", "HEAD"):
            self.send_json(405, {"error": "Use GET or HEAD"}, headers=(("Allow", "GET, HEAD"),))
            return

        size = self.size_param("size", "1GiB")
        chunk_size = self._chunk_size()
        pause = self.number_param("delay_ms", 0.0) / 1000.0
        framing = self.param("framing", "fixed").strip().lower()
        if framing not in ("fixed", "chunked"):
            raise ValueError("framing must be fixed or chunked")
        chunked = framing == "chunked"

        # В fixed-режиме заранее известна длина виртуального файла. В chunked-режиме
        # HTTP-клиент узнаёт конец тела по завершающему чанку, а не по Content-Length.
        # X-Expected-Bytes остаётся диагностическим заголовком в обоих режимах.
        self.begin_response(
            200,
            length=None if chunked else size,
            chunked=chunked,
            headers=(
                ("Content-Type", "application/octet-stream"),
                ("Content-Disposition", 'attachment; filename="generated.bin"'),
                ("X-Expected-Bytes", str(size)),
                ("X-Pattern", "offset-mod-256"),
            ),
        )
        if self.command == "HEAD":
            # Даже для многогигабайтного файла HEAD сообщает только метаданные:
            # не запускаем генератор и не отправляем завершающий HTTP-чанк.
            return

        sent = 0
        for block in pattern_chunks(size, chunk_size):
            # Запись блокируется при заполнении сетевых буферов. Следующую порцию
            # формируем только после неё: медленный клиент не создаёт очередь данных.
            self.write_chunk(block, chunked=chunked)
            sent += len(block)
            if pause and sent < size:
                time.sleep(pause)
        self.finish_body(chunked=chunked)

    def _upload(self) -> None:
        chunk_size = self._chunk_size()
        pause = self.number_param("delay_ms", 0.0) / 1000.0
        digest = hashlib.sha256()
        received = 0

        # Входящий поток читается одинаково для GET, POST, PUT, PATCH, DELETE,
        # HEAD и OPTIONS. Общий HTTP-адаптер снимает только транспортное chunked-
        # оформление; параметры форм, multipart и сжатое содержимое не разбираются.
        for block in self.iter_request_body(chunk_size):
            digest.update(block)
            received += len(block)
            if pause:
                # Медленное потребление постепенно заполняет буферы соединения.
                # Прокси должен вслед за этим приостановить чтение своего клиента.
                time.sleep(pause)

        self.send_json(
            200,
            {
                "bytes": received,
                "sha256": digest.hexdigest(),
                "method": self.command,
                "path": self.path,
                "content_type": self.headers.get("Content-Type"),
            },
        )


if __name__ == "__main__":
    run_server(
        StreamingHandler,
        default_port=9090,
        description="Потоковая отправка и приём файлов любого размера без накопления тела.",
    )
