#!/usr/bin/env python3
"""Build deterministic binary assets and checksums for the XR contract fixture."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import struct
from pathlib import Path
from typing import Any


CONTRACT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACKAGE = CONTRACT_ROOT / "examples" / "BG12345" / "revision-4"
DEFAULT_PROTOCOL = CONTRACT_ROOT / "examples" / "inspection-protocol.json"

# Transparent 1x1 PNG. It is deliberately tiny; the example tests transport, not artwork.
THUMBNAIL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def encode_json(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def build_glb(node_names: list[str]) -> bytes:
    """Return a valid glTF 2.0 GLB with named nodes and no manufacturing mesh."""

    gltf = {
        "asset": {
            "version": "2.0",
            "generator": "QS XR contract fixture builder 1.0.0",
        },
        "scene": 0,
        "scenes": [{"nodes": list(range(len(node_names)))}],
        "nodes": [{"name": name} for name in node_names],
    }
    json_chunk = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    json_chunk += b" " * (-len(json_chunk) % 4)

    total_length = 12 + 8 + len(json_chunk)
    header = struct.pack("<4sII", b"glTF", 2, total_length)
    chunk_header = struct.pack("<I4s", len(json_chunk), b"JSON")
    return header + chunk_header + json_chunk


def build_registration_model() -> bytes:
    """Return a deterministic QSRG 1.0 cube surface used by markerless autosnap."""

    points: list[tuple[float, float, float, float, float, float]] = []
    steps = 14
    coordinates = [index / (steps - 1) for index in range(steps)]
    for axis in range(3):
        for side, normal_sign in ((0.0, -1.0), (1.0, 1.0)):
            for first in coordinates:
                for second in coordinates:
                    position = [first, second, second]
                    remaining = [item for item in range(3) if item != axis]
                    position[axis] = side
                    position[remaining[0]] = first
                    position[remaining[1]] = second
                    normal = [0.0, 0.0, 0.0]
                    normal[axis] = normal_sign
                    points.append((*position, *normal))
    while len(points) < 1_024:
        points.extend(points[: 1_024 - len(points)])
    points = points[:1_024]
    header = struct.pack(
        "<4sHHII7fI",
        b"QSRG",
        1,
        0,
        len(points),
        0,
        0.0,
        0.0,
        0.0,
        1.0,
        1.0,
        1.0,
        0.01,
        1,
    )
    return header + b"".join(struct.pack("<6f", *point) for point in points)


def write_if_changed(path: Path, content: bytes) -> bool:
    if path.exists() and path.read_bytes() == content:
        return False
    path.write_bytes(content)
    return True


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def update_protocol_model_hash(protocol_path: Path, model_hash: str) -> bool:
    protocol = read_json(protocol_path)
    protocol["assembly"]["modelSha256"] = model_hash
    return write_if_changed(protocol_path, encode_json(protocol))


def update_checksums(package_dir: Path) -> bool:
    manifest = read_json(package_dir / "manifest.json")
    checksum_name = manifest["files"]["checksums"]
    content_names = ["manifest.json"] + [
        path
        for role, path in manifest["files"].items()
        if role != "checksums"
    ]

    entries = []
    for name in content_names:
        path = package_dir / name
        if not path.is_file():
            raise FileNotFoundError(f"Missing package file: {path}")
        entries.append(
            {
                "path": name,
                "sha256": sha256(path),
                "byteLength": path.stat().st_size,
            }
        )

    checksums = {
        "schemaVersion": "1.0.0",
        "algorithm": "SHA-256",
        "files": entries,
    }
    return write_if_changed(package_dir / checksum_name, encode_json(checksums))


def build_example_package(package_dir: Path, protocol_path: Path) -> list[Path]:
    parts = read_json(package_dir / "parts.json")
    node_names = [part["gltfNodeName"] for part in parts["parts"]]

    changed: list[Path] = []
    model_path = package_dir / "assembly.glb"
    registration_path = package_dir / "registration_model.bin"
    thumbnail_path = package_dir / "thumbnail.png"
    checksums_path = package_dir / "checksums.json"

    if write_if_changed(model_path, build_glb(node_names)):
        changed.append(model_path)
    if write_if_changed(registration_path, build_registration_model()):
        changed.append(registration_path)
    if write_if_changed(thumbnail_path, THUMBNAIL_PNG):
        changed.append(thumbnail_path)
    if update_protocol_model_hash(protocol_path, sha256(model_path)):
        changed.append(protocol_path)
    if update_checksums(package_dir):
        changed.append(checksums_path)
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL)
    args = parser.parse_args()

    changed = build_example_package(args.package.resolve(), args.protocol.resolve())
    if changed:
        for path in changed:
            print(f"updated {path}")
    else:
        print("example package is already up to date")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
