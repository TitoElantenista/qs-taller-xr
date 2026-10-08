"""Discover normalized Baugruppen packages under the local artifacts folder."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


POSITION_PATTERN = re.compile(
    r"^\s*(?P<drawing>\d{6})\s*[./]\s*(?P<position>\d+)(?:\s*\(\?\))?\s*$",
    re.IGNORECASE,
)
UNASSIGNED_POSITION_NUMBER = "__UNASSIGNED__"
UNASSIGNED_DRAWING_LABEL = "PENDIENTES"
UNASSIGNED_POSITION_LABEL = "Sin Positionsnummer"


def parse_position_number(
    value: str,
    explicit_drawing_number: str | None = None,
) -> tuple[str, str]:
    """Return drawing and position, accepting canonical dot and legacy slash."""

    if value == UNASSIGNED_POSITION_NUMBER:
        return UNASSIGNED_DRAWING_LABEL, UNASSIGNED_POSITION_LABEL

    match = POSITION_PATTERN.match(value)
    if match:
        return match.group("drawing"), str(int(match.group("position")))

    drawing = (explicit_drawing_number or "").strip()
    if re.fullmatch(r"\d{6}", drawing):
        trailing_number = re.search(r"(\d+)", value)
        if trailing_number:
            return drawing, str(int(trailing_number.group(1)))
        return drawing, value.strip()
    return "SIN-DIBUJO", value.strip()


def _position_sort_key(value: str) -> tuple[int, int | str]:
    if value.isdigit():
        return 0, int(value)
    return 1, value.casefold()


@dataclass(frozen=True)
class SourcePackage:
    project_id: str
    project_name: str
    producer_folder: str
    producer: str
    source_id: str
    producer_dir: Path
    package_dir: Path
    raw: dict[str, Any]

    @property
    def position_number(self) -> str:
        return str(self.raw["positionNumber"])

    @property
    def drawing_and_position(self) -> tuple[str, str]:
        return parse_position_number(
            self.position_number,
            self.raw.get("drawingNumber"),
        )

    def summary(self) -> dict[str, Any]:
        drawing, position = self.drawing_and_position
        is_unassigned = self.position_number == UNASSIGNED_POSITION_NUMBER
        return {
            "id": self.source_id,
            "positionNumber": self.position_number,
            "drawingNumber": drawing,
            "position": position,
            "isUnassigned": is_unassigned,
            "producer": self.producer,
            "producerFolder": self.producer_folder,
            "numberingStatus": self.raw.get("numberingStatus", "CURRENT"),
            "occurrenceCount": int(self.raw.get("occurrenceCount", 0)),
            "partCount": int(self.raw.get("partCount", 0)),
            "revision": int(self.raw.get("revision", 1)),
            "revisionCount": int(self.raw.get("revisionCount", 1)),
            "revisions": [
                {
                    "revision": int(item.get("revision", 1)),
                    "importedAt": item.get("importedAt"),
                    "sourceFile": item.get("sourceFile"),
                    "occurrenceCount": int(item.get("occurrenceCount", 0)),
                    "partCount": int(item.get("partCount", 0)),
                }
                for item in self.raw.get("revisions", [])
            ]
            or [
                {
                    "revision": 1,
                    "importedAt": None,
                    "sourceFile": None,
                    "occurrenceCount": int(self.raw.get("occurrenceCount", 0)),
                    "partCount": int(self.raw.get("partCount", 0)),
                }
            ],
        }

    @property
    def ifc_path(self) -> Path:
        manifest_path = self.package_dir / "source_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        relative_path = str((manifest.get("files") or {}).get("ifc") or "assembly.ifc")
        candidate = (self.package_dir / relative_path).resolve()
        if not candidate.is_file():
            raise FileNotFoundError(candidate)
        return candidate


class ProjectCatalog:
    def __init__(self, artifacts_root: Path) -> None:
        self.artifacts_root = artifacts_root.resolve()

    def packages(self) -> list[SourcePackage]:
        result: list[SourcePackage] = []
        if not self.artifacts_root.is_dir():
            return result

        for index_path in sorted(self.artifacts_root.glob("*/*/project-index.json")):
            try:
                index = json.loads(index_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError, TypeError):
                continue

            project_dir = index_path.parent.parent
            producer_dir = index_path.parent
            project = index.get("project") or {}
            project_name = str(project.get("name") or project_dir.name)
            producer = str(index.get("producer") or producer_dir.name.upper())
            for entry in index.get("assemblies", []):
                relative_folder = Path(str(entry.get("folder", "")))
                package_dir = (producer_dir / relative_folder).resolve()
                if not package_dir.is_relative_to(producer_dir.resolve()):
                    continue
                if not (package_dir / "source_manifest.json").is_file():
                    continue
                source_key = str(entry.get("sourceKey") or package_dir.name)
                source_id = f"{producer_dir.name}--{source_key}"
                result.append(
                    SourcePackage(
                        project_id=project_dir.name,
                        project_name=project_name,
                        producer_folder=producer_dir.name,
                        producer=producer,
                        source_id=source_id,
                        producer_dir=producer_dir.resolve(),
                        package_dir=package_dir,
                        raw=entry,
                    )
                )
        # A lightweight desktop index may coexist with independently extracted
        # packages. Prefer the independent package for the same position.
        independent = {
            (item.project_id, item.position_number)
            for item in result
            if item.producer_folder != "desktop"
        }
        versioned_desktop = {
            (item.project_id, item.position_number)
            for item in result
            if item.producer_folder == "desktop" and item.raw.get("revisions")
        }
        return [
            item
            for item in result
            if not (
                item.producer_folder != "desktop"
                and (item.project_id, item.position_number) in versioned_desktop
            )
            and not (
                item.producer_folder == "desktop"
                and not item.raw.get("revisions")
                and (item.project_id, item.position_number) in independent
            )
        ]

    def get(self, project_id: str, source_id: str) -> SourcePackage:
        for package in self.packages():
            if package.project_id == project_id and package.source_id == source_id:
                return package
        raise KeyError((project_id, source_id))

    def get_revision(
        self,
        project_id: str,
        source_id: str,
        revision: int | None,
    ) -> SourcePackage:
        return self.revision(self.get(project_id, source_id), revision)

    def revision(
        self,
        package: SourcePackage,
        revision: int | None,
    ) -> SourcePackage:
        """Resolve a revision from an already discovered package."""

        if revision is None or revision == int(package.raw.get("revision", 1)):
            return package
        for record in package.raw.get("revisions", []):
            if int(record.get("revision", 0)) != revision:
                continue
            package_dir = (package.producer_dir / str(record["folder"])).resolve()
            if not package_dir.is_relative_to(package.producer_dir):
                break
            if not (package_dir / "source_manifest.json").is_file():
                break
            raw = dict(package.raw)
            raw.update(record)
            raw["revision"] = revision
            raw["folder"] = record["folder"]
            return SourcePackage(
                project_id=package.project_id,
                project_name=package.project_name,
                producer_folder=package.producer_folder,
                producer=package.producer,
                source_id=package.source_id,
                producer_dir=package.producer_dir,
                package_dir=package_dir,
                raw=raw,
            )
        raise KeyError((package.project_id, package.source_id, revision))

    def document(self) -> dict[str, Any]:
        projects: dict[str, dict[str, Any]] = {}
        for package in self.packages():
            project = projects.setdefault(
                package.project_id,
                {"id": package.project_id, "name": package.project_name, "drawings": {}},
            )
            summary = package.summary()
            drawing_number = summary["drawingNumber"]
            drawing = project["drawings"].setdefault(
                drawing_number,
                {"number": drawing_number, "positions": []},
            )
            drawing["positions"].append(summary)

        result = []
        for project in projects.values():
            desktop_index = (
                self.artifacts_root
                / str(project["id"])
                / "desktop"
                / "project-index.json"
            )
            try:
                desktop_document = json.loads(
                    desktop_index.read_text(encoding="utf-8")
                )
                geometry_audit = desktop_document.get("sourceGeometryAudit")
                if isinstance(geometry_audit, dict):
                    project["sourceGeometryAudit"] = geometry_audit
            except (OSError, json.JSONDecodeError, TypeError):
                pass
            drawings = list(project.pop("drawings").values())
            drawings.sort(
                key=lambda item: (
                    item["number"] in {"SIN-DIBUJO", UNASSIGNED_DRAWING_LABEL},
                    item["number"] == UNASSIGNED_DRAWING_LABEL,
                    item["number"].casefold(),
                )
            )
            for drawing in drawings:
                drawing["positions"].sort(
                    key=lambda item: (
                        _position_sort_key(item["position"]),
                        item["producer"].casefold(),
                    )
                )
            project["drawings"] = drawings
            project["drawingCount"] = len(drawings)
            project["positionCount"] = sum(len(item["positions"]) for item in drawings)
            result.append(project)
        result.sort(key=lambda item: item["name"].casefold())
        return {"projects": result}


def load_occurrences(package: SourcePackage) -> list[dict[str, Any]]:
    path = package.package_dir / "occurrences.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    occurrences = []
    for index, item in enumerate(document.get("occurrences", []), start=1):
        properties = item.get("properties") or {}
        tekla = properties.get("Tekla Assembly") or {}
        occurrences.append(
            {
                "index": index,
                "sourceGlobalId": item["sourceGlobalId"],
                "guid": item.get("guid"),
                "name": item.get("name") or f"Ocurrencia {index}",
                "memberCount": int(item.get("memberCount", 0)),
                "gridPosition": tekla.get("Assembly/Cast unit position code"),
                "provisionalPositionNumber": item.get("provisionalPositionNumber"),
            }
        )
    if package.position_number == UNASSIGNED_POSITION_NUMBER:
        occurrences.sort(
            key=lambda item: (
                (item.get("provisionalPositionNumber") or "").casefold(),
                item["name"].casefold(),
                item["sourceGlobalId"],
            )
        )
        for index, item in enumerate(occurrences, start=1):
            item["index"] = index
    return occurrences
