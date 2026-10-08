"""Create a fast desktop catalog that shares one complete IFC source file."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import unicodedata
import uuid
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import ifcopenshell
import ifcopenshell.guid
import ifcopenshell.util.element
import ifcopenshell.util.placement
import ifcopenshell.util.unit
import numpy as np

from .catalog import POSITION_PATTERN, UNASSIGNED_POSITION_NUMBER
from .fasteners import audit_mechanical_fasteners
from .glb import find_external_fasteners


POSITION_KEYS = (
    "QS_ASSEMBLY_POS",
    "ASSEMBLY_POS",
    "Assembly Position Number",
    "Assembly/Cast unit Mark",
)
DRAWING_KEYS = (
    "QS_DRAWING_NUMBER",
    "DRAWING_NUMBER",
    "Drawing Number",
    "Zeichnungsnummer",
)


class ImportValidationError(ValueError):
    """An IFC failed a non-destructive preflight validation."""

    def __init__(
        self,
        code: str,
        title: str,
        message: str,
        *,
        problems: list[str],
        recommendations: list[str],
        stats: dict[str, Any],
    ) -> None:
        super().__init__(message)
        self.code = code
        self.title = title
        self.message = message
        self.problems = problems
        self.recommendations = recommendations
        self.stats = stats

    def as_detail(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "title": self.title,
            "message": self.message,
            "problems": self.problems,
            "recommendations": self.recommendations,
            "stats": self.stats,
            "imported": False,
        }


def _utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _folder_name(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"_+", "_", re.sub(r"[^A-Za-z0-9._-]+", "_", normalized)).strip("._-") or "unnamed"


def _json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items() if key != "id"}
    if isinstance(value, (list, tuple, set)):
        return [_json_safe(item) for item in value]
    if hasattr(value, "item"):
        return _json_safe(value.item())
    return str(value)


def _property_sets(entity: Any) -> dict[str, Any]:
    return _json_safe(ifcopenshell.util.element.get_psets(entity, psets_only=True))


def _find_property(properties: dict[str, Any], keys: tuple[str, ...]) -> str | None:
    normalized_keys = {key.casefold() for key in keys}
    for values in properties.values():
        if not isinstance(values, dict):
            continue
        for property_name, value in values.items():
            if property_name.casefold() not in normalized_keys:
                continue
            if value is not None and str(value).strip():
                return str(value).strip()
    return None


def _members(assembly: Any) -> list[Any]:
    members: dict[str, Any] = {}
    for relation in getattr(assembly, "IsDecomposedBy", ()):
        if relation.is_a("IfcRelAggregates"):
            for member in relation.RelatedObjects:
                if getattr(member, "GlobalId", None):
                    members[member.GlobalId] = member
    return list(members.values())


def _expanded_guid(global_id: str) -> str:
    try:
        return str(uuid.UUID(hex=ifcopenshell.guid.expand(global_id)))
    except (TypeError, ValueError):
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"ifc:{global_id}"))


def _source_units(model: Any) -> tuple[str, float]:
    scale = float(ifcopenshell.util.unit.calculate_unit_scale(model))
    if math.isclose(scale, 0.001, abs_tol=1e-12):
        return "mm", scale
    if math.isclose(scale, 1.0, abs_tol=1e-12):
        return "m", scale
    return "project-unit", scale


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _business_position_or_unassigned(value: str | None) -> str:
    if value and POSITION_PATTERN.fullmatch(value):
        return value
    return UNASSIGNED_POSITION_NUMBER


def _position_metadata_diagnostics(model: Any) -> dict[str, Any]:
    assemblies = model.by_type("IfcElementAssembly")
    property_value_count = 0
    tag_value_count = 0
    metadata_value_count = 0
    business_occurrence_count = 0
    business_positions: set[str] = set()
    for assembly in assemblies:
        properties = _property_sets(assembly)
        property_value = _find_property(properties, POSITION_KEYS)
        tag_value = str(getattr(assembly, "Tag", "") or "").strip() or None
        if property_value:
            property_value_count += 1
        if tag_value:
            tag_value_count += 1
        raw_value = property_value or tag_value
        if raw_value:
            metadata_value_count += 1
        position = _business_position_or_unassigned(raw_value)
        if position != UNASSIGNED_POSITION_NUMBER:
            business_occurrence_count += 1
            business_positions.add(position)
    descriptions = [str(item) for item in model.header.file_description.description]
    return {
        "schema": model.schema,
        "viewDescription": descriptions[0] if descriptions else None,
        "assemblyCount": len(assemblies),
        "positionPropertyCount": property_value_count,
        "tagCount": tag_value_count,
        "positionMetadataCount": metadata_value_count,
        "assignedOccurrenceCount": business_occurrence_count,
        "assignedPositionCount": len(business_positions),
        "businessPositions": sorted(business_positions),
    }


def _validate_import_position_data(
    diagnostics: dict[str, Any], previous_index: dict[str, Any]
) -> None:
    if int(diagnostics["assemblyCount"]) == 0:
        raise ImportValidationError(
            "IFC_ASSEMBLIES_MISSING",
            "El IFC no contiene Baugruppen",
            "No se ha importado ningún dato porque el archivo no contiene IfcElementAssembly.",
            problems=["No se encontró ningún IfcElementAssembly."],
            recommendations=["Activa la exportación de Assemblies en Tekla y vuelve a exportar."],
            stats={key: value for key, value in diagnostics.items() if key != "businessPositions"},
        )

    active_positions = {
        str(item.get("positionNumber"))
        for item in previous_index.get("assemblies") or []
        if item.get("positionNumber") not in (None, UNASSIGNED_POSITION_NUMBER)
    }
    # A legacy failed import may already have moved every valid position to the
    # retired list. Use it only as a recovery baseline when no active position remains.
    retired_positions = {
        str(item.get("positionNumber"))
        for item in previous_index.get("retiredAssemblies") or []
        if item.get("positionNumber") not in (None, UNASSIGNED_POSITION_NUMBER)
    }
    previous_positions = active_positions or retired_positions
    if not previous_positions:
        return

    new_positions = set(diagnostics["businessPositions"])
    missing_positions = previous_positions - new_positions
    loss_ratio = len(missing_positions) / len(previous_positions)
    stats = {
        key: value for key, value in diagnostics.items() if key != "businessPositions"
    }
    stats.update(
        {
            "previousAssignedPositionCount": len(previous_positions),
            "positionsMissingFromUpdate": len(missing_positions),
            "positionLossPercent": round(loss_ratio * 100, 1),
        }
    )

    if int(diagnostics["positionMetadataCount"]) == 0:
        raise ImportValidationError(
            "IFC_POSITION_DATA_MISSING",
            "Falta la Positionsnummer de las Baugruppen",
            "La actualización se ha cancelado antes de modificar el proyecto.",
            problems=[
                f"Las {diagnostics['assemblyCount']} Baugruppen carecen de Tag y de Assembly position number.",
                f"La importación retiraría {len(missing_positions)} posiciones existentes.",
                f"Exportación detectada: {diagnostics['schema']} · {diagnostics.get('viewDescription') or 'vista desconocida'}.",
            ],
            recommendations=[
                "En Tekla activa Assemblies y Property sets.",
                "Incluye Assembly position number/ASSEMBLY_POS en el property set exportado.",
                "Usa la misma configuración de exportación que el IFC anterior y vuelve a intentarlo.",
            ],
            stats=stats,
        )

    if int(diagnostics["assignedPositionCount"]) == 0:
        raise ImportValidationError(
            "IFC_POSITION_FORMAT_INVALID",
            "No se reconoce ninguna Positionsnummer",
            "El IFC contiene valores de posición, pero ninguno cumple el formato ######.n.",
            problems=[
                f"Se encontraron metadatos en {diagnostics['positionMetadataCount']} Baugruppen, pero cero posiciones válidas.",
            ],
            recommendations=[
                "Comprueba que Assembly position number tenga seis cifras, un punto y la posición.",
                "También se admite temporalmente el separador '/' de los IFC antiguos.",
            ],
            stats=stats,
        )

    if len(missing_positions) >= 10 and loss_ratio >= 0.5:
        raise ImportValidationError(
            "IFC_POSITION_LOSS_SUSPICIOUS",
            "La actualización eliminaría demasiadas posiciones",
            "La importación se ha cancelado porque el cambio de catálogo parece anómalo.",
            problems=[
                f"Desaparecerían {len(missing_positions)} de {len(previous_positions)} posiciones ({loss_ratio:.0%}).",
                f"El IFC nuevo sólo contiene {diagnostics['assignedPositionCount']} posiciones reconocidas.",
            ],
            recommendations=[
                "Revisa el alcance y los property sets de la exportación de Tekla.",
                "Si se trata realmente de otro alcance, créalo como proyecto independiente.",
            ],
            stats=stats,
        )


def _source_geometry_audit(model: Any) -> dict[str, Any]:
    descriptions = [str(item) for item in model.header.file_description.description]
    header_text = " ".join(descriptions)
    if "Option [Welds:On]" in header_text:
        weld_option = "ON"
    elif "Option [Welds:Off]" in header_text:
        weld_option = "OFF"
    else:
        weld_option = "UNKNOWN"
    fasteners = model.by_type("IfcFastener")
    weld_count = sum(
        not item.is_a("IfcMechanicalFastener")
        and str(getattr(item, "Name", "") or "").casefold() == "weld"
        for item in fasteners
    )
    return {
        "weldExportOption": weld_option,
        "weldCount": weld_count,
        "mechanicalFastenerCount": len(model.by_type("IfcMechanicalFastener")),
        "openingElementCount": len(model.by_type("IfcOpeningElement")),
        "voidRelationshipCount": len(model.by_type("IfcRelVoidsElement")),
        **audit_mechanical_fasteners(model),
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_ifc_value(value: Any, stack: set[int] | None = None) -> Any:
    """Return a stable representation graph without STEP ids or global context."""

    stack = stack or set()
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float):
        return round(value, 6)
    if isinstance(value, (list, tuple)):
        return [_canonical_ifc_value(item, stack) for item in value]
    if hasattr(value, "is_a") and hasattr(value, "id"):
        entity_id = value.id()
        if entity_id in stack:
            return [value.is_a(), "cycle"]
        next_stack = stack | {entity_id}
        attributes = []
        for index in range(len(value)):
            name = value.attribute_name(index)
            if name in {"ContextOfItems", "OwnerHistory"}:
                continue
            attributes.append(
                [name, _canonical_ifc_value(value[index], next_stack)]
            )
        return [value.is_a(), attributes]
    if hasattr(value, "item"):
        return _canonical_ifc_value(value.item(), stack)
    return str(value)


def _stable_hash(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _part_signature(part: Any, cache: dict[int, str]) -> str:
    representation = getattr(part, "Representation", None)
    representation_id = representation.id() if representation else 0
    representation_hash = cache.get(representation_id)
    if representation_hash is None:
        representation_hash = _stable_hash(
            _canonical_ifc_value(representation) if representation else None
        )
        cache[representation_id] = representation_hash
    properties = _property_sets(part)
    return _stable_hash(
        {
            "ifcClass": part.is_a(),
            "name": getattr(part, "Name", None),
            "objectType": getattr(part, "ObjectType", None),
            "representation": representation_hash,
            "properties": properties,
        }
    )


def _occurrence_signature(
    assembly: Any,
    cache: dict[int, str],
    unit_scale_to_metres: float,
    external_fasteners: list[Any] | None = None,
) -> str:
    direct_members = sorted(_members(assembly), key=lambda item: item.GlobalId)
    external_ids = {
        str(item.GlobalId)
        for item in external_fasteners or []
        if getattr(item, "GlobalId", None)
    }
    direct_ids = {str(member.GlobalId) for member in direct_members}
    members = [
        *direct_members,
        *sorted(
            (
                item
                for item in external_fasteners or []
                if item.GlobalId not in direct_ids
            ),
            key=lambda item: item.GlobalId,
        ),
    ]
    part_data = []
    for member in members:
        matrix = ifcopenshell.util.placement.get_local_placement(member.ObjectPlacement)
        scope = "EXTERNAL_CONNECTED" if member.GlobalId in external_ids else "ASSEMBLY"
        part_data.append((_part_signature(member, cache), matrix, scope))

    pairwise = []
    for left_index, (left_hash, left_matrix, left_scope) in enumerate(part_data):
        for right_hash, right_matrix, right_scope in part_data[left_index + 1 :]:
            left_rotation = np.asarray(left_matrix[:3, :3], dtype=float)
            right_rotation = np.asarray(right_matrix[:3, :3], dtype=float)
            distance_metres = float(
                np.linalg.norm(
                    np.asarray(left_matrix[:3, 3], dtype=float)
                    - np.asarray(right_matrix[:3, 3], dtype=float)
                )
            ) * unit_scale_to_metres
            relative = left_rotation.T @ right_rotation
            first_hash, second_hash = left_hash, right_hash
            if first_hash > second_hash:
                first_hash, second_hash = second_hash, first_hash
                relative = relative.T
            elif first_hash == second_hash:
                direct = tuple(round(float(item), 5) for item in relative.flat)
                inverse = tuple(round(float(item), 5) for item in relative.T.flat)
                if inverse < direct:
                    relative = relative.T
            pairwise.append(
                [
                    first_hash,
                    second_hash,
                    *sorted((left_scope, right_scope)),
                    round(distance_metres, 5),
                    [round(float(item), 5) for item in relative.flat],
                ]
            )
    return _stable_hash(
        {
            "parts": sorted([item[2], item[0]] for item in part_data),
            "pairwise": sorted(pairwise),
        }
    )


def _prepare_groups(
    model: Any,
    ifc_path: Path | None = None,
) -> tuple[list[dict[str, Any]], int]:
    grouped: dict[str, list[tuple[Any, dict[str, Any], str | None]]] = defaultdict(list)
    drawing_numbers: dict[str, set[str]] = defaultdict(set)
    unassigned_occurrence_count = 0
    for assembly in model.by_type("IfcElementAssembly"):
        properties = _property_sets(assembly)
        provisional_position = (
            _find_property(properties, POSITION_KEYS)
            or str(getattr(assembly, "Tag", "") or "").strip()
            or None
        )
        position = _business_position_or_unassigned(provisional_position)
        if position == UNASSIGNED_POSITION_NUMBER:
            unassigned_occurrence_count += 1
        grouped[position].append((assembly, properties, provisional_position))
        if position != UNASSIGNED_POSITION_NUMBER:
            drawing = _find_property(properties, DRAWING_KEYS)
            if drawing:
                drawing_numbers[position].add(drawing)

    representation_cache: dict[int, str] = {}
    unit_scale_to_metres = float(ifcopenshell.util.unit.calculate_unit_scale(model))
    result = []
    sorted_positions = sorted(
        grouped,
        key=lambda value: (value == UNASSIGNED_POSITION_NUMBER, value.casefold()),
    )
    total = len(sorted_positions)
    for index, position in enumerate(sorted_positions, start=1):
        occurrence_data = sorted(grouped[position], key=lambda item: item[0].GlobalId)
        occurrences = []
        occurrence_signatures = set()
        part_count = 0
        for assembly, properties, provisional_position in occurrence_data:
            members = _members(assembly)
            external_fastener_records = find_external_fasteners(
                model,
                assembly,
                ifc_path,
            )
            external_fasteners = [
                item
                for record in external_fastener_records
                if (item := model.by_guid(record["sourceGlobalId"])) is not None
            ]
            member_count = len(members)
            part_count += member_count
            occurrence_signatures.add(
                _occurrence_signature(
                    assembly,
                    representation_cache,
                    unit_scale_to_metres,
                    external_fasteners,
                )
            )
            occurrences.append(
                {
                    "sourceGlobalId": assembly.GlobalId,
                    "guid": _expanded_guid(assembly.GlobalId),
                    "name": getattr(assembly, "Name", None),
                    "memberCount": member_count,
                    "externalFastenerCount": len(external_fastener_records),
                    "externalFasteners": external_fastener_records,
                    "properties": properties,
                    "provisionalPositionNumber": (
                        provisional_position
                        if position == UNASSIGNED_POSITION_NUMBER
                        else None
                    ),
                }
            )
        numbers = drawing_numbers[position]
        drawing_number = next(iter(numbers)) if len(numbers) == 1 else None
        if position == UNASSIGNED_POSITION_NUMBER:
            numbering_status = "UNASSIGNED"
        else:
            numbering_status = "QUESTIONABLE" if "?" in position else "CURRENT"
        result.append(
            {
                "positionNumber": position,
                "numberingStatus": numbering_status,
                "drawingNumber": drawing_number,
                "occurrenceCount": len(occurrences),
                "partCount": part_count,
                "occurrences": occurrences,
                # Occurrence counts and global placement are intentionally excluded.
                "geometryFingerprint": _stable_hash(sorted(occurrence_signatures)),
            }
        )
        if index == 1 or index % 25 == 0 or index == total:
            print(f"[{index}/{total}] fingerprinted")
    return result, unassigned_occurrence_count


def _resolve_package_ifc(desktop_folder: Path, entry: dict[str, Any]) -> Path | None:
    package_dir = desktop_folder / str(entry.get("folder", ""))
    manifest_path = package_dir / "source_manifest.json"
    if not manifest_path.is_file():
        return None
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        relative = str((manifest.get("files") or {}).get("ifc") or "assembly.ifc")
        candidate = (package_dir / relative).resolve()
        return candidate if candidate.is_file() else None
    except (OSError, json.JSONDecodeError, TypeError):
        return None


def _revision_records(entry: dict[str, Any], fallback_fingerprint: str | None) -> list[dict[str, Any]]:
    existing = entry.get("revisions")
    if isinstance(existing, list) and existing:
        return [dict(item) for item in existing]
    return [
        {
            "revision": int(entry.get("revision", 1)),
            "folder": entry["folder"],
            "geometryFingerprint": entry.get("geometryFingerprint") or fallback_fingerprint,
            "importedAt": entry.get("generatedAt"),
            "sourceFile": entry.get("sourceFile"),
            "occurrenceCount": int(entry.get("occurrenceCount", 0)),
            "partCount": int(entry.get("partCount", 0)),
        }
    ]


def _occurrence_state_matches(
    desktop_folder: Path,
    previous: dict[str, Any],
    prepared: dict[str, Any],
) -> bool:
    path = desktop_folder / str(previous.get("folder", "")) / "occurrences.json"
    if not path.is_file():
        return False
    try:
        existing = json.loads(path.read_text(encoding="utf-8")).get("occurrences") or []
    except (OSError, json.JSONDecodeError, TypeError):
        return False

    def signature(item: dict[str, Any]) -> tuple[Any, ...]:
        return (
            item.get("sourceGlobalId"),
            int(item.get("memberCount", 0)),
            item.get("provisionalPositionNumber"),
        )

    return sorted(map(signature, existing)) == sorted(
        map(signature, prepared["occurrences"])
    )


def _write_revision_package(
    *,
    package_dir: Path,
    shared_source: Path,
    project_name: str,
    model: Any,
    prepared: dict[str, Any],
    generated_at: str,
    revision: int,
) -> None:
    if package_dir.exists():
        shutil.rmtree(package_dir)
    package_dir.mkdir(parents=True)
    _write_json(
        package_dir / "occurrences.json",
        {
            "schemaVersion": "1.0.0",
            "assemblyPositionNumber": prepared["positionNumber"],
            "occurrences": prepared["occurrences"],
        },
    )
    relative_ifc = Path(os.path.relpath(shared_source, package_dir)).as_posix()
    _write_json(
        package_dir / "source_manifest.json",
        {
            "schemaVersion": "1.0.0",
            "producer": "IFC_DESKTOP_INDEX",
            "generatedAt": generated_at,
            "revision": revision,
            "geometryFingerprint": prepared["geometryFingerprint"],
            "project": {"name": project_name},
            "assembly": {
                "positionNumber": prepared["positionNumber"],
                "numberingStatus": prepared["numberingStatus"],
                "drawingNumber": prepared["drawingNumber"],
                "occurrenceCount": prepared["occurrenceCount"],
                "partCount": prepared["partCount"],
            },
            "source": {
                "system": "IFC",
                "schema": model.schema,
                "originalFile": shared_source.name,
                "lengthUnit": _source_units(model)[0],
                "unitScaleToMetres": _source_units(model)[1],
                "sharedForDesktop": True,
            },
            "files": {"ifc": relative_ifc, "occurrences": "occurrences.json"},
        },
    )


def build_desktop_index(
    source_path: Path,
    output_root: Path,
    project_name: str | None = None,
    overwrite: bool = False,
) -> Path:
    source_path = source_path.resolve()
    model = ifcopenshell.open(source_path)
    projects = model.by_type("IfcProject")
    ifc_project_name = getattr(projects[0], "Name", None) if projects else None
    name = (project_name or ifc_project_name or source_path.stem).strip()
    project_folder = output_root.resolve() / _folder_name(name)
    desktop_folder = project_folder / "desktop"
    index_path = desktop_folder / "project-index.json"
    if index_path.exists() and not overwrite:
        raise FileExistsError(f"Desktop project already exists: {index_path}")

    previous_index: dict[str, Any] = {}
    if index_path.is_file():
        previous_index = json.loads(index_path.read_text(encoding="utf-8"))

    diagnostics = _position_metadata_diagnostics(model)
    _validate_import_position_data(diagnostics, previous_index)
    prepared_groups, unassigned_occurrence_count = _prepare_groups(model, source_path)
    generated_at = _utc_now()
    source_audit = _source_geometry_audit(model)
    source_hash = _sha256(source_path)
    import_id = source_hash[:16]
    safe_source_name = Path(source_path.name).name
    desktop_folder.mkdir(parents=True, exist_ok=True)
    if previous_index:
        _write_json(
            desktop_folder / "index-history" / f"{import_id}.before.json",
            previous_index,
        )
    shared_source = desktop_folder / "imports" / import_id / safe_source_name
    shared_source.parent.mkdir(parents=True, exist_ok=True)
    if not shared_source.is_file() or shared_source.stat().st_size != source_path.stat().st_size:
        shutil.copy2(source_path, shared_source)
    previous_active = previous_index.get("assemblies") or []
    previous_retired = previous_index.get("retiredAssemblies") or []
    history_by_position = {
        str(entry["positionNumber"]): dict(entry)
        for entry in [*previous_retired, *previous_active]
        if entry.get("positionNumber")
    }

    # Upgrade legacy entries with fingerprints when possible. When the exact source
    # is imported again, the new fingerprint is authoritative and no false revision
    # is created. For a different source, fingerprint the previous IFC once.
    prepared_by_position = {
        str(item["positionNumber"]): item for item in prepared_groups
    }
    legacy_entries = [
        entry for entry in history_by_position.values() if not entry.get("geometryFingerprint")
    ]
    if legacy_entries:
        old_ifc = None
        if previous_active:
            old_ifc = _resolve_package_ifc(desktop_folder, previous_active[0])
        if old_ifc and old_ifc.is_file() and _sha256(old_ifc) != source_hash:
            print("Fingerprinting previous IFC to migrate revision history...")
            old_model = ifcopenshell.open(old_ifc)
            old_prepared, _ = _prepare_groups(old_model, old_ifc)
            old_fingerprints = {
                item["positionNumber"]: item["geometryFingerprint"]
                for item in old_prepared
            }
        else:
            old_fingerprints = {
                position: item["geometryFingerprint"]
                for position, item in prepared_by_position.items()
            }
        for entry in legacy_entries:
            entry["geometryFingerprint"] = old_fingerprints.get(entry["positionNumber"])

    entries: list[dict[str, Any]] = []
    used_source_keys = {
        str(entry.get("sourceKey"))
        for entry in history_by_position.values()
        if entry.get("sourceKey")
    }
    created_count = 0
    revised_count = 0
    unchanged_count = 0

    for index, prepared in enumerate(prepared_groups, start=1):
        position = str(prepared["positionNumber"])
        previous = history_by_position.get(position)
        changed = False
        occurrence_state_changed = False
        if previous:
            source_key = str(
                previous.get("sourceKey")
                or previous.get("sourceId")
                or Path(str(previous["folder"])).name
            )
            previous_fingerprint = previous.get("geometryFingerprint")
            revisions = _revision_records(previous, previous_fingerprint)
            current_revision = int(previous.get("revision") or revisions[-1]["revision"])
            changed = previous_fingerprint != prepared["geometryFingerprint"]
            occurrence_state_changed = not changed and not _occurrence_state_matches(
                desktop_folder, previous, prepared
            )
            revision = current_revision + 1 if changed else current_revision
            if changed:
                revised_count += 1
            else:
                unchanged_count += 1
        else:
            source_key = "PENDIENTES" if position == UNASSIGNED_POSITION_NUMBER else _folder_name(position)
            if source_key in used_source_keys:
                source_key = f"{source_key}_{uuid.uuid5(uuid.NAMESPACE_URL, position).hex[:8]}"
            revisions = []
            revision = 1
            created_count += 1
        used_source_keys.add(source_key)

        reuse_previous_package = bool(previous and not changed and not occurrence_state_changed)
        if reuse_previous_package:
            relative_folder = str(previous["folder"])
        else:
            folder_name = (
                f"rev-{revision:04d}-state-{import_id[:8]}"
                if occurrence_state_changed
                else f"rev-{revision:04d}"
            )
            package_dir = desktop_folder / "revisions" / source_key / folder_name
            _write_revision_package(
                package_dir=package_dir,
                shared_source=shared_source,
                project_name=name,
                model=model,
                prepared=prepared,
                generated_at=generated_at,
                revision=revision,
            )
            relative_folder = package_dir.relative_to(desktop_folder).as_posix()
            revision_record = {
                "revision": revision,
                "folder": relative_folder,
                "geometryFingerprint": prepared["geometryFingerprint"],
                "importedAt": generated_at,
                "sourceFile": safe_source_name,
                "sourceSha256": source_hash,
                "occurrenceCount": prepared["occurrenceCount"],
                "partCount": prepared["partCount"],
                "changeType": (
                    "REVISED"
                    if changed
                    else "UNCHANGED_STATE"
                    if occurrence_state_changed
                    else "CREATED"
                ),
            }
            replaced = False
            for revision_index, record in enumerate(revisions):
                if int(record["revision"]) == revision:
                    revisions[revision_index] = revision_record
                    replaced = True
                    break
            if not replaced:
                revisions.append(revision_record)
        revisions.sort(key=lambda item: int(item["revision"]))

        entries.append(
            {
                "sourceKey": source_key,
                "positionNumber": position,
                "numberingStatus": prepared["numberingStatus"],
                "drawingNumber": prepared["drawingNumber"],
                "folder": relative_folder,
                "occurrenceCount": prepared["occurrenceCount"],
                "partCount": prepared["partCount"],
                "geometryFingerprint": prepared["geometryFingerprint"],
                "revision": revision,
                "revisionCount": len(revisions),
                "revisions": revisions,
            }
        )
        if index == 1 or index % 25 == 0 or index == len(prepared_groups):
            print(f"[{index}/{len(prepared_groups)}] revision packages written")

    active_positions = {entry["positionNumber"] for entry in entries}
    retired_entries = [
        entry
        for position, entry in history_by_position.items()
        if position not in active_positions
    ]
    retired_count = len(
        [entry for entry in previous_active if entry.get("positionNumber") not in active_positions]
    )
    import_summary = {
        "id": import_id,
        "sourceFile": safe_source_name,
        "sourceSha256": source_hash,
        "importedAt": generated_at,
        "createdPositions": created_count,
        "revisedPositions": revised_count,
        "unchangedPositions": unchanged_count,
        "retiredPositions": retired_count,
    }
    imports = [
        item
        for item in previous_index.get("imports", [])
        if item.get("sourceSha256") != source_hash
    ]
    imports.append(import_summary)
    _write_json(
        index_path,
        {
            "schemaVersion": "1.0.0",
            "producer": "IFC_DESKTOP_INDEX",
            "generatedAt": generated_at,
            "project": {"name": name, "folder": project_folder.name},
            "source": {
                "file": safe_source_name,
                "sha256": source_hash,
                "importId": import_id,
                "sharedForDesktop": True,
            },
            "sourceGeometryAudit": source_audit,
            "assemblyOccurrenceCount": sum(
                int(item["occurrenceCount"]) for item in entries
            ),
            "assemblyPositionCount": len(entries),
            "unassignedOccurrenceCount": unassigned_occurrence_count,
            "lastImport": import_summary,
            "imports": imports,
            "assemblies": entries,
            "retiredAssemblies": retired_entries,
        },
    )
    if unassigned_occurrence_count:
        print(f"Pending without business Positionsnummer: {unassigned_occurrence_count}")
    print(
        "Source geometry: "
        f"bolts={source_audit['mechanicalFastenerCount']}, "
        f"physical={source_audit['physicalFastenerCount']}, "
        f"hole-proxies={source_audit['fabricationHoleCount']}, "
        f"marks={source_audit['fabricationMarkCount']}, "
        f"unknown={source_audit['unknownMechanicalFastenerCount']}, "
        f"welds={source_audit['weldCount']} (option={source_audit['weldExportOption']}), "
        f"openings={source_audit['openingElementCount']}, "
        f"voids={source_audit['voidRelationshipCount']}"
    )
    if source_audit["weldExportOption"] == "OFF":
        print("WARNING: weld geometry was disabled in the IFC export.")
    if (
        source_audit["mechanicalFastenerCount"]
        and not source_audit["openingElementCount"]
        and not source_audit["voidRelationshipCount"]
    ):
        print("WARNING: bolts exist but no IFC opening/void entities were exported for holes.")
    print(
        "Revisions: "
        f"new={created_count}, changed={revised_count}, "
        f"unchanged={unchanged_count}, retired={retired_count}"
    )
    return index_path


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("ifc", type=Path)
    result.add_argument("--output", type=Path, default=Path("artifacts"))
    result.add_argument("--project-name")
    result.add_argument("--overwrite", action="store_true")
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        index_path = build_desktop_index(
            arguments.ifc,
            arguments.output,
            arguments.project_name,
            arguments.overwrite,
        )
    except (FileNotFoundError, FileExistsError, ValueError, RuntimeError) as exc:
        print(f"ERROR: {exc}")
        return 2
    print(f"Desktop index: {index_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
