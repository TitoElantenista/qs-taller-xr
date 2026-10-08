"""Persistent project-local measurement storage."""

from __future__ import annotations

import json
import math
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


def _utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _validated_point(value: list[float]) -> list[float]:
    if len(value) != 3:
        raise ValueError("A measurement point must have exactly three coordinates")
    result = [float(item) for item in value]
    if not all(math.isfinite(item) and abs(item) <= 1_000_000 for item in result):
        raise ValueError("Measurement coordinates must be finite")
    return result


class MeasurementStore:
    _lock = threading.RLock()

    def __init__(self, artifacts_root: Path) -> None:
        self.artifacts_root = artifacts_root.resolve()

    def _path(self, project_id: str) -> Path:
        project_dir = (self.artifacts_root / project_id).resolve()
        if not project_dir.is_relative_to(self.artifacts_root) or not project_dir.is_dir():
            raise KeyError(project_id)
        return project_dir / "desktop" / "measurements.json"

    def _read(self, project_id: str) -> dict[str, Any]:
        path = self._path(project_id)
        if not path.is_file():
            return {"schemaVersion": "1.0.0", "measurements": []}
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ValueError(f"Cannot read {path}: {exc}") from exc
        if not isinstance(document.get("measurements"), list):
            raise ValueError(f"Invalid measurement store: {path}")
        return document

    def _write(self, project_id: str, document: dict[str, Any]) -> None:
        path = self._path(project_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".json.tmp")
        temporary.write_text(
            json.dumps(document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(path)

    def list(
        self,
        project_id: str,
        source_id: str,
        occurrence_id: str,
        revision: int = 1,
    ) -> list[dict[str, Any]]:
        with self._lock:
            measurements = self._read(project_id)["measurements"]
            return [
                item
                for item in measurements
                if item.get("sourceId") == source_id
                and item.get("occurrenceSourceGlobalId") == occurrence_id
                and int(item.get("revision", 1)) == revision
            ]

    def create(
        self,
        project_id: str,
        source_id: str,
        occurrence_id: str,
        start: list[float],
        end: list[float],
        label: str | None = None,
        revision: int = 1,
    ) -> dict[str, Any]:
        start_point = _validated_point(start)
        end_point = _validated_point(end)
        distance_mm = math.dist(start_point, end_point) * 1000.0
        if distance_mm < 0.01:
            raise ValueError("The two measurement points are too close")
        clean_label = (label or "").strip()[:120] or None
        measurement = {
            "id": str(uuid.uuid4()),
            "sourceId": source_id,
            "occurrenceSourceGlobalId": occurrence_id,
            "revision": revision,
            "start": start_point,
            "end": end_point,
            "distanceMm": round(distance_mm, 3),
            "label": clean_label,
            "createdAt": _utc_now(),
        }
        with self._lock:
            document = self._read(project_id)
            document["measurements"].append(measurement)
            self._write(project_id, document)
        return measurement

    def delete(
        self,
        project_id: str,
        source_id: str,
        occurrence_id: str,
        measurement_id: str,
        revision: int = 1,
    ) -> bool:
        with self._lock:
            document = self._read(project_id)
            before = len(document["measurements"])
            document["measurements"] = [
                item
                for item in document["measurements"]
                if not (
                    item.get("id") == measurement_id
                    and item.get("sourceId") == source_id
                    and item.get("occurrenceSourceGlobalId") == occurrence_id
                    and int(item.get("revision", 1)) == revision
                )
            ]
            changed = len(document["measurements"]) != before
            if changed:
                self._write(project_id, document)
            return changed

    def delete_all(
        self,
        project_id: str,
        source_id: str,
        occurrence_id: str,
        revision: int = 1,
    ) -> int:
        with self._lock:
            document = self._read(project_id)
            kept = [
                item
                for item in document["measurements"]
                if not (
                    item.get("sourceId") == source_id
                    and item.get("occurrenceSourceGlobalId") == occurrence_id
                    and int(item.get("revision", 1)) == revision
                )
            ]
            removed = len(document["measurements"]) - len(kept)
            if removed:
                document["measurements"] = kept
                self._write(project_id, document)
            return removed
