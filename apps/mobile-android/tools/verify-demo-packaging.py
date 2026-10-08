#!/usr/bin/env python3
"""Check the final archives, not just a Gradle flag: demo assets must be debug-only."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEMO_NAMES = {"heb140-300.qsxr", "ipe270-200.qsxr", "tv-bank-148x47x57.qsxr"}


def verify(path: Path, debug: bool) -> dict:
    prefix = "base/assets/" if path.suffix == ".aab" else "assets/"
    with zipfile.ZipFile(path) as archive:
        demo = {name for name in archive.namelist() if name.startswith(prefix + "bundled/") and not name.endswith("/")}
        if debug:
            expected = {prefix + "bundled/" + name for name in DEMO_NAMES}
            if demo != expected:
                raise ValueError(f"{path}: missing or unexpected debug demos: {sorted(demo)}")
            for name in DEMO_NAMES:
                if archive.read(prefix + "bundled/" + name) != (ROOT / "app/src/debug/assets/bundled" / name).read_bytes():
                    raise ValueError(f"{path}: stale demo {name}")
        elif demo or any(Path(name).name in DEMO_NAMES for name in archive.namelist()):
            raise ValueError(f"{path}: release still contains demo assets")
    return {"file": path.name, "debug": debug, "demo_files": sorted(demo),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("release_archives", nargs="+", type=Path)
    parser.add_argument("--debug-apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    results = [verify(path, debug=False) for path in args.release_archives]
    if args.debug_apk:
        results.append(verify(args.debug_apk, debug=True))
    output = json.dumps(results, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
