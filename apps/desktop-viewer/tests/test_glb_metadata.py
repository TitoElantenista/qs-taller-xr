from types import SimpleNamespace

import manifold3d
import numpy as np

from qs_desktop_viewer.glb import (
    FastenerSpatialIndex,
    PartGeometry,
    _component_bounds,
    _part_metadata,
    _render_mesh_from_manifold,
    _split_part_position,
    _validated_external_item_indices,
    find_external_fasteners,
)


def test_split_part_position_uses_six_digit_drawing_and_part_number() -> None:
    assert _split_part_position("191001.504") == ("191001", "504")
    assert _split_part_position("191001/504(?)") == ("191001", "504")


def test_split_part_position_rejects_tags_that_are_not_part_marks() -> None:
    assert _split_part_position("2uQ8S0x9P9wQ7N3m") is None
    assert _split_part_position("91001.504") is None
    assert _split_part_position(None) is None


def test_part_metadata_reads_ifc_weight_area_and_removes_duplicate_type(
    monkeypatch,
) -> None:
    entity = SimpleNamespace(
        Tag=None,
        Name="TRÄGER",
        Description=None,
        ObjectType="HEA200",
        file=object(),
    )

    def get_psets(member, *, psets_only):
        assert member is entity
        assert psets_only is False
        return {
            "Default": {
                "Part position number": "191001.501",
                "PROFILE": "HEA200",
                "MATERIAL": "S355",
            },
            "Qto_BeamBaseQuantities": {
                "NetWeight": 12.5,
                "SurfaceArea": 2.75,
            },
        }

    monkeypatch.setattr("qs_desktop_viewer.glb.ifcopenshell.util.element.get_psets", get_psets)
    monkeypatch.setattr(
        "qs_desktop_viewer.glb.ifcopenshell.util.element.get_materials",
        lambda _: [],
    )
    monkeypatch.setattr(
        "qs_desktop_viewer.glb.ifcopenshell.util.element.get_type",
        lambda _: SimpleNamespace(Name="HEA200"),
    )
    monkeypatch.setattr(
        "qs_desktop_viewer.glb.ifcopenshell.util.unit.calculate_unit_scale",
        lambda _, unit: 1.0 if unit == "AREAUNIT" else 1_000.0,
    )

    metadata = _part_metadata(entity, "191001.1")

    assert metadata["weightKg"] == "12.5"
    assert metadata["surfaceAreaM2"] == "2.75"
    assert metadata["profile"] == "HEA200"
    assert "typeName" not in metadata
    assert "objectType" not in metadata


def test_component_bounds_keep_individual_bolts_separate() -> None:
    vertices = np.asarray(
        [
            [0.0, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [10.0, 0.0, 0.0],
            [11.0, 0.0, 0.0],
            [10.0, 1.0, 0.0],
        ]
    )
    faces = np.asarray([[0, 1, 2], [3, 4, 5]])

    bounds = _component_bounds(vertices, faces)

    assert len(bounds) == 2
    assert sorted(float(lower[0]) for lower, _ in bounds) == [0.0, 10.0]


def test_boolean_render_mesh_keeps_right_angle_edges_sharp() -> None:
    vertices, faces, normals = _render_mesh_from_manifold(
        manifold3d.Manifold.cube((1.0, 1.0, 1.0), center=True)
    )

    assert len(vertices) == 24
    assert len(faces) == 12
    assert normals is not None
    assert np.allclose(np.linalg.norm(normals, axis=1), 1.0)
    corner = np.all(np.isclose(vertices, [-0.5, -0.5, -0.5]), axis=1)
    assert len({tuple(value) for value in normals[corner]}) == 3


def test_external_bolts_require_a_real_hole_intersection() -> None:
    applied = {"group:1", "group:3", "other:0"}

    assert _validated_external_item_indices("group", [0, 1, 2], applied) == [1]
    assert _validated_external_item_indices("group", [], applied) == [1, 3]
    assert _validated_external_item_indices("floating", [0, 1], applied) == []


def test_external_group_keeps_only_the_physical_bolt_that_connects(
    monkeypatch,
) -> None:
    direct_member = SimpleNamespace(GlobalId="direct-part")
    assembly = SimpleNamespace(GlobalId="target-assembly")
    model = SimpleNamespace(by_type=lambda _: [])
    lower = np.full((11, 3), 100.0)
    upper = np.full((11, 3), 101.0)
    lower[10] = [-0.01, -0.01, -0.01]
    upper[10] = [0.01, 0.01, 0.01]
    index = FastenerSpatialIndex(
        lower=lower,
        upper=upper,
        global_ids=["external-group"] * 11,
        item_indices=list(range(11)),
        item_counts={"external-group": 11},
        owners={
            "external-group": {
                "ownerAssemblySourceGlobalId": "other-assembly",
                "ownerAssemblyPositionNumber": "191009.5",
            }
        },
    )
    target = PartGeometry(
        global_id="direct-part",
        ifc_class="IfcPlate",
        name="Plate",
        vertices=np.asarray(
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]
        ),
        faces=np.asarray([[0, 1, 2]]),
        normals=None,
        metadata={},
    )
    monkeypatch.setattr(
        "qs_desktop_viewer.glb._assembly_members",
        lambda _: [direct_member],
    )
    monkeypatch.setattr(
        "qs_desktop_viewer.glb._fastener_spatial_index",
        lambda *_: index,
    )

    records = find_external_fasteners(
        model,
        assembly,
        target_geometry=[target],
    )

    assert records[0]["connectedItemIndices"] == [10]
    assert records[0]["fastenerItemCount"] == 11
