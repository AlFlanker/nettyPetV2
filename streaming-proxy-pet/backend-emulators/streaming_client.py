#!/usr/bin/env python3
"""Проверяет большие передачи через прокси, не сохраняя тело целиком в памяти.

Данные воспроизводимы: последовательность байтов 0..255 повторяется до заданной
длины. При загрузке сервер подтверждает длину и SHA-256; при скачивании клиент
проверяет каждую полученную порцию. По умолчанию скачанные данные отбрасываются.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import math
from pathlib import Path
import sys
import time
from urllib.parse import urlencode, urlsplit

from http_support import parse_size, pattern_bytes, pattern_chunks, validate_chunk_size


def size_argument(value: str) -> int:
    """Ошибки размера оформляются как ошибки CLI, без Python traceback."""
    try:
        result = parse_size(value)
        if result < 0:
            raise ValueError("размер не может быть отрицательным")
        return result
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error


def chunk_argument(value: str) -> int:
    try:
        return validate_chunk_size(parse_size(value))
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error


def nonnegative_number(value: str) -> float:
    try:
        result = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("ожидается число") from error
    if not math.isfinite(result) or result < 0:
        raise argparse.ArgumentTypeError("ожидается конечное неотрицательное число")
    return result


def positive_number(value: str) -> float:
    result = nonnegative_number(value)
    if result == 0:
        raise argparse.ArgumentTypeError("значение должно быть больше нуля")
    return result


def origin_argument(value: str) -> str:
    try:
        origin = urlsplit(value)
        if (
            origin.scheme not in {"http", "https"}
            or origin.hostname is None
            or origin.username is not None
            or origin.password is not None
            or origin.query
            or origin.fragment
            or origin.path not in {"", "/"}
            or origin.netloc.endswith(":")
            or origin.port == 0
        ):
            raise ValueError("нужен HTTP(S)-адрес сервера без пути, параметров и учётных данных")
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error
    return value.rstrip("/")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Потоковая проверка загрузки и скачивания файлов от 1 GiB через прокси.",
        epilog=(
            "Размеры: 1GiB = 1073741824 байта, 1GB = 1000000000 байт. "
            "Клиент проверяет полную успешную передачу; ранний отказ сервера считается ошибкой."
        ),
    )
    actions = result.add_subparsers(dest="direction", required=True)
    for name, help_text in (("download", "скачать и проверить поток"), ("upload", "отправить поток и проверить SHA-256")):
        action = actions.add_parser(name, help=help_text)
        action.add_argument("--base-url", type=origin_argument, default="http://127.0.0.1:8080",
                            help="адрес прокси либо прямой адрес эмулятора")
        action.add_argument("--size", type=size_argument, default=parse_size("1GiB"),
                            help="размер тела, по умолчанию 1GiB; допустимы байты, KiB/MiB/GiB и KB/MB/GB")
        action.add_argument("--chunk-size", type=chunk_argument, default=65536,
                            help="размер одной порции, по умолчанию 64KiB; максимум 1MiB")
        action.add_argument("--framing", choices=("fixed", "chunked"), default="fixed",
                            help="Content-Length либо передача без заранее указанной длины")
        action.add_argument("--timeout", type=positive_number, default=300.0,
                            help="таймаут одной сетевой операции в секундах, по умолчанию 300")
        action.add_argument("--pause-ms", type=nonnegative_number, default=0.0,
                            help="пауза клиента после каждой порции для эмуляции медленной стороны")
        action.add_argument("--server-delay-ms", type=nonnegative_number, default=0.0,
                            help="пауза эмулятора между порциями чтения или записи")
        if name == "upload":
            action.add_argument("--method", choices=("POST", "PUT", "PATCH", "GET", "DELETE", "OPTIONS"),
                                default="POST", help="метод запроса; HEAD не возвращает JSON с контрольной суммой")
        else:
            action.add_argument("--output", type=Path, help="необязательный файл для скачанного тела")
            action.add_argument("--overwrite", action="store_true", help="разрешить замену существующего --output")
    return result


def connection(args: argparse.Namespace) -> http.client.HTTPConnection:
    origin = urlsplit(args.base_url)
    # HTTPSConnection по умолчанию проверяет доверие сертификату и имя сервера.
    implementation = http.client.HTTPSConnection if origin.scheme == "https" else http.client.HTTPConnection
    return implementation(origin.hostname, origin.port, timeout=args.timeout)


def summary(args: argparse.Namespace, count: int, digest: str, started: float, status: int) -> dict:
    elapsed = time.monotonic() - started
    return {
        "direction": args.direction,
        "bytes": count,
        "sha256": digest,
        "duration_seconds": round(elapsed, 6),
        "mib_per_second": round(count / (1024 * 1024) / max(elapsed, 1e-9), 3),
        "framing": args.framing,
        "status": status,
    }


def require_success(response: http.client.HTTPResponse) -> None:
    if response.status != 200:
        # Тело произвольного ошибочного ответа не читаем целиком: оно тоже может
        # быть большим или передаваться бесконечно медленно.
        raise RuntimeError(f"сервер ответил HTTP {response.status} {response.reason}")


def download(args: argparse.Namespace) -> dict:
    query = urlencode({"size": args.size, "chunk": args.chunk_size, "framing": args.framing,
                       "delay_ms": args.server_delay_ms})
    client = connection(args)
    output = None
    started = time.monotonic()
    try:
        # Режим xb защищает уже существующий файл. При сетевом сбое оставшийся
        # частичный файл полезен для диагностики; успешным такой запуск не считается.
        if args.output is not None:
            output = args.output.open("wb" if args.overwrite else "xb")
        client.request("GET", "/download?" + query, headers={"Connection": "close"})
        response = client.getresponse()
        require_success(response)
        digest = hashlib.sha256()
        count = 0
        while True:
            block = response.read(args.chunk_size)
            if not block:
                break
            if count + len(block) > args.size:
                raise RuntimeError("получено больше байтов, чем запрошено")
            # Границы чтений клиента не обязаны совпадать с HTTP-чанками сервера.
            # Проверяем содержимое по абсолютному смещению, а не по номеру порции.
            if block != pattern_bytes(count, len(block)):
                raise RuntimeError(f"содержимое отличается от шаблона в порции со смещением {count}")
            digest.update(block)
            count += len(block)
            if output is not None:
                output.write(block)
            if args.pause_ms:
                time.sleep(args.pause_ms / 1000)
        if count != args.size:
            raise RuntimeError(f"неполный ответ: получено {count}, ожидалось {args.size} байтов")
        return summary(args, count, digest.hexdigest(), started, response.status)
    finally:
        client.close()
        if output is not None:
            output.close()


def upload(args: argparse.Namespace) -> dict:
    query = urlencode({"chunk": args.chunk_size, "delay_ms": args.server_delay_ms})
    digest = hashlib.sha256()
    count = 0

    def body():
        nonlocal count
        # Генератор отдаёт только очередную порцию; ни http.client, ни этот код
        # не получают объект размером со весь файл. SHA-256 считается по тем же байтам.
        for block in pattern_chunks(args.size, args.chunk_size):
            digest.update(block)
            count += len(block)
            yield block
            if args.pause_ms:
                time.sleep(args.pause_ms / 1000)

    headers = {"Content-Type": "application/octet-stream", "Connection": "close"}
    if args.framing == "fixed":
        headers["Content-Length"] = str(args.size)
    client = connection(args)
    started = time.monotonic()
    try:
        # При отсутствии Content-Length http.client оформляет элементы генератора
        # как HTTP-чанки, включая завершающий нулевой чанк. Режим fixed отправляет
        # те же байты с известной длиной, не добавляя chunked-кодирование.
        client.request(args.method, "/upload?" + query, body=body(), headers=headers,
                       encode_chunked=args.framing == "chunked")
        response = client.getresponse()
        require_success(response)
        if response.length is not None and response.length > 65536:
            raise RuntimeError("объявленная длина диагностического JSON-ответа превышает 64 KiB")
        reply = response.read(65537)
        if len(reply) > 65536:
            raise RuntimeError("диагностический JSON-ответ превышает 64 KiB")
        # read(размер) может вернуть корректный JSON при раннем EOF, не выбросив
        # IncompleteRead. Проверяем остаток Content-Length отдельно: синтаксически
        # законченный JSON ещё не означает полностью полученное HTTP-сообщение.
        if response.length not in (None, 0):
            raise RuntimeError(f"неполный диагностический ответ: не получено {response.length} байтов")
        payload = json.loads(reply)
        if not isinstance(payload, dict):
            raise RuntimeError("ожидался JSON-объект с длиной и SHA-256")
        if count != args.size or payload.get("bytes") != args.size:
            raise RuntimeError(f"длина на сервере {payload.get('bytes')!r}, ожидалось {args.size}")
        expected_digest = digest.hexdigest()
        if payload.get("sha256") != expected_digest:
            raise RuntimeError("SHA-256 на сервере отличается от отправленных данных")
        if payload.get("method") != args.method:
            raise RuntimeError(f"сервер получил метод {payload.get('method')!r}, ожидался {args.method}")
        return summary(args, count, expected_digest, started, response.status)
    finally:
        client.close()


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        result = download(args) if args.direction == "download" else upload(args)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, ValueError, RuntimeError, http.client.HTTPException) as error:
        print(f"Ошибка передачи: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Передача прервана пользователем.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
