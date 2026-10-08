import json
from pathlib import Path

import pytest

from qs_desktop_viewer.measurements import MeasurementStore


def test_measurements_are_persisted_and_deleted_per_occurrence(tmp_path: Path) -> None:
    (tmp_path / "Project-A").mkdir()
    store = MeasurementStore(tmp_path)

    first = store.create("Project-A", "script--100001_1", "occ-a", [0, 0, 0], [0.1, 0, 0])
    store.create("Project-A", "script--100001_1", "occ-b", [0, 0, 0], [0, 0.2, 0])

    assert first["distanceMm"] == 100.0
    assert len(store.list("Project-A", "script--100001_1", "occ-a")) == 1
    assert store.delete("Project-A", "script--100001_1", "occ-a", first["id"])
    assert store.list("Project-A", "script--100001_1", "occ-a") == []
    assert len(store.list("Project-A", "script--100001_1", "occ-b")) == 1

    document = json.loads(
        (tmp_path / "Project-A" / "desktop" / "measurements.json").read_text(encoding="utf-8")
    )
    assert len(document["measurements"]) == 1


def test_measurement_rejects_identical_points(tmp_path: Path) -> None:
    (tmp_path / "Project-A").mkdir()
    with pytest.raises(ValueError, match="too close"):
        MeasurementStore(tmp_path).create(
            "Project-A", "source", "occurrence", [1, 1, 1], [1, 1, 1]
        )


def test_measurements_are_isolated_by_revision(tmp_path: Path) -> None:
    (tmp_path / "Project-A").mkdir()
    store = MeasurementStore(tmp_path)
    store.create(
        "Project-A",
        "source",
        "occurrence",
        [0, 0, 0],
        [0.1, 0, 0],
        revision=1,
    )
    store.create(
        "Project-A",
        "source",
        "occurrence",
        [0, 0, 0],
        [0.2, 0, 0],
        revision=2,
    )

    assert [item["distanceMm"] for item in store.list("Project-A", "source", "occurrence", 1)] == [100.0]
    assert [item["distanceMm"] for item in store.list("Project-A", "source", "occurrence", 2)] == [200.0]
