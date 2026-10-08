from __future__ import annotations

import struct
import sys
from pathlib import Path


CONTRACT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONTRACT_ROOT))

from tools.build_example_package import build_glb, build_registration_model  # noqa: E402
from tools.validate_examples import parse_glb, validate_repository  # noqa: E402


def test_contract_examples_are_valid() -> None:
    assert validate_repository() == []


def test_generated_glb_is_deterministic_and_version_2() -> None:
    node_names = ["part:first", "part:second"]
    first = build_glb(node_names)
    second = build_glb(node_names)

    assert first == second
    assert struct.unpack_from("<4sI", first, 0) == (b"glTF", 2)


def test_example_glb_contains_one_named_node_per_part() -> None:
    gltf = parse_glb(CONTRACT_ROOT / "examples" / "BG12345" / "revision-4" / "assembly.glb")
    assert len(gltf["nodes"]) == 2
    assert all(node["name"].startswith("part:") for node in gltf["nodes"])


def test_generated_registration_model_is_deterministic_qsrg() -> None:
    first = build_registration_model()
    second = build_registration_model()

    assert first == second
    assert struct.unpack_from("<4sHHI", first, 0) == (b"QSRG", 1, 0, 1_024)
