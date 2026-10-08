#!/usr/bin/env python3
"""Build the deterministic demo packages bundled only with the debug Android app.

Three test models share one demo project, so the app can be tried without any file of one's own:

* HEB 140 x 300 mm      the original calibration profile (unchanged geometry and ids);
* IPE 270 x 200 mm      a short profile offcut that is easy to have on a desk;
* HEMNES 504.135.26     white stained frame / light brown top, three drawers and three open bays,
                        148x47x57 cm. Modelled from the public product reference; internal sizes
                        and small details are approximate, not manufacturer CAD.

Every package goes through the same QSRG builder as the desktop exporter, so the bundled models
exercise the normal mobile package path.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import math
import struct
import sys
import uuid
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "apps" / "desktop-viewer" / "src"))

from qs_desktop_viewer.registration_model import (  # noqa: E402
    build_registration_model,
    inspect_registration_model,
)

ASSETS = ROOT / "apps" / "mobile-android" / "app" / "src" / "debug" / "assets" / "bundled"
PROJECT_ID = "d6655dc6-fc60-5b13-a966-99af0c630ce0"
PROJECT_NAME = "Demo · Testmodelle"
NAMESPACE = uuid.UUID("5d0c6a3e-7b57-5a7e-9d53-6b0a2f1c9e11")
CREATED_AT = "2026-08-11T00:00:00Z"
FILLET_SEGMENTS = 6
THUMBNAIL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)
PACKAGE_ENTRIES = (
    "manifest.json",
    "assembly.glb",
    "registration_model.bin",
    "parts.json",
    "thumbnail.png",
    "checksums.json",
)


@dataclass
class Part:
    guid: str
    position_number: str
    kind: str
    name: str
    ifc_class: str
    colour: tuple[float, float, float]
    positions: np.ndarray
    normals: np.ndarray
    properties: dict[str, Any] = field(default_factory=dict)

    @property
    def node_name(self) -> str:
        return f"part:{self.guid}"


@dataclass
class Sample:
    asset: str
    package_id: str
    assembly_id: str
    position_number: str
    title: str
    parts: list[Part]
    expected_size: tuple[float, float, float]


def _json_bytes(document: dict[str, Any]) -> bytes:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"


# ── Geometry ────────────────────────────────────────────────────────────────────────────────────


def _cross_2d(first: np.ndarray, second: np.ndarray, third: np.ndarray) -> float:
    return float(
        (second[0] - first[0]) * (third[1] - first[1]) - (second[1] - first[1]) * (third[0] - first[0])
    )


def _point_in_triangle(point: np.ndarray, first: np.ndarray, second: np.ndarray, third: np.ndarray) -> bool:
    signs = [_cross_2d(a, b, point) for a, b in ((first, second), (second, third), (third, first))]
    return all(value >= -1e-12 for value in signs) or all(value <= 1e-12 for value in signs)


def _triangulate_polygon(points: np.ndarray) -> list[tuple[int, int, int]]:
    """Ear clipping of a simple counter clockwise polygon."""
    remaining = list(range(len(points)))
    triangles: list[tuple[int, int, int]] = []
    guard = 0
    while len(remaining) > 3:
        guard += 1
        if guard > 10_000:
            raise ValueError("Polygon triangulation did not converge")
        for offset, current in enumerate(remaining):
            previous = remaining[offset - 1]
            following = remaining[(offset + 1) % len(remaining)]
            if _cross_2d(points[previous], points[current], points[following]) <= 1e-14:
                continue
            if any(
                _point_in_triangle(points[other], points[previous], points[current], points[following])
                for other in remaining
                if other not in (previous, current, following)
            ):
                continue
            triangles.append((previous, current, following))
            remaining.pop(offset)
            break
        else:
            raise ValueError("Polygon has no ear; is it counter clockwise and simple?")
    triangles.append((remaining[0], remaining[1], remaining[2]))
    return triangles


def _i_section(height: float, width: float, web: float, flange: float, radius: float) -> np.ndarray:
    """Counter clockwise outline of a rolled I profile with root fillets, centred on the origin."""
    half_width, half_height, half_web = width / 2.0, height / 2.0, web / 2.0
    lower, upper = -half_height + flange, half_height - flange

    def arc(cx: float, cy: float, start: float, end: float) -> list[tuple[float, float]]:
        angles = np.linspace(start, end, FILLET_SEGMENTS + 1)[1:]
        return [
            (cx + radius * math.cos(math.radians(float(a))), cy + radius * math.sin(math.radians(float(a))))
            for a in angles
        ]

    points: list[tuple[float, float]] = [
        (-half_width, -half_height),
        (half_width, -half_height),
        (half_width, lower),
        (half_web + radius, lower),
    ]
    points += arc(half_web + radius, lower + radius, -90.0, -180.0)
    points.append((half_web, upper - radius))
    points += arc(half_web + radius, upper - radius, 180.0, 90.0)
    points += [(half_width, upper), (half_width, half_height), (-half_width, half_height), (-half_width, upper),
               (-half_web - radius, upper)]
    points += arc(-half_web - radius, upper - radius, 90.0, 0.0)
    points.append((-half_web, lower + radius))
    points += arc(-half_web - radius, lower + radius, 0.0, -90.0)
    points.append((-half_width, lower))
    return np.asarray(points, dtype=np.float64)


class Soup:
    def __init__(self) -> None:
        self.positions: list[np.ndarray] = []
        self.normals: list[np.ndarray] = []

    def triangle(self, first: np.ndarray, second: np.ndarray, third: np.ndarray) -> None:
        normal = np.cross(second - first, third - first)
        length = float(np.linalg.norm(normal))
        if length <= 1e-14:
            raise ValueError("Degenerate triangle")
        normal /= length
        self.positions += [first, second, third]
        self.normals += [normal, normal, normal]

    def arrays(self) -> tuple[np.ndarray, np.ndarray]:
        return np.asarray(self.positions, dtype="<f4"), np.asarray(self.normals, dtype="<f4")


def _extrusion(section: np.ndarray, length: float) -> tuple[np.ndarray, np.ndarray]:
    soup = Soup()
    half = length / 2.0
    for first, second, third in _triangulate_polygon(section):
        soup.triangle(*[np.asarray((*section[i], -half)) for i in (first, third, second)])
        soup.triangle(*[np.asarray((*section[i], half)) for i in (first, second, third)])
    for index in range(len(section)):
        following = (index + 1) % len(section)
        a = np.asarray((*section[index], -half))
        b = np.asarray((*section[following], -half))
        c = np.asarray((*section[following], half))
        d = np.asarray((*section[index], half))
        soup.triangle(a, b, c)
        soup.triangle(a, c, d)
    return soup.arrays()


def _box(lower: tuple[float, float, float], upper: tuple[float, float, float]) -> tuple[np.ndarray, np.ndarray]:
    (x0, y0, z0), (x1, y1, z1) = lower, upper
    if not (x1 > x0 and y1 > y0 and z1 > z0):
        raise ValueError(f"Empty box {lower} {upper}")
    corner = lambda x, y, z: np.asarray((x, y, z), dtype=np.float64)  # noqa: E731
    faces = [
        (corner(x0, y0, z1), corner(x1, y0, z1), corner(x1, y1, z1), corner(x0, y1, z1)),  # +Z
        (corner(x1, y0, z0), corner(x0, y0, z0), corner(x0, y1, z0), corner(x1, y1, z0)),  # -Z
        (corner(x1, y0, z1), corner(x1, y0, z0), corner(x1, y1, z0), corner(x1, y1, z1)),  # +X
        (corner(x0, y0, z0), corner(x0, y0, z1), corner(x0, y1, z1), corner(x0, y1, z0)),  # -X
        (corner(x0, y1, z1), corner(x1, y1, z1), corner(x1, y1, z0), corner(x0, y1, z0)),  # +Y
        (corner(x0, y0, z0), corner(x1, y0, z0), corner(x1, y0, z1), corner(x0, y0, z1)),  # -Y
    ]
    soup = Soup()
    for a, b, c, d in faces:
        soup.triangle(a, b, c)
        soup.triangle(a, c, d)
    return soup.arrays()


# ── Samples ─────────────────────────────────────────────────────────────────────────────────────


def _profile_sample(
    *,
    asset: str,
    title: str,
    profile: str,
    label: str,
    drawing: str,
    height: float,
    width: float,
    web: float,
    flange: float,
    radius: float,
    length: float,
    ids: tuple[str, str, str] | None = None,
    source_id: str,
) -> Sample:
    package_id, assembly_id, part_id = ids or tuple(
        str(uuid.uuid5(NAMESPACE, f"{asset}:{role}")) for role in ("package", "assembly", "part")
    )
    positions, normals = _extrusion(_i_section(height, width, web, flange, radius), length)
    part = Part(
        guid=part_id,
        position_number=f"{drawing}.501",
        kind="MAIN_PART",
        name=title,
        ifc_class="IfcBeam",
        colour=(0.16, 0.48, 0.68),
        positions=positions,
        normals=normals,
        properties={
            "ifcClass": "IfcBeam",
            "name": title,
            "profile": profile,
            "material": "S355",
            "lengthMm": round(length * 1000),
            "webThicknessMm": web * 1000,
            "flangeThicknessMm": flange * 1000,
            "rootRadiusMm": radius * 1000,
            "sourceGlobalId": source_id,
        },
    )
    return Sample(asset, package_id, assembly_id, f"{label}.1", title, [part], (width, height, length))


def _tv_bench_sample() -> Sample:
    # https://www.ikea.com/de/de/p/hemnes-tv-bank-weiss-gebeizt-hellbraun-50413526/
    # IKEA's current dimension drawing (0978642_pe814195) gives 1477 x 465 x 567 mm.
    # Interior boards/rails are approximate: the front upper opening is visibly smaller
    # than a drawer front. Do not confuse the drawing's 170 mm INTERNAL DRAWER height
    # with the open shelf above it.
    asset = "tv-bank-148x47x57.qsxr"
    width, depth, height = 1.477, 0.465, 0.567
    hx, hz = width / 2.0, depth / 2.0
    top_thickness, post, inset, clearance, board = 0.022, 0.045, 0.012, 0.113, 0.018
    top_bottom = height - top_thickness
    left, right = -hx + inset, hx - inset
    back, front = -hz + inset, hz - 0.030
    inner_left, inner_right = left + post, right - post
    upper_rail_height, lower_rail_height, upper_opening = 0.040, 0.040, 0.125
    shelf_bottom = top_bottom - upper_rail_height - upper_opening - board
    drawer_bottom = clearance + lower_rail_height

    wood = (0.62, 0.40, 0.22)
    white = (0.88, 0.86, 0.81)
    definitions: list[tuple[str, str, tuple[float, float, float], tuple[float, float, float], tuple[float, float, float]]] = [
        ("Deckplatte", "MAIN_PART", (-hx, top_bottom, -hz), (hx, height, hz), wood),
        ("Bein vorne links", "PART", (left, 0.0, front - post), (left + post, top_bottom, front), white),
        ("Bein vorne rechts", "PART", (right - post, 0.0, front - post), (right, top_bottom, front), white),
        ("Bein hinten links", "PART", (left, 0.0, back), (left + post, top_bottom, back + post), white),
        ("Bein hinten rechts", "PART", (right - post, 0.0, back), (right, top_bottom, back + post), white),
        ("Seitenwand links", "PLATE", (left + 0.006, clearance, back + post), (left + 0.006 + board, top_bottom, front - post), white),
        ("Seitenwand rechts", "PLATE", (right - 0.006 - board, clearance, back + post), (right - 0.006, top_bottom, front - post), white),
        ("Rückwand", "PLATE", (inner_left, clearance, back + 0.012), (inner_right, top_bottom, back + 0.017), white),
        ("Boden", "PLATE", (inner_left, clearance, back + 0.017), (inner_right, clearance + board, front - 0.022), white),
        ("Zwischenboden", "PLATE", (inner_left, shelf_bottom, back + 0.017), (inner_right, shelf_bottom + board, front - 0.004), white),
        ("Frontleiste oben", "PLATE", (inner_left, top_bottom - upper_rail_height, front - board), (inner_right, top_bottom, front), white),
        ("Frontleiste unten", "PLATE", (inner_left, clearance, front - board), (inner_right, drawer_bottom, front), white),
    ]
    bay_width = (inner_right - inner_left - 2 * board) / 3.0
    for index in range(2):
        divider = inner_left + (index + 1) * bay_width + index * board
        definitions.append(
            (f"Trennwand {index + 1}", "PLATE", (divider, clearance + board, back + 0.017),
             (divider + board, top_bottom, front - 0.004), white)
        )
    for index, side in enumerate(("links", "Mitte", "rechts")):
        bay_left = inner_left + index * (bay_width + board)
        bay_right = bay_left + bay_width
        definitions.append(
            (f"Schubladenfront {side}", "PLATE", (bay_left + 0.003, drawer_bottom + 0.003, front - board),
             (bay_right - 0.003, shelf_bottom - 0.004, front), white)
        )
        # Closed drawers: shallow box behind the front, visible when using transparency.
        definitions.extend([
            (f"Schubladenboden {side}", "PLATE", (bay_left + 0.012, drawer_bottom + 0.010, back + 0.035),
             (bay_right - 0.012, drawer_bottom + 0.015, front - board), wood),
            (f"Schubladenrückwand {side}", "PLATE", (bay_left + 0.012, drawer_bottom + 0.015, back + 0.035),
             (bay_right - 0.012, shelf_bottom - 0.025, back + 0.047), wood),
        ])
        for wall, wall_x in (("links", bay_left + 0.012), ("rechts", bay_right - 0.024)):
            definitions.append(
                (f"Schubladenseite {side} {wall}", "PLATE", (wall_x, drawer_bottom + 0.015, back + 0.047),
                 (wall_x + 0.012, shelf_bottom - 0.025, front - board), wood)
            )

    # The model is delivered centred on its bounding box, like the profiles.
    centre_y = height / 2.0
    parts: list[Part] = []
    for number, (name, kind, lower, upper, colour) in enumerate(definitions, start=1):
        lower = (lower[0], lower[1] - centre_y, lower[2])
        upper = (upper[0], upper[1] - centre_y, upper[2])
        positions, normals = _box(lower, upper)
        parts.append(
            Part(
                guid=str(uuid.uuid5(NAMESPACE, f"{asset}:part:{name}")),
                position_number=f"900148.{500 + number}",
                kind=kind,
                name=name,
                ifc_class="IfcFurnishingElement",
                colour=colour,
                positions=positions,
                normals=normals,
                properties={
                    "ifcClass": "IfcFurnishingElement",
                    "name": name,
                    "material": "Kiefer massiv" if kind != "PART" or "Knopf" not in name else "Metall",
                    "product": "IKEA HEMNES 504.135.26 · weiß gebeizt/hellbraun",
                    "sourceUrl": "https://www.ikea.com/de/de/p/hemnes-tv-bank-weiss-gebeizt-hellbraun-50413526/",
                    "note": "Außenmaße laut Maßzeichnung 147,7 × 46,5 × 56,7 cm; offene Fächer ca. 12,5 cm hoch. Innenmaße und Details näherungsweise modelliert.",
                },
            )
        )
    # Three round black knobs, one per drawer, staying within the top's nominal depth.
    knob_y = (drawer_bottom + 0.003 + shelf_bottom - 0.004) / 2 - centre_y
    for index, side in enumerate(("links", "Mitte", "rechts")):
        centre_x = inner_left + index * (bay_width + board) + bay_width / 2
        profile = [(0.0, 0.0), (0.0, 0.006), (0.010, 0.006), (0.012, 0.012),
                   (0.017, 0.014), (0.022, 0.010), (0.024, 0.0)]
        triangles = []
        for (z0, r0), (z1, r1) in zip(profile, profile[1:]):
            for segment in range(16):
                a0, a1 = segment * math.tau / 16, (segment + 1) * math.tau / 16
                def point(z: float, r: float, a: float) -> tuple[float, float, float]:
                    return (centre_x + r * math.cos(a), knob_y + r * math.sin(a), front + z)
                a, b, c, d = point(z0, r0, a0), point(z0, r0, a1), point(z1, r1, a1), point(z1, r1, a0)
                for triangle in ((a, b, c), (a, c, d)):
                    triangle = np.asarray(triangle)
                    normal = np.cross(triangle[1] - triangle[0], triangle[2] - triangle[0])
                    if np.linalg.norm(normal) > 1e-12:
                        triangles.append(triangle)
        positions = np.asarray(triangles, dtype="<f4").reshape(-1, 3)
        face_normals = np.cross(positions[1::3] - positions[0::3], positions[2::3] - positions[0::3])
        face_normals /= np.linalg.norm(face_normals, axis=1)[:, None]
        parts.append(Part(
            guid=str(uuid.uuid5(NAMESPACE, f"{asset}:part:Knopf {side}")),
            position_number=f"900148.{600 + index}", kind="PART", name=f"Knopf {side}",
            ifc_class="IfcFurnishingElement", colour=(0.035, 0.035, 0.035), positions=positions,
            normals=np.repeat(face_normals, 3, axis=0).astype("<f4"),
            properties={"ifcClass": "IfcFurnishingElement", "name": f"Knopf {side}", "material": "Metall"},
        ))
    return Sample(
        asset=asset,
        package_id=str(uuid.uuid5(NAMESPACE, f"{asset}:package")),
        assembly_id=str(uuid.uuid5(NAMESPACE, f"{asset}:assembly")),
        position_number="TV-BANK.1",
        title="HEMNES 504.135.26 · weiß/hellbraun · 148 × 47 × 57 cm",
        parts=parts,
        expected_size=(width, height, depth),
    )


def samples() -> list[Sample]:
    return [
        _profile_sample(
            asset="heb140-300.qsxr",
            title="HEB 140 × 300 mm",
            profile="HEB 140",
            label="HEB140",
            drawing="900140",
            height=0.140, width=0.140, web=0.007, flange=0.012, radius=0.012, length=0.300,
            ids=(
                "767bc082-0a02-5d83-b00d-178dd58447eb",
                "b19a0f1f-9b55-58f7-b3b6-d90280116ec8",
                "e063aaea-508d-5bbe-a89f-c45069b7a4c4",
            ),
            source_id="BUNDLED-HEB140-300",
        ),
        # EN 10365 IPE 270: h 270, b 135, tw 6.6, tf 10.2, r 15 mm.
        _profile_sample(
            asset="ipe270-200.qsxr",
            title="IPE 270 × 200 mm",
            profile="IPE 270",
            label="IPE270",
            drawing="900270",
            height=0.270, width=0.135, web=0.0066, flange=0.0102, radius=0.015, length=0.200,
            source_id="BUNDLED-IPE270-200",
        ),
        _tv_bench_sample(),
    ]


# ── GLB and package ─────────────────────────────────────────────────────────────────────────────


def _build_glb(sample: Sample) -> bytes:
    binary = bytearray()
    views: list[dict[str, Any]] = []
    accessors: list[dict[str, Any]] = []

    def add(values: np.ndarray, component: int, kind: str, target: int, bounds: bool = False) -> int:
        binary.extend(b"\0" * (-len(binary) % 4))
        payload = values.tobytes(order="C")
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(payload), "target": target})
        binary.extend(payload)
        accessor: dict[str, Any] = {
            "bufferView": len(views) - 1, "componentType": component, "count": len(values), "type": kind,
        }
        if bounds:
            accessor["min"] = [float(v) for v in values.min(axis=0)]
            accessor["max"] = [float(v) for v in values.max(axis=0)]
        accessors.append(accessor)
        return len(accessors) - 1

    colours: list[tuple[float, float, float]] = []
    nodes, meshes = [], []
    for part in sample.parts:
        if part.colour not in colours:
            colours.append(part.colour)
        position = add(part.positions, 5126, "VEC3", 34962, bounds=True)
        normal = add(part.normals, 5126, "VEC3", 34962)
        index = add(np.arange(len(part.positions), dtype="<u2"), 5123, "SCALAR", 34963)
        extras = {"ifcClass": part.ifc_class, "name": part.name, "partPositionNumber": part.position_number}
        meshes.append(
            {
                "name": part.node_name,
                "primitives": [
                    {
                        "attributes": {"POSITION": position, "NORMAL": normal},
                        "indices": index,
                        "material": colours.index(part.colour),
                        "mode": 4,
                    }
                ],
                "extras": extras,
            }
        )
        nodes.append({"name": part.node_name, "mesh": len(meshes) - 1, "extras": extras})
    binary.extend(b"\0" * (-len(binary) % 4))

    document = {
        "asset": {"version": "2.0", "generator": "QS bundled demo samples"},
        "scene": 0,
        "scenes": [{"name": sample.title, "nodes": list(range(len(nodes)))}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": [
            {
                "pbrMetallicRoughness": {
                    "baseColorFactor": [*colour, 1.0],
                    "metallicFactor": 0.30,
                    "roughnessFactor": 0.62,
                },
                "doubleSided": False,
            }
            for colour in colours
        ],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": views,
        "accessors": accessors,
        "extras": {"units": "metres", "mobilePackage": {"schemaVersion": "2.0.0", "assemblyGuid": sample.assembly_id}},
    }
    json_chunk = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    json_chunk += b" " * (-len(json_chunk) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(binary)
    return b"".join(
        (
            struct.pack("<4sII", b"glTF", 2, total),
            struct.pack("<I4s", len(json_chunk), b"JSON"),
            json_chunk,
            struct.pack("<I4s", len(binary), b"BIN\0"),
            bytes(binary),
        )
    )


def build_package(sample: Sample) -> bytes:
    glb = _build_glb(sample)
    registration, info = build_registration_model(glb)
    size = tuple(upper - lower for lower, upper in zip(info.bounds_min, info.bounds_max))
    if not np.allclose(size, sample.expected_size, atol=1e-5):
        raise ValueError(f"{sample.asset}: unexpected size {size}, wanted {sample.expected_size}")

    manifest = {
        "schemaVersion": "2.0.0",
        "packageId": sample.package_id,
        "createdAt": CREATED_AT,
        "producer": {"name": "QS Taller XR", "version": "bundled-2"},
        "project": {"projectId": PROJECT_ID, "name": PROJECT_NAME},
        "assembly": {"guid": sample.assembly_id, "positionNumber": sample.position_number, "revision": 1},
        "coordinateSystem": {
            "handedness": "RIGHT_HANDED",
            "upAxis": "Y",
            "linearUnit": "m",
            "matrixStorage": "COLUMN_MAJOR",
            "vectorConvention": "COLUMN_VECTOR",
            "transformNotation": "PARENT_FROM_CHILD",
        },
        "files": {
            "model": "assembly.glb",
            "registrationModel": "registration_model.bin",
            "parts": "parts.json",
            "thumbnail": "thumbnail.png",
            "checksums": "checksums.json",
        },
    }
    parts = {
        "schemaVersion": "1.0.0",
        "assemblyGuid": sample.assembly_id,
        "parts": [
            {
                "guid": part.guid,
                "positionNumber": part.position_number,
                "kind": part.kind,
                "gltfNodeName": part.node_name,
                "boundsAssemblyM": {
                    "min": [float(v) for v in part.positions.min(axis=0)],
                    "max": [float(v) for v in part.positions.max(axis=0)],
                },
                "inspectable": True,
                "properties": part.properties,
            }
            for part in sample.parts
        ],
    }
    contents = {
        "manifest.json": _json_bytes(manifest),
        "assembly.glb": glb,
        "registration_model.bin": registration,
        "parts.json": _json_bytes(parts),
        "thumbnail.png": THUMBNAIL_PNG,
    }
    contents["checksums.json"] = _json_bytes(
        {
            "schemaVersion": "1.0.0",
            "algorithm": "SHA-256",
            "files": [
                {"path": name, "sha256": hashlib.sha256(payload).hexdigest(), "byteLength": len(payload)}
                for name, payload in contents.items()
            ],
        }
    )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in PACKAGE_ENTRIES:
            entry = zipfile.ZipInfo(name, date_time=(2026, 8, 11, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, contents[name])
    payload = output.getvalue()

    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if tuple(archive.namelist()) != PACKAGE_ENTRIES:
            raise ValueError(f"{sample.asset}: unexpected archive entries")
        inspected = inspect_registration_model(archive.read("registration_model.bin"))
        print(
            f"{sample.asset}: {len(payload)} bytes · {len(sample.parts)} Teile · "
            f"{inspected.point_count} Punkte · {inspected.edge_count} Kanten · "
            f"{' × '.join(f'{v * 1000:.0f}' for v in size)} mm"
        )
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail when a committed asset differs")
    arguments = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    stale = []
    for sample in samples():
        payload = build_package(sample)
        target = ASSETS / sample.asset
        if arguments.check:
            if not target.is_file() or target.read_bytes() != payload:
                stale.append(sample.asset)
        else:
            target.write_bytes(payload)
    if stale:
        raise SystemExit(f"Bundled demo assets are stale: {', '.join(stale)}")


if __name__ == "__main__":
    main()
