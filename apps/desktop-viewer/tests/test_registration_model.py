import struct

import ifcopenshell.guid
import numpy as np
import pytest

from qs_desktop_viewer.registration_model import (
    HEADER_SIZE,
    build_registration_model,
    inspect_registration_model,
)
from qs_desktop_viewer.mobile_package import _read_glb_json, _write_glb_json

from test_mobile_package import fake_glb


def test_registration_model_is_deterministic_metric_and_self_describing() -> None:
    global_id = ifcopenshell.guid.compress("44444444444444448444444444444444")
    source = fake_glb(global_id)

    first, first_info = build_registration_model(source)
    second, second_info = build_registration_model(source)

    assert first == second
    assert first_info == second_info == inspect_registration_model(first)
    assert first[:4] == b"QSRG"
    assert first_info.bounds_min == pytest.approx((0.0, 0.0, 0.0))
    assert first_info.bounds_max == pytest.approx((1.0, 1.0, 0.0))
    assert 0.004 <= first_info.sample_spacing_m <= 0.025

    point_values = np.frombuffer(
        first,
        dtype="<f4",
        count=first_info.point_count * 6,
        offset=HEADER_SIZE,
    ).reshape((-1, 6))
    assert np.isfinite(point_values).all()
    assert np.linalg.norm(point_values[:, 3:6], axis=1) == pytest.approx(1.0)


def test_registration_model_rejects_corrupt_lengths_and_versions() -> None:
    global_id = ifcopenshell.guid.compress("44444444444444448444444444444444")
    payload, _ = build_registration_model(fake_glb(global_id))

    with pytest.raises(ValueError, match="byte length"):
        inspect_registration_model(payload[:-1])

    unsupported = bytearray(payload)
    struct.pack_into("<H", unsupported, 4, 99)
    with pytest.raises(ValueError, match="Unsupported"):
        inspect_registration_model(bytes(unsupported))


def test_registration_model_applies_node_transform_and_excludes_fastener_proxies() -> None:
    global_id = ifcopenshell.guid.compress("44444444444444448444444444444444")
    document, chunks = _read_glb_json(fake_glb(global_id))
    document["nodes"][0]["translation"] = [2.0, 3.0, 4.0]
    document["nodes"].append(
        {
            "name": "fabrication-mark-outlier",
            "mesh": 0,
            "translation": [100.0, 0.0, 0.0],
            "extras": {"fastenerCategory": "FABRICATION_MARK"},
        }
    )
    document["scenes"][0]["nodes"].append(1)

    payload, info = build_registration_model(_write_glb_json(document, chunks))

    assert inspect_registration_model(payload) == info
    assert info.bounds_min == pytest.approx((2.0, 3.0, 4.0))
    assert info.bounds_max == pytest.approx((3.0, 4.0, 4.0))


def test_registration_model_rejects_target_made_only_of_fasteners() -> None:
    global_id = ifcopenshell.guid.compress("44444444444444448444444444444444")
    document, chunks = _read_glb_json(fake_glb(global_id))
    document["meshes"][0]["extras"]["fastenerCategory"] = "PHYSICAL_FASTENER"

    with pytest.raises(ValueError, match="no structural triangle geometry"):
        build_registration_model(_write_glb_json(document, chunks))
