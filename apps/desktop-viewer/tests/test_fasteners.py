from types import SimpleNamespace

from qs_desktop_viewer.fasteners import (
    FastenerCategory,
    classify_mechanical_fastener,
)


def entity() -> SimpleNamespace:
    return SimpleNamespace(GlobalId="fastener-guid")


def properties(**values) -> dict:
    return {"Bolt Info": values}


def test_classifies_tekla_hole_proxies_before_generic_fasteners() -> None:
    classified = classify_mechanical_fastener(
        entity(),
        properties(
            BOLT_STANDARD="LOCH",
            MATERIAL="LOCH",
            **{"HOLE.DIAMETER_X": 50.0, "HOLE.DIAMETER_Y": 100.0},
        ),
    )

    assert classified.category == FastenerCategory.HOLE
    assert classified.hole_diameter_x == 50.0
    assert classified.hole_diameter_y == 100.0


def test_classifies_powder_lines_as_fabrication_marks() -> None:
    classified = classify_mechanical_fastener(
        entity(),
        properties(BOLT_STANDARD="DSTV", NAME="PULVERLINIE50"),
    )

    assert classified.category == FastenerCategory.MARK


def test_classifies_structural_bolts_as_physical() -> None:
    classified = classify_mechanical_fastener(
        entity(),
        properties(
            BOLT_STANDARD="EN-14399-4",
            NAME="M20*65Mu-EN14399-4",
            **{"HOLE.DIAMETER": 22.0},
        ),
    )

    assert classified.category == FastenerCategory.PHYSICAL
    assert classified.cut_from_physical_fastener is True
    assert classified.hole_diameter_x == 22.0
    assert classified.hole_diameter_y == 22.0


def test_nelson_studs_are_physical_but_do_not_create_through_holes() -> None:
    classified = classify_mechanical_fastener(
        entity(),
        properties(BOLT_STANDARD="NELSON", NAME="NELSON 22*150"),
    )

    assert classified.category == FastenerCategory.PHYSICAL
    assert classified.cut_from_physical_fastener is False


def test_unknown_fastener_is_not_silently_treated_as_a_bolt() -> None:
    classified = classify_mechanical_fastener(
        entity(),
        properties(BOLT_STANDARD="CUSTOM", NAME="Reference symbol"),
    )

    assert classified.category == FastenerCategory.UNKNOWN
