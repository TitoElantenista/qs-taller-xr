"""Generate a compact glTF binary for one IFC assembly occurrence."""

from __future__ import annotations

import json
import math
import re
import struct
import threading
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import ifcopenshell
import ifcopenshell.geom
import ifcopenshell.util.element
import ifcopenshell.util.placement
import ifcopenshell.util.unit
import manifold3d
import numpy as np

from .fasteners import (
    FastenerCategory,
    FastenerClassification,
    classify_mechanical_fastener,
)


GEOMETRY_CACHE_VERSION = "quality-v9-stable-holes"
MESHER_LINEAR_DEFLECTION_METRES = 0.00025
MESHER_ANGULAR_DEFLECTION_RADIANS = 0.15
BOOLEAN_NORMAL_SHARP_ANGLE_DEGREES = 35.0
CONNECTION_MESHER_DEFLECTION_METRES = 0.002
FASTENER_CONNECTION_TOLERANCE_METRES = 0.006
PART_POSITION_PATTERN = re.compile(
    r"^\s*(?P<drawing>\d{6})\s*[./]\s*(?P<part>\d+)(?:\s*\(\?\))?\s*$"
)

MATERIAL_COLORS = {
    "IfcMember": (0.16, 0.42, 0.58, 1.0),
    "IfcBeam": (0.16, 0.42, 0.58, 1.0),
    "IfcPlate": (0.23, 0.51, 0.65, 1.0),
    "IfcMechanicalFastener": (0.86, 0.48, 0.16, 1.0),
    "IfcFastener": (0.92, 0.56, 0.18, 1.0),
    "ExternalMechanicalFastener": (0.73, 0.28, 0.92, 1.0),
    "FabricationMark": (0.22, 0.78, 0.92, 1.0),
    "UnknownMechanicalFastener": (0.66, 0.69, 0.71, 1.0),
    "IfcDiscreteAccessory": (0.48, 0.57, 0.62, 1.0),
}
DEFAULT_COLOR = (0.28, 0.53, 0.64, 1.0)


@dataclass
class PartGeometry:
    global_id: str
    ifc_class: str
    name: str
    vertices: np.ndarray
    faces: np.ndarray
    normals: np.ndarray | None
    metadata: dict[str, str]


@dataclass
class FastenerSpatialIndex:
    lower: np.ndarray
    upper: np.ndarray
    global_ids: list[str]
    item_indices: list[int]
    item_counts: dict[str, int]
    owners: dict[str, dict[str, str]]


@dataclass
class HoleCutSource:
    entity: Any
    classification: FastenerClassification
    item_indices: list[int] | None = None


@dataclass
class HoleCutter:
    solid: manifold3d.Manifold
    lower: np.ndarray
    upper: np.ndarray
    source_kind: str
    source_id: str


_FASTENER_INDEX_CACHE: OrderedDict[tuple[Any, ...], FastenerSpatialIndex] = OrderedDict()
_FASTENER_INDEX_LOCK = threading.RLock()
_FASTENER_INDEX_CACHE_LIMIT = 4


def _property_sets(entity: Any) -> dict[str, Any]:
    # Keep both property sets and IFC base quantities. Tekla export templates may
    # publish weight/area either as a custom property or as IfcElementQuantity.
    return ifcopenshell.util.element.get_psets(entity, psets_only=False)


def _find_property_value(properties: dict[str, Any], names: tuple[str, ...]) -> Any | None:
    """Find a value respecting the preference order declared in ``names``."""

    for wanted_name in names:
        wanted = wanted_name.casefold()
        for values in properties.values():
            if not isinstance(values, dict):
                continue
            for name, value in values.items():
                if name.casefold() == wanted and value is not None:
                    if not isinstance(value, str) or value.strip():
                        return value
    return None


def _find_property(properties: dict[str, Any], names: tuple[str, ...]) -> str | None:
    value = _find_property_value(properties, names)
    text = str(value).strip() if value is not None else ""
    return text or None


def _measure_number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
    else:
        text = str(value).strip().replace("\u00a0", " ")
        match = re.search(r"[-+]?\d+(?:[.,]\d+)?(?:[eE][-+]?\d+)?", text)
        if not match:
            return None
        number = float(match.group(0).replace(",", "."))
    return number if math.isfinite(number) else None


def _normalised_measure(value: Any, entity: Any, kind: str) -> float | None:
    """Convert an IFC/custom quantity to kg or m² when its unit is known."""

    number = _measure_number(value)
    if number is None:
        return None
    unit_text = re.sub(r"\s+", "", str(value).casefold())
    if kind == "weight":
        if "tonne" in unit_text or unit_text.endswith("ton") or unit_text.endswith("t"):
            return number * 1_000.0
        if "lb" in unit_text:
            return number * 0.45359237
        if unit_text.endswith("g") and not unit_text.endswith("kg"):
            return number / 1_000.0
        if "kg" in unit_text:
            return number
        # IFC defines the SI mass base as gram. The project commonly declares
        # KILO+GRAM, so divide the calculated SI scale by 1000 to obtain kg.
        scale = ifcopenshell.util.unit.calculate_unit_scale(entity.file, "MASSUNIT")
        return number * scale / 1_000.0
    if "mm2" in unit_text or "mm²" in unit_text:
        return number * 1e-6
    if "cm2" in unit_text or "cm²" in unit_text:
        return number * 1e-4
    if "ft2" in unit_text or "ft²" in unit_text or "sqft" in unit_text:
        return number * 0.09290304
    if "m2" in unit_text or "m²" in unit_text:
        return number
    scale = ifcopenshell.util.unit.calculate_unit_scale(entity.file, "AREAUNIT")
    return number * scale


def _same_attribute(left: Any, right: Any) -> bool:
    return bool(left and right and str(left).strip().casefold() == str(right).strip().casefold())


def _split_part_position(value: str | None) -> tuple[str, str] | None:
    if not value:
        return None
    match = PART_POSITION_PATTERN.fullmatch(value)
    if not match:
        return None
    return match.group("drawing"), match.group("part")


def _part_metadata(member: Any, assembly_position: str | None) -> dict[str, str]:
    properties = _property_sets(member)
    part_position = _find_property(
        properties,
        ("Part position number", "PART_POS", "Part mark", "PART_MARK"),
    )
    # Tekla plates sometimes export a GUID-like Tag. It is only a safe fallback
    # when it actually follows the business position format.
    tag = str(getattr(member, "Tag", None) or "").strip()
    if not _split_part_position(part_position) and _split_part_position(tag):
        part_position = tag
    split_position = _split_part_position(part_position)

    member_assembly_position = _find_property(
        properties,
        ("Assembly position number", "ASSEMBLY_POS", "Assembly mark", "CAST_UNIT_POS"),
    )
    materials = sorted(
        {
            str(material.Name).strip()
            for material in ifcopenshell.util.element.get_materials(member)
            if getattr(material, "Name", None) and str(material.Name).strip()
        }
    )
    related_type = ifcopenshell.util.element.get_type(member)
    profile = _find_property(properties, ("PROFILE", "Profile", "Profile name"))
    object_type = getattr(member, "ObjectType", None)
    type_name = getattr(related_type, "Name", None) if related_type else None
    if _same_attribute(profile, object_type):
        object_type = None
    if _same_attribute(profile, type_name):
        type_name = None
    weight = _find_property_value(
        properties,
        (
            "NetWeight", "Net weight", "NET_WEIGHT", "WEIGHT_NET",
            "Weight", "WEIGHT", "Mass", "MASS",
            "TotalWeight", "Total weight", "TOTAL_WEIGHT",
            "GrossWeight", "Gross weight", "GROSS_WEIGHT", "WEIGHT_GROSS",
            "Gewicht", "Nettogewicht", "Bruttogewicht",
        ),
    )
    surface_area = _find_property_value(
        properties,
        (
            "NetSurfaceArea", "Net surface area", "NET_SURFACE_AREA",
            "SurfaceArea", "Surface area", "SURFACE_AREA",
            "TotalSurfaceArea", "Total surface area", "TOTAL_SURFACE_AREA",
            "OuterSurfaceArea", "Outer surface area", "OUTER_SURFACE_AREA",
            "GrossSurfaceArea", "Gross surface area", "GROSS_SURFACE_AREA",
            "NetArea", "Net area", "NET_AREA",
            "Area", "AREA", "PaintArea", "Paint area", "PAINT_AREA",
            "GrossArea", "Gross area", "GROSS_AREA",
            "Oberfläche", "Oberflaeche", "OBERFLAECHE", "Fläche", "Flaeche",
        ),
    )
    values: dict[str, Any] = {
        "partPositionNumber": part_position if split_position else None,
        "partDrawingNumber": split_position[0] if split_position else None,
        "partNumber": split_position[1] if split_position else None,
        "assemblyPositionNumber": member_assembly_position or assembly_position,
        "name": getattr(member, "Name", None),
        "description": getattr(member, "Description", None),
        "objectType": object_type,
        "typeName": type_name,
        "profile": profile,
        "material": _find_property(properties, ("MATERIAL", "Material"))
        or (", ".join(materials) if materials else None),
        "weightKg": _normalised_measure(weight, member, "weight") if weight is not None else None,
        "surfaceAreaM2": (
            _normalised_measure(surface_area, member, "area")
            if surface_area is not None else None
        ),
        "phase": _find_property(properties, ("PHASE", "Phase")),
        "comment": _find_property(properties, ("Comment", "COMMENT")),
        "classAttribute": _find_property(properties, ("CLASS_ATTR", "Class")),
    }
    return {
        key: str(value).strip()
        for key, value in values.items()
        if value is not None and str(value).strip()
    }


def _assembly_members(assembly: Any) -> list[Any]:
    members: dict[str, Any] = {}
    for relation in getattr(assembly, "IsDecomposedBy", ()):
        if not relation.is_a("IfcRelAggregates"):
            continue
        for member in relation.RelatedObjects:
            global_id = getattr(member, "GlobalId", None)
            if global_id:
                members[global_id] = member
    return [members[key] for key in sorted(members)]


def _assembly_position(assembly: Any) -> str | None:
    position = _find_property(
        _property_sets(assembly),
        ("Assembly position number", "ASSEMBLY_POS", "Assembly mark", "CAST_UNIT_POS"),
    )
    if position:
        return position
    tag = str(getattr(assembly, "Tag", None) or "").strip()
    return tag or None


def _fastener_owner_records(model: Any) -> dict[str, dict[str, str]]:
    owners: dict[str, dict[str, str]] = {}
    for assembly in model.by_type("IfcElementAssembly"):
        tag = str(getattr(assembly, "Tag", None) or "").strip()
        owner = {
            "ownerAssemblySourceGlobalId": str(assembly.GlobalId),
            # Tekla writes the assembly position to Tag in the supported exports.
            # Avoid expanding every property set in a multi-thousand assembly IFC.
            "ownerAssemblyPositionNumber": tag,
            "ownerAssemblyName": str(getattr(assembly, "Name", None) or ""),
        }
        for member in _assembly_members(assembly):
            if (
                member.is_a("IfcMechanicalFastener")
                and classify_mechanical_fastener(member).category
                == FastenerCategory.PHYSICAL
            ):
                owners[member.GlobalId] = owner
    return owners


def _component_bounds(
    vertices: np.ndarray,
    faces: np.ndarray,
) -> list[tuple[np.ndarray, np.ndarray]]:
    """Return one AABB per disconnected solid in a bolt-group mesh."""

    if not len(vertices):
        return []
    flat_faces = np.asarray(faces, dtype=np.int64).reshape((-1,))
    flat_faces = flat_faces[: len(flat_faces) // 3 * 3]
    triangles = flat_faces.reshape((-1, 3))
    triangles = triangles[
        ((triangles >= 0) & (triangles < len(vertices))).all(axis=1)
    ]
    if not len(triangles):
        return [(vertices.min(axis=0), vertices.max(axis=0))]

    edges = np.concatenate(
        (
            triangles[:, (0, 1)],
            triangles[:, (0, 2)],
            triangles[:, (1, 2)],
        ),
        axis=0,
    )
    labels = np.arange(len(vertices), dtype=np.int64)
    for _ in range(32):
        previous = labels.copy()
        minimum = np.minimum(labels[edges[:, 0]], labels[edges[:, 1]])
        np.minimum.at(labels, edges[:, 0], minimum)
        np.minimum.at(labels, edges[:, 1], minimum)
        labels = labels[labels]
        if np.array_equal(labels, previous):
            break

    used_vertices = np.unique(triangles)
    roots = labels[used_vertices]
    unique_roots = np.unique(roots)
    if not len(unique_roots):
        return [(vertices.min(axis=0), vertices.max(axis=0))]
    return [
        (
            vertices[used_vertices[roots == root]].min(axis=0),
            vertices[used_vertices[roots == root]].max(axis=0),
        )
        for root in unique_roots
    ]


def _fastener_index_key(ifc_path: Path | None, model: Any) -> tuple[Any, ...]:
    if ifc_path is not None:
        path = ifc_path.resolve()
        try:
            stat = path.stat()
            return (str(path), stat.st_size, stat.st_mtime_ns)
        except OSError:
            return (str(path),)
    return ("model", id(model))


def _build_fastener_spatial_index(model: Any) -> FastenerSpatialIndex:
    settings = ifcopenshell.geom.settings()
    settings.set(settings.WELD_VERTICES, True)
    settings.set("mesher-linear-deflection", CONNECTION_MESHER_DEFLECTION_METRES)
    settings.set("mesher-angular-deflection", MESHER_ANGULAR_DEFLECTION_RADIANS)
    lower: list[np.ndarray] = []
    upper: list[np.ndarray] = []
    global_ids: list[str] = []
    item_indices: list[int] = []
    item_counts: dict[str, int] = {}
    fasteners = [
        item
        for item in model.by_type("IfcMechanicalFastener")
        if classify_mechanical_fastener(item).category == FastenerCategory.PHYSICAL
    ]
    unit_scale = float(ifcopenshell.util.unit.calculate_unit_scale(model))
    mapped_vertices: dict[int, np.ndarray] = {}
    fallback_settings = ifcopenshell.geom.settings()
    fallback_settings.set(fallback_settings.USE_WORLD_COORDS, True)
    fallback_settings.set(fallback_settings.WELD_VERTICES, True)
    fallback_settings.set(
        "mesher-linear-deflection",
        CONNECTION_MESHER_DEFLECTION_METRES,
    )
    for fastener in fasteners:
        try:
            product_from_local = ifcopenshell.util.placement.get_local_placement(
                fastener.ObjectPlacement
            )
            product_from_local[:3, 3] *= unit_scale
            mapped_items = [
                item
                for representation in fastener.Representation.Representations
                for item in representation.Items
                if item.is_a("IfcMappedItem")
            ]
            all_items_are_mapped = len(mapped_items) == sum(
                len(representation.Items)
                for representation in fastener.Representation.Representations
            )
            if not mapped_items or not all_items_are_mapped:
                raise ValueError("Fastener representation is not fully mapped")
            item_counts[str(fastener.GlobalId)] = len(mapped_items)
            for item_index, item in enumerate(mapped_items):
                mapped_representation = item.MappingSource.MappedRepresentation
                vertices = mapped_vertices.get(mapped_representation.id())
                if vertices is None:
                    geometry = ifcopenshell.geom.create_shape(
                        settings,
                        mapped_representation,
                    )
                    vertices = np.asarray(
                        geometry.verts,
                        dtype=np.float64,
                    ).reshape((-1, 3))
                    mapped_vertices[mapped_representation.id()] = vertices
                local_from_map = (
                    ifcopenshell.util.placement.get_mappeditem_transformation(item)
                )
                local_from_map[:3, 3] *= unit_scale
                world_from_map = product_from_local @ local_from_map
                world_vertices = (
                    world_from_map[:3, :3] @ vertices.T
                ).T + world_from_map[:3, 3]
                lower.append(world_vertices.min(axis=0))
                upper.append(world_vertices.max(axis=0))
                global_ids.append(str(fastener.GlobalId))
                item_indices.append(item_index)
        except Exception:
            try:
                shape = ifcopenshell.geom.create_shape(fallback_settings, fastener)
                vertices = np.asarray(
                    shape.geometry.verts,
                    dtype=np.float64,
                ).reshape((-1, 3))
                lower.append(vertices.min(axis=0))
                upper.append(vertices.max(axis=0))
                global_ids.append(str(fastener.GlobalId))
                item_indices.append(-1)
                item_counts[str(fastener.GlobalId)] = 1
            except Exception:
                continue
    return FastenerSpatialIndex(
        lower=np.asarray(lower, dtype=np.float64).reshape((-1, 3)),
        upper=np.asarray(upper, dtype=np.float64).reshape((-1, 3)),
        global_ids=global_ids,
        item_indices=item_indices,
        item_counts=item_counts,
        owners=_fastener_owner_records(model),
    )


def _fastener_spatial_index(
    model: Any,
    ifc_path: Path | None,
) -> FastenerSpatialIndex:
    key = _fastener_index_key(ifc_path, model)
    with _FASTENER_INDEX_LOCK:
        cached = _FASTENER_INDEX_CACHE.get(key)
        if cached is not None:
            _FASTENER_INDEX_CACHE.move_to_end(key)
            return cached
        index = _build_fastener_spatial_index(model)
        _FASTENER_INDEX_CACHE[key] = index
        while len(_FASTENER_INDEX_CACHE) > _FASTENER_INDEX_CACHE_LIMIT:
            _FASTENER_INDEX_CACHE.popitem(last=False)
        return index


def _connection_geometry(
    model: Any,
    assembly: Any,
) -> list[PartGeometry]:
    settings = ifcopenshell.geom.settings()
    settings.set(settings.USE_WORLD_COORDS, True)
    settings.set(settings.WELD_VERTICES, False)
    settings.set("mesher-linear-deflection", CONNECTION_MESHER_DEFLECTION_METRES)
    settings.set("mesher-angular-deflection", MESHER_ANGULAR_DEFLECTION_RADIANS)
    result: list[PartGeometry] = []
    for member in _assembly_members(assembly):
        if member.is_a("IfcFastener"):
            continue
        try:
            shape = ifcopenshell.geom.create_shape(settings, member)
            vertices = np.asarray(shape.geometry.verts, dtype=np.float64).reshape((-1, 3))
            faces = np.asarray(shape.geometry.faces, dtype=np.int64).reshape((-1, 3))
        except Exception:
            continue
        if len(vertices) and len(faces):
            result.append(
                PartGeometry(
                    global_id=str(member.GlobalId),
                    ifc_class=str(member.is_a()),
                    name=str(getattr(member, "Name", None) or member.GlobalId),
                    vertices=vertices,
                    faces=faces,
                    normals=None,
                    metadata={},
                )
            )
    return result


def find_external_fasteners(
    model: Any,
    assembly: Any,
    ifc_path: Path | None = None,
    *,
    target_geometry: list[PartGeometry] | None = None,
) -> list[dict[str, Any]]:
    """Find bolt groups owned elsewhere that geometrically connect this assembly."""

    direct_members = _assembly_members(assembly)
    direct_ids = {str(member.GlobalId) for member in direct_members}
    matches: dict[str, str] = {}
    connected_item_indices: dict[str, set[int]] = {}

    # Prefer explicit IFC connectivity whenever the exporter supplies it.
    for relation in model.by_type("IfcRelConnectsWithRealizingElements"):
        endpoint_ids = {
            str(global_id)
            for endpoint in (relation.RelatingElement, relation.RelatedElement)
            if (global_id := getattr(endpoint, "GlobalId", None))
        }
        if not endpoint_ids.intersection(direct_ids):
            continue
        for element in relation.RealizingElements:
            global_id = str(getattr(element, "GlobalId", None) or "")
            if (
                global_id
                and global_id not in direct_ids
                and element.is_a("IfcMechanicalFastener")
                and classify_mechanical_fastener(element).category
                == FastenerCategory.PHYSICAL
            ):
                matches[global_id] = "IFC_RELATIONSHIP"

    geometry = target_geometry if target_geometry is not None else _connection_geometry(
        model, assembly
    )
    geometry = [
        part
        for part in geometry
        if part.ifc_class not in {"IfcMechanicalFastener", "IfcFastener"}
    ]
    index = _fastener_spatial_index(model, ifc_path)
    if len(index.lower):
        for part in geometry:
            faces = np.asarray(part.faces, dtype=np.int64).reshape((-1, 3))
            faces = faces[((faces >= 0) & (faces < len(part.vertices))).all(axis=1)]
            if not len(faces):
                continue
            triangles = part.vertices[faces]
            triangle_lower = triangles.min(axis=1)
            triangle_upper = triangles.max(axis=1)
            part_lower = triangle_lower.min(axis=0)
            part_upper = triangle_upper.max(axis=0)
            candidate_rows = np.flatnonzero(
                (
                    (index.upper + FASTENER_CONNECTION_TOLERANCE_METRES >= part_lower)
                    & (index.lower - FASTENER_CONNECTION_TOLERANCE_METRES <= part_upper)
                ).all(axis=1)
            )
            for row in candidate_rows:
                global_id = index.global_ids[int(row)]
                if global_id in direct_ids:
                    continue
                component_lower = (
                    index.lower[int(row)] - FASTENER_CONNECTION_TOLERANCE_METRES
                )
                component_upper = (
                    index.upper[int(row)] + FASTENER_CONNECTION_TOLERANCE_METRES
                )
                if (
                    (triangle_upper >= component_lower)
                    & (triangle_lower <= component_upper)
                ).all(axis=1).any():
                    matches.setdefault(global_id, "GEOMETRIC")
                    item_index = index.item_indices[int(row)]
                    if item_index >= 0:
                        connected_item_indices.setdefault(global_id, set()).add(item_index)

    records = []
    for global_id, detection in sorted(matches.items()):
        owner = index.owners.get(global_id, {})
        if owner.get("ownerAssemblySourceGlobalId") == str(assembly.GlobalId):
            continue
        record: dict[str, Any] = {
            "sourceGlobalId": global_id,
            "connectionDetection": detection,
            **{key: value for key, value in owner.items() if value},
        }
        item_indices = sorted(connected_item_indices.get(global_id, set()))
        if item_indices:
            record["connectedItemIndices"] = item_indices
            record["fastenerItemCount"] = index.item_counts.get(
                global_id,
                len(item_indices),
            )
        records.append(record)
    return records


def _assembly_elements(model: Any, assembly: Any) -> list[Any]:
    """Return direct members plus welds connecting members of the assembly."""

    direct_members = _assembly_members(assembly)
    result = {member.GlobalId: member for member in direct_members}
    member_ids = set(result)
    if not member_ids:
        return []

    weld_ids = {
        element.GlobalId
        for element in model.by_type("IfcFastener")
        if not element.is_a("IfcMechanicalFastener")
        and str(getattr(element, "Name", "") or "").casefold() in {
            "weld",
            "schweißnaht",
            "schweissnaht",
        }
    }
    if not weld_ids:
        return direct_members

    for relation in model.by_type("IfcRelConnectsWithRealizingElements"):
        relating = getattr(relation.RelatingElement, "GlobalId", None)
        related = getattr(relation.RelatedElement, "GlobalId", None)
        if relating not in member_ids or related not in member_ids:
            continue
        for element in relation.RealizingElements:
            global_id = getattr(element, "GlobalId", None)
            if global_id in weld_ids:
                result[global_id] = element
    return [result[key] for key in sorted(result)]


def _normal_vectors(vertices: np.ndarray, faces: np.ndarray) -> np.ndarray:
    normals = np.zeros_like(vertices, dtype=np.float32)
    first = vertices[faces[:, 0]]
    second = vertices[faces[:, 1]]
    third = vertices[faces[:, 2]]
    face_normals = np.cross(second - first, third - first)
    lengths = np.linalg.norm(face_normals, axis=1)
    valid = lengths > 1e-12
    face_normals[valid] /= lengths[valid, None]
    for corner in range(3):
        np.add.at(normals, faces[:, corner], face_normals)
    lengths = np.linalg.norm(normals, axis=1)
    valid = lengths > 1e-12
    normals[valid] /= lengths[valid, None]
    return normals.astype("<f4", copy=False)


def _pad4(data: bytes, padding: bytes = b"\0") -> bytes:
    missing = (-len(data)) % 4
    return data + padding * missing


class GlbDocument:
    def __init__(self) -> None:
        self.binary = bytearray()
        self.buffer_views: list[dict[str, Any]] = []
        self.accessors: list[dict[str, Any]] = []

    def add_array(
        self,
        values: np.ndarray,
        *,
        component_type: int,
        value_type: str,
        target: int,
        include_bounds: bool = False,
    ) -> int:
        while len(self.binary) % 4:
            self.binary.append(0)
        offset = len(self.binary)
        payload = values.tobytes(order="C")
        self.binary.extend(payload)
        view_index = len(self.buffer_views)
        self.buffer_views.append(
            {
                "buffer": 0,
                "byteOffset": offset,
                "byteLength": len(payload),
                "target": target,
            }
        )
        count = int(values.shape[0])
        accessor: dict[str, Any] = {
            "bufferView": view_index,
            "byteOffset": 0,
            "componentType": component_type,
            "count": count,
            "type": value_type,
        }
        if include_bounds:
            accessor["min"] = [float(value) for value in values.min(axis=0)]
            accessor["max"] = [float(value) for value in values.max(axis=0)]
        accessor_index = len(self.accessors)
        self.accessors.append(accessor)
        return accessor_index


def _selected_fastener_geometry(
    settings: Any,
    fastener: Any,
    item_indices: list[int],
) -> tuple[np.ndarray, np.ndarray, np.ndarray | None]:
    """Tessellate only selected physical bolts from a Tekla bolt-group entity."""

    mapped_items = [
        item
        for representation in fastener.Representation.Representations
        for item in representation.Items
        if item.is_a("IfcMappedItem")
    ]
    selected = sorted({index for index in item_indices if 0 <= index < len(mapped_items)})
    if not selected:
        raise ValueError("el grupo externo no contiene índices de perno válidos")

    unit_scale = float(ifcopenshell.util.unit.calculate_unit_scale(fastener.file))
    world_from_local = ifcopenshell.util.placement.get_local_placement(
        fastener.ObjectPlacement
    )
    world_from_local[:3, 3] *= unit_scale
    vertex_blocks: list[np.ndarray] = []
    face_blocks: list[np.ndarray] = []
    normal_blocks: list[np.ndarray] = []
    normals_complete = True
    vertex_offset = 0
    for item_index in selected:
        geometry = ifcopenshell.geom.create_shape(settings, mapped_items[item_index])
        local_vertices = np.asarray(
            geometry.verts,
            dtype=np.float64,
        ).reshape((-1, 3))
        faces = np.asarray(geometry.faces, dtype=np.uint32).reshape((-1, 3))
        vertices = (
            world_from_local[:3, :3] @ local_vertices.T
        ).T + world_from_local[:3, 3]
        source_normals = np.asarray(geometry.normals, dtype=np.float64)
        if source_normals.size == local_vertices.size:
            local_normals = source_normals.reshape((-1, 3))
            normals = (world_from_local[:3, :3] @ local_normals.T).T
            lengths = np.linalg.norm(normals, axis=1)
            valid = lengths > 1e-12
            normals[valid] /= lengths[valid, None]
            normal_blocks.append(normals)
        else:
            normals_complete = False
        vertex_blocks.append(vertices)
        face_blocks.append(faces + vertex_offset)
        vertex_offset += len(vertices)
    return (
        np.concatenate(vertex_blocks, axis=0),
        np.concatenate(face_blocks, axis=0),
        np.concatenate(normal_blocks, axis=0) if normals_complete else None,
    )


def _manifold_from_mesh(vertices: np.ndarray, faces: np.ndarray) -> manifold3d.Manifold:
    mesh = manifold3d.Mesh(
        np.ascontiguousarray(vertices, dtype=np.float32),
        np.ascontiguousarray(faces, dtype=np.uint32),
        tolerance=1e-6,
    )
    mesh.merge()
    return manifold3d.Manifold(mesh)


def _render_mesh_from_manifold(
    solid: manifold3d.Manifold,
) -> tuple[np.ndarray, np.ndarray, np.ndarray | None]:
    """Return a renderer mesh with hard fabrication edges and smooth hole walls."""

    shaded = solid.calculate_normals(
        0,
        BOOLEAN_NORMAL_SHARP_ANGLE_DEGREES,
    )
    mesh = shaded.to_mesh()
    properties = np.asarray(mesh.vert_properties, dtype=np.float64)
    normals = properties[:, 3:6] if properties.shape[1] >= 6 else None
    return (
        properties[:, :3],
        np.asarray(mesh.tri_verts, dtype=np.uint32),
        normals,
    )


def _validated_external_item_indices(
    global_id: str,
    selected: list[int],
    applied_hole_ids: set[str],
) -> list[int]:
    prefix = f"{global_id}:"
    if selected:
        return [
            item_index
            for item_index in selected
            if f"{prefix}{item_index}" in applied_hole_ids
        ]
    return sorted(
        int(source_id.removeprefix(prefix))
        for source_id in applied_hole_ids
        if source_id.startswith(prefix)
        and source_id.removeprefix(prefix).isdigit()
    )


def _cutter_bounds(solid: manifold3d.Manifold) -> tuple[np.ndarray, np.ndarray]:
    bounds = solid.bounding_box()
    return (
        np.asarray(bounds[:3], dtype=np.float64),
        np.asarray(bounds[3:], dtype=np.float64),
    )


def _capsule_cross_section(width: float, depth: float) -> manifold3d.CrossSection:
    radius = min(width, depth) / 2.0
    if math.isclose(width, depth, rel_tol=0.0, abs_tol=1e-6):
        return manifold3d.CrossSection.circle(radius, circular_segments=32)
    if width > depth:
        offset = (width - depth) / 2.0
        middle = manifold3d.CrossSection.square(
            (width - depth, depth),
            center=True,
        )
        return (
            middle
            + manifold3d.CrossSection.circle(radius, circular_segments=32).translate(
                (-offset, 0.0)
            )
            + manifold3d.CrossSection.circle(radius, circular_segments=32).translate(
                (offset, 0.0)
            )
        )
    offset = (depth - width) / 2.0
    middle = manifold3d.CrossSection.square(
        (width, depth - width),
        center=True,
    )
    return (
        middle
        + manifold3d.CrossSection.circle(radius, circular_segments=32).translate(
            (0.0, -offset)
        )
        + manifold3d.CrossSection.circle(radius, circular_segments=32).translate(
            (0.0, offset)
        )
    )


def _explicit_hole_cutters(
    settings: Any,
    source: HoleCutSource,
) -> list[HoleCutter]:
    if source.item_indices:
        vertices, faces, _ = _selected_fastener_geometry(
            settings,
            source.entity,
            source.item_indices,
        )
    else:
        shape = ifcopenshell.geom.create_shape(settings, source.entity)
        vertices = np.asarray(shape.geometry.verts, dtype=np.float64).reshape((-1, 3))
        faces = np.asarray(shape.geometry.faces, dtype=np.uint32).reshape((-1, 3))
    solid = _manifold_from_mesh(vertices, faces)
    if solid.status() != manifold3d.Error.NoError:
        raise ValueError(f"cortador no-manifold: {solid.status().name}")
    result = []
    for index, component in enumerate(solid.decompose()):
        lower, upper = _cutter_bounds(component)
        result.append(
            HoleCutter(
                solid=component,
                lower=lower,
                upper=upper,
                source_kind="EXPLICIT_IFC_HOLE",
                source_id=f"{source.entity.GlobalId}:{index}",
            )
        )
    return result


def _physical_fastener_cutters(
    settings: Any,
    source: HoleCutSource,
) -> list[HoleCutter]:
    classification = source.classification
    if not classification.cut_from_physical_fastener:
        return []
    diameter_x = classification.hole_diameter_x
    diameter_y = classification.hole_diameter_y
    if not diameter_x or not diameter_y:
        return []

    entity = source.entity
    unit_scale = float(ifcopenshell.util.unit.calculate_unit_scale(entity.file))
    width = diameter_x * unit_scale
    depth = diameter_y * unit_scale
    if min(width, depth) < 0.001 or max(width, depth) > 1.0:
        raise ValueError(
            f"dimensiones de agujero fuera de rango: {diameter_x:g} × {diameter_y:g}"
        )
    mapped_items = [
        item
        for representation in entity.Representation.Representations
        for item in representation.Items
        if item.is_a("IfcMappedItem")
    ]
    selected = (
        sorted({index for index in source.item_indices if 0 <= index < len(mapped_items)})
        if source.item_indices
        else list(range(len(mapped_items)))
    )
    if not selected:
        return []

    world_from_local = np.asarray(
        ifcopenshell.util.placement.get_local_placement(entity.ObjectPlacement),
        dtype=np.float64,
    )
    world_from_local[:3, 3] *= unit_scale
    cross_section = _capsule_cross_section(width, depth)
    result = []
    for item_index in selected:
        item = mapped_items[item_index]
        local_from_map = np.asarray(
            ifcopenshell.util.placement.get_mappeditem_transformation(item),
            dtype=np.float64,
        )
        local_from_map[:3, 3] *= unit_scale
        world_from_map = world_from_local @ local_from_map
        geometry = ifcopenshell.geom.create_shape(
            settings,
            item.MappingSource.MappedRepresentation,
        )
        local_vertices = np.asarray(geometry.verts, dtype=np.float64).reshape((-1, 3))
        lower = local_vertices.min(axis=0)
        upper = local_vertices.max(axis=0)
        margin = 0.002
        start = np.asarray(
            [
                (lower[0] + upper[0]) / 2.0,
                lower[1] - margin,
                (lower[2] + upper[2]) / 2.0,
            ],
            dtype=np.float64,
        )
        height = float(upper[1] - lower[1] + 2.0 * margin)
        if height <= 0.001:
            continue
        rotation = world_from_map[:3, :3]
        translation = world_from_map[:3, :3] @ start + world_from_map[:3, 3]
        # Manifold extrudes along local Z. The negative third IFC basis keeps
        # the cutter transform right-handed while mapping Z to the bolt axis.
        transform = np.column_stack(
            (
                rotation[:, 0],
                -rotation[:, 2],
                rotation[:, 1],
                translation,
            )
        )
        solid = cross_section.extrude(height).transform(transform)
        if solid.status() != manifold3d.Error.NoError:
            raise ValueError(f"cortador no-manifold: {solid.status().name}")
        cutter_lower, cutter_upper = _cutter_bounds(solid)
        result.append(
            HoleCutter(
                solid=solid,
                lower=cutter_lower,
                upper=cutter_upper,
                source_kind="PHYSICAL_FASTENER_HOLE",
                source_id=f"{entity.GlobalId}:{item_index}",
            )
        )
    return result


def _apply_fabrication_holes(
    parts: list[PartGeometry],
    sources: list[HoleCutSource],
    settings: Any,
    warnings: list[str],
) -> tuple[dict[str, int], set[str]]:
    cutters: list[HoleCutter] = []
    for source in sources:
        try:
            if source.classification.category == FastenerCategory.HOLE:
                cutters.extend(_explicit_hole_cutters(settings, source))
            elif source.classification.category == FastenerCategory.PHYSICAL:
                cutters.extend(_physical_fastener_cutters(settings, source))
        except Exception as exc:
            warnings.append(f"{source.entity.GlobalId}: no se pudo crear el agujero ({exc})")

    applied_ids: set[str] = set()
    cut_part_count = 0
    failure_count = 0
    for part in parts:
        if part.ifc_class in {"IfcMechanicalFastener", "IfcFastener"}:
            continue
        part_lower = part.vertices.min(axis=0)
        part_upper = part.vertices.max(axis=0)
        broad_candidates = [
            cutter
            for cutter in cutters
            if (cutter.upper >= part_lower - 1e-6).all()
            and (cutter.lower <= part_upper + 1e-6).all()
        ]
        if not broad_candidates:
            continue
        try:
            original = _manifold_from_mesh(part.vertices, part.faces)
            if original.status() != manifold3d.Error.NoError:
                raise ValueError(f"malla no-manifold: {original.status().name}")
            # Bounding boxes are only a broad phase. A bolt can be inside the box of a
            # triangular gusset while remaining completely outside its real contour.
            # Only cutters with non-zero solid intersection are allowed to alter the
            # part or to validate an externally-owned fastener.
            candidates: list[HoleCutter] = []
            intersection_epsilon = max(
                1e-12,
                abs(original.volume()) * 1e-10,
            )
            for cutter in broad_candidates:
                intersection = manifold3d.Manifold.batch_boolean(
                    [original, cutter.solid],
                    manifold3d.OpType.Intersect,
                )
                if (
                    intersection.status() == manifold3d.Error.NoError
                    and not intersection.is_empty()
                    and intersection.volume() > intersection_epsilon
                ):
                    candidates.append(cutter)
            if not candidates:
                continue
            cut = manifold3d.Manifold.batch_boolean(
                [original, *(item.solid for item in candidates)],
                manifold3d.OpType.Subtract,
            )
            if cut.status() != manifold3d.Error.NoError or cut.is_empty():
                raise ValueError(f"resultado booleano inválido: {cut.status().name}")
            removed_volume = original.volume() - cut.volume()
            if removed_volume <= max(1e-12, abs(original.volume()) * 1e-10):
                continue
            # Manifold shares vertices by default. Averaging normals across the
            # 90-degree rim made flat plates look torn and pyramidal in Three.js.
            # Split sharp edges while keeping the cylindrical wall visually smooth.
            part.vertices, part.faces, part.normals = _render_mesh_from_manifold(cut)
            part.metadata["generatedFabricationHoles"] = "true"
            cut_part_count += 1
            applied_ids.update(item.source_id for item in candidates)
        except Exception as exc:
            failure_count += 1
            warnings.append(f"{part.global_id}: no se pudieron aplicar los agujeros ({exc})")
    return (
        {
            "holeCutterCount": len(cutters),
            "generatedHoleCount": len(applied_ids),
            "cutPartCount": cut_part_count,
            "holeCutFailureCount": failure_count,
        },
        applied_ids,
    )


def _collect_geometry(
    model: Any,
    assembly: Any,
    ifc_path: Path | None,
    external_fasteners: list[dict[str, Any]] | None,
) -> tuple[list[PartGeometry], list[str], int, dict[str, int]]:
    settings = ifcopenshell.geom.settings()
    settings.set(settings.USE_WORLD_COORDS, True)
    # Keeping split vertices preserves the normals produced by OpenCascade at
    # hard edges. Welding discards those normals and makes steel plates look wavy.
    settings.set(settings.WELD_VERTICES, False)
    settings.set("mesher-linear-deflection", MESHER_LINEAR_DEFLECTION_METRES)
    settings.set("mesher-angular-deflection", MESHER_ANGULAR_DEFLECTION_RADIANS)
    settings.set("reorient-shells", True)
    result: list[PartGeometry] = []
    warnings: list[str] = []
    hole_sources: list[HoleCutSource] = []
    assembly_position = _assembly_position(assembly)

    def append_member(
        member: Any,
        extra_metadata: dict[str, str] | None = None,
        item_indices: list[int] | None = None,
    ) -> None:
        try:
            if item_indices is None:
                shape = ifcopenshell.geom.create_shape(settings, member)
                vertices = np.asarray(
                    shape.geometry.verts,
                    dtype=np.float64,
                ).reshape((-1, 3))
                faces = np.asarray(
                    shape.geometry.faces,
                    dtype=np.uint32,
                ).reshape((-1, 3))
                source_normals = np.asarray(shape.geometry.normals, dtype=np.float64)
                normals = (
                    source_normals.reshape((-1, 3))
                    if source_normals.size == vertices.size
                    else None
                )
            else:
                vertices, faces, normals = _selected_fastener_geometry(
                    settings,
                    member,
                    item_indices,
                )
            if len(vertices) == 0 or len(faces) == 0:
                warnings.append(f"{member.GlobalId}: geometría vacía")
                return
            metadata = _part_metadata(member, assembly_position)
            if member.is_a("IfcMechanicalFastener"):
                classified = classify_mechanical_fastener(member)
                metadata.update(classified.metadata())
                if classified.category == FastenerCategory.PHYSICAL:
                    metadata["fastenerScope"] = "ASSEMBLY"
            if extra_metadata:
                metadata.update(extra_metadata)
            result.append(
                PartGeometry(
                    global_id=member.GlobalId,
                    ifc_class=member.is_a(),
                    name=getattr(member, "Name", None) or member.GlobalId,
                    vertices=vertices,
                    faces=faces,
                    normals=normals,
                    metadata=metadata,
                )
            )
        except Exception as exc:  # IfcOpenShell raises several backend-specific types.
            warnings.append(f"{member.GlobalId}: {exc}")

    for member in _assembly_elements(model, assembly):
        if member.is_a("IfcMechanicalFastener"):
            classified = classify_mechanical_fastener(member)
            if classified.category == FastenerCategory.HOLE:
                hole_sources.append(HoleCutSource(member, classified))
                continue
            if classified.category == FastenerCategory.UNKNOWN:
                warnings.append(
                    f"{member.GlobalId}: IfcMechanicalFastener desconocido omitido"
                )
                continue
            if classified.category == FastenerCategory.PHYSICAL:
                hole_sources.append(HoleCutSource(member, classified))
            append_member(member, classified.metadata())
            continue
        append_member(member)

    if external_fasteners is None:
        external_fasteners = find_external_fasteners(
            model,
            assembly,
            ifc_path,
            target_geometry=result,
        )
    direct_ids = {part.global_id for part in result}
    pending_external: list[
        tuple[Any, FastenerClassification, dict[str, str], list[int], Any]
    ] = []
    for record in external_fasteners:
        global_id = str(record.get("sourceGlobalId") or "")
        if not global_id or global_id in direct_ids:
            continue
        member = model.by_guid(global_id)
        if member is None or not member.is_a("IfcMechanicalFastener"):
            warnings.append(f"{global_id}: perno externo no encontrado en el IFC")
            continue
        classified = classify_mechanical_fastener(member)
        if classified.category in {
            FastenerCategory.MARK,
            FastenerCategory.UNKNOWN,
        }:
            if classified.category == FastenerCategory.UNKNOWN:
                warnings.append(
                    f"{global_id}: IfcMechanicalFastener externo desconocido omitido"
                )
            continue
        metadata = {
            "externalFastener": "true",
            "fastenerScope": "EXTERNAL_CONNECTED",
            "connectedAssemblySourceGlobalId": str(assembly.GlobalId),
        }
        metadata.update(classified.metadata())
        for key in (
            "connectionDetection",
            "ownerAssemblySourceGlobalId",
            "ownerAssemblyPositionNumber",
            "ownerAssemblyName",
        ):
            if value := record.get(key):
                metadata[key] = str(value)
        item_indices = [
            int(value)
            for value in record.get("connectedItemIndices", [])
            if isinstance(value, int) and not isinstance(value, bool)
        ]
        if classified.category in {FastenerCategory.HOLE, FastenerCategory.PHYSICAL}:
            hole_sources.append(
                HoleCutSource(member, classified, item_indices or None)
            )
        if classified.category == FastenerCategory.HOLE:
            continue
        pending_external.append(
            (
                member,
                classified,
                metadata,
                item_indices,
                record.get("fastenerItemCount"),
            )
        )
    hole_summary, applied_hole_ids = _apply_fabrication_holes(
        result,
        hole_sources,
        settings,
        warnings,
    )
    external_count = 0
    for member, classified, metadata, item_indices, item_count in pending_external:
        rendered_indices = item_indices
        if (
            metadata.get("connectionDetection") == "GEOMETRIC"
            and classified.cut_from_physical_fastener
            and classified.hole_diameter_x
            and classified.hole_diameter_y
        ):
            rendered_indices = _validated_external_item_indices(
                str(member.GlobalId),
                item_indices,
                applied_hole_ids,
            )
            if not rendered_indices:
                warnings.append(
                    f"{member.GlobalId}: perno externo geométrico descartado "
                    "porque no atraviesa ninguna pieza de la Baugruppe"
                )
                continue
        if rendered_indices:
            metadata["connectedFastenerItemCount"] = str(len(rendered_indices))
        if item_count:
            metadata["fastenerItemCount"] = str(item_count)
        before = len(result)
        append_member(member, metadata, rendered_indices or None)
        if len(result) > before:
            external_count += 1
    return result, warnings, external_count, hole_summary


def _stored_external_fasteners(
    package_dir: Path,
    occurrence_global_id: str,
) -> list[dict[str, Any]] | None:
    path = package_dir / "occurrences.json"
    try:
        occurrences = json.loads(path.read_text(encoding="utf-8")).get("occurrences") or []
    except (OSError, json.JSONDecodeError, TypeError):
        return None
    for occurrence in occurrences:
        if str(occurrence.get("sourceGlobalId") or "") != occurrence_global_id:
            continue
        if "externalFasteners" not in occurrence:
            return None
        records = occurrence.get("externalFasteners") or []
        return [
            {str(key): value for key, value in record.items() if value is not None}
            for record in records
            if isinstance(record, dict)
        ]
    return None


def build_occurrence_glb(
    package_dir: Path,
    occurrence_global_id: str,
    output_path: Path,
    ifc_path: Path | None = None,
    model: Any | None = None,
) -> dict[str, Any]:
    """Build one centered, metre-based, Y-up GLB and return build metadata."""

    ifc_path = ifc_path or package_dir / "assembly.ifc"
    model = model if model is not None else ifcopenshell.open(ifc_path)
    assembly = model.by_guid(occurrence_global_id)
    if assembly is None or not assembly.is_a("IfcElementAssembly"):
        raise KeyError(occurrence_global_id)

    external_fasteners = _stored_external_fasteners(
        package_dir,
        occurrence_global_id,
    )
    parts, warnings, external_fastener_count, hole_summary = _collect_geometry(
        model,
        assembly,
        ifc_path,
        external_fasteners,
    )
    if not parts:
        raise ValueError(f"Assembly {occurrence_global_id} has no renderable geometry")

    all_vertices = np.concatenate([part.vertices for part in parts], axis=0)
    center = (all_vertices.min(axis=0) + all_vertices.max(axis=0)) / 2.0

    document = GlbDocument()
    nodes: list[dict[str, Any]] = []
    meshes: list[dict[str, Any]] = []
    materials: list[dict[str, Any]] = []
    material_by_class: dict[str, int] = {}

    for part in parts:
        local = part.vertices - center
        # IFC is right-handed Z-up; glTF is right-handed Y-up.
        vertices = np.column_stack((local[:, 0], local[:, 2], -local[:, 1])).astype("<f4")
        faces = part.faces.astype("<u4", copy=False)
        if part.normals is not None:
            normals = np.column_stack(
                (part.normals[:, 0], part.normals[:, 2], -part.normals[:, 1])
            ).astype("<f4")
            lengths = np.linalg.norm(normals, axis=1)
            valid = lengths > 1e-12
            normals[valid] /= lengths[valid, None]
            if not np.all(valid):
                normals[~valid] = _normal_vectors(vertices, faces)[~valid]
        else:
            normals = _normal_vectors(vertices, faces)

        position_accessor = document.add_array(
            vertices,
            component_type=5126,
            value_type="VEC3",
            target=34962,
            include_bounds=True,
        )
        normal_accessor = document.add_array(
            normals,
            component_type=5126,
            value_type="VEC3",
            target=34962,
        )
        index_accessor = document.add_array(
            faces.reshape((-1,)),
            component_type=5125,
            value_type="SCALAR",
            target=34963,
        )

        material_key = (
            "ExternalMechanicalFastener"
            if part.metadata.get("externalFastener") == "true"
            else "FabricationMark"
            if part.metadata.get("fastenerCategory") == FastenerCategory.MARK.value
            else "UnknownMechanicalFastener"
            if part.metadata.get("fastenerCategory") == FastenerCategory.UNKNOWN.value
            else part.ifc_class
        )
        material_index = material_by_class.get(material_key)
        if material_index is None:
            material_index = len(materials)
            material_by_class[material_key] = material_index
            materials.append(
                {
                    "name": material_key,
                    "pbrMetallicRoughness": {
                        "baseColorFactor": list(
                            MATERIAL_COLORS.get(material_key, DEFAULT_COLOR)
                        ),
                        "metallicFactor": 0.18,
                        "roughnessFactor": 0.66,
                    },
                    "doubleSided": True,
                }
            )

        mesh_index = len(meshes)
        extras = {
            "sourceGlobalId": part.global_id,
            "ifcClass": part.ifc_class,
            **part.metadata,
        }
        meshes.append(
            {
                "name": part.name,
                "primitives": [
                    {
                        "attributes": {
                            "POSITION": position_accessor,
                            "NORMAL": normal_accessor,
                        },
                        "indices": index_accessor,
                        "material": material_index,
                    }
                ],
                "extras": extras,
            }
        )
        nodes.append({"name": part.name, "mesh": mesh_index, "extras": extras})

    gltf = {
        "asset": {"version": "2.0", "generator": "QS Desktop Viewer 0.1.0"},
        "scene": 0,
        "scenes": [{"name": str(getattr(assembly, "Name", "Assembly")), "nodes": list(range(len(nodes)))}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": materials,
        "buffers": [{"byteLength": len(document.binary)}],
        "bufferViews": document.buffer_views,
        "accessors": document.accessors,
        "extras": {
            "assemblySourceGlobalId": occurrence_global_id,
            "units": "metres",
            "sourceCenterIfcMetres": [float(value) for value in center],
            "geometryQuality": {
                "cacheVersion": GEOMETRY_CACHE_VERSION,
                "linearDeflectionMetres": MESHER_LINEAR_DEFLECTION_METRES,
                "angularDeflectionRadians": MESHER_ANGULAR_DEFLECTION_RADIANS,
                "sourceNormals": True,
            },
            "externalFastenerCount": external_fastener_count,
            "fabricationHoles": hole_summary,
        },
    }
    json_chunk = _pad4(
        json.dumps(gltf, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        b" ",
    )
    binary_chunk = _pad4(bytes(document.binary))
    total_length = 12 + 8 + len(json_chunk) + 8 + len(binary_chunk)
    payload = b"".join(
        (
            struct.pack("<4sII", b"glTF", 2, total_length),
            struct.pack("<I4s", len(json_chunk), b"JSON"),
            json_chunk,
            struct.pack("<I4s", len(binary_chunk), b"BIN\0"),
            binary_chunk,
        )
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_bytes(payload)
    temporary.replace(output_path)
    return {
        "partCount": len(parts),
        "weldCount": sum(part.ifc_class == "IfcFastener" for part in parts),
        "externalFastenerCount": external_fastener_count,
        **hole_summary,
        "triangleCount": sum(len(part.faces) for part in parts),
        "warnings": warnings,
        "byteLength": len(payload),
    }


def ensure_occurrence_glb(
    package_dir: Path,
    occurrence_global_id: str,
    output_path: Path,
    ifc_path: Path | None = None,
    model_cache: dict[Path, Any] | None = None,
) -> Path:
    ifc_path = ifc_path or package_dir / "assembly.ifc"
    if occurrence_glb_is_current(package_dir, output_path, ifc_path):
        return output_path
    model = None
    if model_cache is not None:
        key = ifc_path.resolve()
        model = model_cache.get(key)
        if model is None:
            model = ifcopenshell.open(key)
            model_cache[key] = model
    build_occurrence_glb(
        package_dir,
        occurrence_global_id,
        output_path,
        ifc_path,
        model=model,
    )
    return output_path


def occurrence_glb_is_current(
    package_dir: Path,
    output_path: Path,
    ifc_path: Path | None = None,
) -> bool:
    ifc_path = ifc_path or package_dir / "assembly.ifc"
    sources = [ifc_path, package_dir / "occurrences.json"]
    return output_path.is_file() and output_path.stat().st_mtime >= max(
        path.stat().st_mtime for path in sources
    )
