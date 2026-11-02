#!/usr/bin/env python3
"""Создаёт настоящий тестовый файл порциями, без выделения памяти на весь файл."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

from http_support import parse_size, pattern_chunks, validate_chunk_size


def size_argument(value: str) -> int:
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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Создать файл из повторяющихся байтов 0..255 для curl, multipart и других клиентов.",
        epilog="Файл занимает указанный объём на диске; в памяти находится только очередная порция.",
    )
    parser.add_argument("output", type=Path, help="путь к создаваемому файлу")
    parser.add_argument("--size", type=size_argument, default=parse_size("1GiB"),
                        help="размер, по умолчанию 1GiB; поддерживаются байты, KiB/MiB/GiB и KB/MB/GB")
    parser.add_argument("--chunk-size", type=chunk_argument, default=65536,
                        help="порция записи, по умолчанию 64KiB; максимум 1MiB")
    parser.add_argument("--overwrite", action="store_true", help="разрешить замену существующего файла")
    args = parser.parse_args(argv)
    digest = hashlib.sha256()
    count = 0
    try:
        # Исключающее создание защищает ранее записанные данные. Режим замены
        # применяется только по явному --overwrite; при сбое может остаться неполный файл.
        with args.output.open("wb" if args.overwrite else "xb") as output:
            for block in pattern_chunks(args.size, args.chunk_size):
                output.write(block)
                digest.update(block)
                count += len(block)
        print(json.dumps({"path": str(args.output), "bytes": count, "sha256": digest.hexdigest()},
                         ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, ValueError) as error:
        print(f"Ошибка создания файла: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Создание файла прервано; на диске может остаться неполный файл.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
