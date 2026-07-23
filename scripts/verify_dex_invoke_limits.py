#!/usr/bin/env python3
"""Reject APKs containing methods with more than 255 incoming DEX words."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

CLASS_RE = re.compile(r"Class descriptor\s+: '([^']+)'")
METHOD_RE = re.compile(r"name\s+: '([^']+)'$")
INS_RE = re.compile(r"ins\s+: (\d+)$")


def find_dexdump(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if path.is_file():
            return path
        raise FileNotFoundError(f"dexdump not found: {path}")

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = Path.home() / "AppData/Local/Android/Sdk"
        if local.is_dir():
            sdk = str(local)
    if not sdk:
        raise FileNotFoundError("Set ANDROID_HOME or pass --dexdump")

    executable = "dexdump.exe" if os.name == "nt" else "dexdump"
    candidates = sorted(
        Path(sdk).glob(f"build-tools/*/{executable}"),
        key=lambda item: tuple(int(part) for part in re.findall(r"\d+", item.parent.name)),
        reverse=True,
    )
    if not candidates:
        raise FileNotFoundError(f"No {executable} under {sdk}/build-tools")
    return candidates[0]


def oversized_methods(
    dexdump: Path,
    dex_file: Path,
) -> tuple[list[tuple[str, str, int]], tuple[str, str, int]]:
    process = subprocess.Popen(
        [str(dexdump), "-d", str(dex_file)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert process.stdout is not None
    class_name = "<unknown>"
    method_name = "<unknown>"
    failures: list[tuple[str, str, int]] = []
    peak = (class_name, method_name, 0)
    for raw_line in process.stdout:
        line = raw_line.strip()
        match = CLASS_RE.search(line)
        if match:
            class_name = match.group(1)
            method_name = "<unknown>"
            continue
        match = METHOD_RE.search(line)
        if match:
            method_name = match.group(1)
            continue
        match = INS_RE.search(line)
        if match:
            incoming_words = int(match.group(1))
            if incoming_words > peak[2]:
                peak = (class_name, method_name, incoming_words)
            if incoming_words > 255:
                failures.append((class_name, method_name, incoming_words))
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"dexdump failed for {dex_file} with exit code {return_code}")
    return failures, peak


def verify_apk(
    apk: Path,
    dexdump: Path,
) -> tuple[list[tuple[str, str, int]], tuple[str, str, int]]:
    with tempfile.TemporaryDirectory(prefix="verify-dex-") as temp_dir:
        temp = Path(temp_dir)
        with zipfile.ZipFile(apk) as archive:
            dex_names = sorted(
                name for name in archive.namelist()
                if name.startswith("classes") and name.endswith(".dex") and "/" not in name
            )
            if not dex_names:
                raise ValueError(f"No classes*.dex found in {apk}")
            for name in dex_names:
                archive.extract(name, temp)
        failures: list[tuple[str, str, int]] = []
        peak = ("<unknown>", "<unknown>", 0)
        for dex_file in sorted(temp.glob("classes*.dex")):
            dex_failures, dex_peak = oversized_methods(dexdump, dex_file)
            failures.extend(dex_failures)
            if dex_peak[2] > peak[2]:
                peak = dex_peak
        return failures, peak


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dexdump")
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    try:
        dexdump = find_dexdump(args.dexdump)
        failures, peak = verify_apk(args.apk, dexdump)
    except (FileNotFoundError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        print(f"DEX verification error: {error}", file=sys.stderr)
        return 2

    if failures:
        print("DEX invoke-range safety check FAILED", file=sys.stderr)
        for class_name, method_name, incoming_words in failures:
            print(
                f"- {class_name}.{method_name}: ins={incoming_words} exceeds 255",
                file=sys.stderr,
            )
        return 1

    print(
        f"DEX invoke-range safety check passed: {args.apk} "
        f"(max ins={peak[2]}, headroom={255 - peak[2]}, method={peak[0]}.{peak[1]})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
