"""Project-wide IFC import history and safe rollback of the latest import."""

from __future__ import annotations

import json
import shutil
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .catalog import UNASSIGNED_POSITION_NUMBER


def _utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


class ImportHistoryStore:
    _lock = threading.RLock()

    def __init__(self, artifacts_root: Path) -> None:
        self.artifacts_root = artifacts_root.resolve()

    def _desktop(self, project_id: str) -> Path:
        project = (self.artifacts_root / project_id).resolve()
        if not project.is_relative_to(self.artifacts_root):
            raise KeyError(project_id)
        desktop = project / "desktop"
        if not (desktop / "project-index.json").is_file():
            raise KeyError(project_id)
        return desktop

    @staticmethod
    def _read(path: Path) -> dict[str, Any]:
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _write_atomic(path: Path, document: dict[str, Any]) -> None:
        temporary = path.with_suffix(".json.tmp")
        temporary.write_text(
            json.dumps(document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(path)

    @staticmethod
    def _records(entry: dict[str, Any]) -> list[dict[str, Any]]:
        records = entry.get("revisions")
        if isinstance(records, list) and records:
            return [dict(item) for item in records]
        return [
            {
                "revision": int(entry.get("revision", 1)),
                "folder": entry["folder"],
                "geometryFingerprint": entry.get("geometryFingerprint"),
                "importedAt": entry.get("importedAt"),
                "sourceFile": entry.get("sourceFile"),
                "sourceSha256": entry.get("sourceSha256"),
                "occurrenceCount": int(entry.get("occurrenceCount", 0)),
                "partCount": int(entry.get("partCount", 0)),
            }
        ]

    @staticmethod
    def _latest_id(index: dict[str, Any]) -> str | None:
        last = index.get("lastImport") or {}
        if last.get("id"):
            return str(last["id"])
        imports = index.get("imports") or []
        return str(imports[-1]["id"]) if imports else None

    def list(self, project_id: str) -> dict[str, Any]:
        with self._lock:
            desktop = self._desktop(project_id)
            index = self._read(desktop / "project-index.json")
            latest_id = self._latest_id(index)
            items = []
            for item in reversed(index.get("imports") or []):
                value = dict(item)
                value["isLatest"] = str(item.get("id")) == latest_id
                value["canDelete"] = value["isLatest"] and self._can_restore(
                    desktop, index, str(item.get("id"))
                )
                items.append(value)
            return {
                "imports": items,
                "deletePolicy": "ONLY_LATEST",
                "message": "Las revisiones creadas por un IFC se eliminan juntas para mantener el proyecto coherente.",
            }

    def _can_restore(
        self, desktop: Path, index: dict[str, Any], import_id: str
    ) -> bool:
        if (desktop / "index-history" / f"{import_id}.before.json").is_file():
            return True
        target = next(
            (item for item in index.get("imports") or [] if str(item.get("id")) == import_id),
            None,
        )
        if not target:
            return False
        target_hash = target.get("sourceSha256")
        entries = [
            *list(index.get("assemblies") or []),
            *list(index.get("retiredAssemblies") or []),
        ]
        return any(
            any(record.get("sourceSha256") != target_hash for record in self._records(entry))
            for entry in entries
        )

    @staticmethod
    def _matches_target(record: dict[str, Any], target: dict[str, Any]) -> bool:
        target_hash = target.get("sourceSha256")
        if target_hash and record.get("sourceSha256") == target_hash:
            return True
        return bool(
            record.get("sourceFile") == target.get("sourceFile")
            and record.get("importedAt") == target.get("importedAt")
        )

    def _legacy_restore(
        self, desktop: Path, index: dict[str, Any], target: dict[str, Any]
    ) -> tuple[dict[str, Any], list[tuple[str, int, str, bool]]]:
        history = {
            str(entry["positionNumber"]): dict(entry)
            for entry in [
                *list(index.get("retiredAssemblies") or []),
                *list(index.get("assemblies") or []),
            ]
            if entry.get("positionNumber")
        }
        active: list[dict[str, Any]] = []
        removed_scopes: list[tuple[str, int, str, bool]] = []
        for entry in history.values():
            source_key = str(
                entry.get("sourceKey") or Path(str(entry.get("folder", ""))).name
            )
            records = self._records(entry)
            remaining = []
            for record in records:
                if self._matches_target(record, target):
                    # Legacy indexes rewrote the provenance of an unchanged single
                    # revision during re-import. With no snapshot, keep those records:
                    # the import did not actually create a revision to remove.
                    if len(records) == 1 and int(target.get("createdPositions", 0)) == 0:
                        legacy_folder = f"sources/{source_key}"
                        if not (desktop / legacy_folder / "source_manifest.json").is_file():
                            raise ValueError(
                                f"No existe el paquete anterior necesario para restaurar {source_key}"
                            )
                        restored_record = dict(record)
                        restored_record["folder"] = legacy_folder
                        restored_record.pop("sourceFile", None)
                        restored_record.pop("sourceSha256", None)
                        restored_record.pop("importedAt", None)
                        remaining.append(restored_record)
                        removed_scopes.append(
                            (
                                source_key,
                                int(record.get("revision", 1)),
                                str(record.get("folder", "")),
                                False,
                            )
                        )
                        continue
                    removed_scopes.append(
                        (
                            source_key,
                            int(record.get("revision", 1)),
                            str(record.get("folder", "")),
                            True,
                        )
                    )
                else:
                    remaining.append(record)
            if not remaining:
                continue
            remaining.sort(key=lambda item: int(item.get("revision", 1)))
            latest = remaining[-1]
            restored = dict(entry)
            restored.update(
                {
                    "sourceKey": source_key,
                    "folder": latest["folder"],
                    "occurrenceCount": int(latest.get("occurrenceCount", 0)),
                    "partCount": int(latest.get("partCount", 0)),
                    "geometryFingerprint": latest.get("geometryFingerprint"),
                    "revision": int(latest.get("revision", 1)),
                    "revisionCount": len(remaining),
                    "revisions": remaining,
                }
            )
            active.append(restored)

        active.sort(
            key=lambda item: (
                item.get("positionNumber") == UNASSIGNED_POSITION_NUMBER,
                str(item.get("positionNumber", "")).casefold(),
            )
        )
        restored_index = dict(index)
        remaining_imports = [
            item
            for item in index.get("imports") or []
            if str(item.get("id")) != str(target.get("id"))
        ]
        if remaining_imports:
            latest_source = remaining_imports[-1]
            restored_source = {
                "file": latest_source.get("sourceFile"),
                "sha256": latest_source.get("sourceSha256"),
                "importId": latest_source.get("id"),
                "sharedForDesktop": True,
            }
        else:
            legacy_ifcs = sorted((desktop / "source").glob("*.ifc"))
            restored_source = {
                "file": legacy_ifcs[0].name if legacy_ifcs else "restored-legacy-source.ifc",
                "sharedForDesktop": True,
            }
        restored_index.update(
            {
                "generatedAt": _utc_now(),
                "source": restored_source,
                "assemblyOccurrenceCount": sum(int(item["occurrenceCount"]) for item in active),
                "assemblyPositionCount": len(active),
                "unassignedOccurrenceCount": sum(
                    int(item["occurrenceCount"])
                    for item in active
                    if item.get("positionNumber") == UNASSIGNED_POSITION_NUMBER
                ),
                "lastImport": remaining_imports[-1] if remaining_imports else None,
                "imports": remaining_imports,
                "assemblies": active,
                "retiredAssemblies": [],
                "sourceGeometryAudit": {"status": "UNKNOWN_AFTER_ROLLBACK"},
                "rollback": {
                    "deletedImportId": target.get("id"),
                    "deletedSourceFile": target.get("sourceFile"),
                    "restoredAt": _utc_now(),
                    "mode": "LEGACY_RECONSTRUCTION",
                },
            }
        )
        return restored_index, removed_scopes

    def _target_scopes(
        self, index: dict[str, Any], target: dict[str, Any]
    ) -> list[tuple[str, int, str, bool]]:
        result = []
        for entry in [
            *list(index.get("assemblies") or []),
            *list(index.get("retiredAssemblies") or []),
        ]:
            source_key = str(
                entry.get("sourceKey") or Path(str(entry.get("folder", ""))).name
            )
            for record in self._records(entry):
                if self._matches_target(record, target):
                    result.append(
                        (
                            source_key,
                            int(record.get("revision", 1)),
                            str(record.get("folder", "")),
                            record.get("changeType") != "UNCHANGED_STATE",
                        )
                    )
        return result

    def _clean_scoped_records(
        self, desktop: Path, scopes: set[tuple[str, int]]
    ) -> None:
        for filename, list_key in (("measurements.json", "measurements"),):
            path = desktop / filename
            if not path.is_file():
                continue
            document = self._read(path)
            values = document.get(list_key)
            if not isinstance(values, list):
                continue
            document[list_key] = [
                item
                for item in values
                if (
                    str(item.get("sourceId")), int(item.get("revision", 1))
                )
                not in {(f"desktop--{source_key}", revision) for source_key, revision in scopes}
            ]
            self._write_atomic(path, document)

    def _cleanup(
        self,
        desktop: Path,
        import_id: str,
        scopes: list[tuple[str, int, str, bool]],
    ) -> None:
        scoped_revisions = {
            (source_key, revision)
            for source_key, revision, _, remove_records in scopes
            if remove_records
        }
        for source_key, revision, folder, _ in scopes:
            package = (desktop / folder).resolve()
            revisions_root = (desktop / "revisions").resolve()
            if package.is_relative_to(revisions_root) and package.is_dir():
                shutil.rmtree(package)
            for cache_version in (desktop / "cache").glob("*"):
                cached = cache_version / f"desktop--{source_key}" / f"rev-{revision:04d}"
                if cached.is_dir():
                    shutil.rmtree(cached)
        self._clean_scoped_records(desktop, scoped_revisions)
        imported_source = desktop / "imports" / import_id
        if imported_source.is_dir():
            shutil.rmtree(imported_source)
        snapshot = desktop / "index-history" / f"{import_id}.before.json"
        snapshot.unlink(missing_ok=True)

    def delete_latest(self, project_id: str, import_id: str) -> dict[str, Any]:
        with self._lock:
            desktop = self._desktop(project_id)
            index_path = desktop / "project-index.json"
            index = self._read(index_path)
            if self._latest_id(index) != import_id:
                raise ValueError("Sólo se puede eliminar la última importación IFC")
            target = next(
                (
                    item
                    for item in index.get("imports") or []
                    if str(item.get("id")) == import_id
                ),
                None,
            )
            if target is None:
                raise KeyError(import_id)
            snapshot_path = desktop / "index-history" / f"{import_id}.before.json"
            if snapshot_path.is_file():
                restored = self._read(snapshot_path)
                scopes = self._target_scopes(index, target)
                mode = "SNAPSHOT"
            else:
                if not self._can_restore(desktop, index, import_id):
                    raise ValueError("No existe un estado anterior al que volver")
                restored, scopes = self._legacy_restore(desktop, index, target)
                mode = "LEGACY_RECONSTRUCTION"
            self._write_atomic(index_path, restored)
            self._cleanup(desktop, import_id, scopes)
            return {
                "deletedImportId": import_id,
                "deletedSourceFile": target.get("sourceFile"),
                "restoredPositionCount": len(restored.get("assemblies") or []),
                "mode": mode,
            }
