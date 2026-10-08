"""Classify Tekla IFC fastener proxies by their fabrication meaning."""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import ifcopenshell.util.element


class FastenerCategory(StrEnum):
    PHYSICAL = "PHYSICAL_FASTENER"
    HOLE = "FABRICATION_HOLE"
    MARK = "FABRICATION_MARK"
    UNKNOWN = "UNKNOWN_MECHANICAL_FASTENER"


@dataclass(frozen=True)
class FastenerClassification:
    category: FastenerCategory
    standard: str | None
    catalog_name: str | None
    hole_diameter_x: float | None
    hole_diameter_y: float | None
    reason: str
    cut_from_physical_fastener: bool = False

    def metadata(self) -> dict[str, str]:
        values: dict[str, Any] = {
            "fastenerCategory": self.category.value,
            "fastenerStandard": self.standard,
            "fastenerCatalogName": self.catalog_name,
            "holeDiameterXmm": self.hole_diameter_x,
            "holeDiameterYmm": self.hole_diameter_y,
            "fastenerClassificationReason": self.reason,
        }
        return {
            key: str(value).strip()
            for key, value in values.items()
            if value is not None and str(value).strip()
        }


_PHYSICAL_STANDARDS = {
    "4014",
    "4017",
    "7990",
    "DIN4014",
    "DIN4017",
    "DIN7990",
    "EN143994",
    "FISCHER",
    "HILTI",
    "ISO4014",
    "ISO4017",
    "NELSON",
}
_HOLE_STANDARDS = {"LOCH", "ZINCHOLES"}
_MARK_STANDARDS = {"DSTV"}
_MARK_PATTERN = re.compile(
    r"(?:PULVER|POWDER|BRENN|SCRIBE|ANREISS|MARKIER|SIGNIER)",
    re.IGNORECASE,
)
_PHYSICAL_NAME_PATTERN = re.compile(
    r"^(?:M\d+(?:[.,]\d+)?(?:\b|[*X-])|FAZ\b|HAS\b|HST\b|NELSON\b)",
    re.IGNORECASE,
)


def _clean(value: Any) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text or None


def _normalise_code(value: str | None) -> str:
    return re.sub(r"[^A-Z0-9]+", "", (value or "").upper())


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        result = float(value)
    else:
        match = re.search(r"[-+]?\d+(?:[.,]\d+)?", str(value))
        if not match:
            return None
        result = float(match.group(0).replace(",", "."))
    return result if math.isfinite(result) and result > 0 else None


def _property(
    properties: dict[str, Any],
    names: tuple[str, ...],
) -> Any | None:
    wanted = [name.casefold() for name in names]
    for wanted_name in wanted:
        for values in properties.values():
            if not isinstance(values, dict):
                continue
            for name, value in values.items():
                if name.casefold() == wanted_name and value is not None:
                    return value
    return None


def classify_mechanical_fastener(
    entity: Any,
    properties: dict[str, Any] | None = None,
) -> FastenerClassification:
    """Classify one IFC mechanical fastener without trusting its IFC class alone."""

    properties = properties or ifcopenshell.util.element.get_psets(
        entity,
        psets_only=True,
    )
    standard = _clean(_property(properties, ("BOLT_STANDARD", "Bolt standard")))
    catalog_name = _clean(
        _property(properties, ("NAME", "NAME_FULL", "Bolt name"))
    )
    material = _clean(_property(properties, ("MATERIAL", "GRADE")))
    standard_code = _normalise_code(standard)
    name_code = _normalise_code(catalog_name)
    material_code = _normalise_code(material)
    hole_x = _number(
        _property(
            properties,
            ("HOLE.DIAMETER_X", "Bolt hole diameter X", "HOLE.DIAMETER"),
        )
    )
    hole_y = _number(
        _property(
            properties,
            ("HOLE.DIAMETER_Y", "Bolt hole diameter Y", "HOLE.DIAMETER"),
        )
    )

    if standard_code in _HOLE_STANDARDS or material_code in _HOLE_STANDARDS:
        return FastenerClassification(
            FastenerCategory.HOLE,
            standard,
            catalog_name,
            hole_x,
            hole_y,
            "EXPLICIT_HOLE_STANDARD",
        )
    if (
        standard_code in _MARK_STANDARDS
        and _MARK_PATTERN.search(catalog_name or "")
    ) or _MARK_PATTERN.search(catalog_name or ""):
        return FastenerClassification(
            FastenerCategory.MARK,
            standard,
            catalog_name,
            hole_x,
            hole_y,
            "FABRICATION_MARK_NAME",
        )
    if standard_code in _PHYSICAL_STANDARDS or _PHYSICAL_NAME_PATTERN.search(
        catalog_name or ""
    ):
        return FastenerClassification(
            FastenerCategory.PHYSICAL,
            standard,
            catalog_name,
            hole_x,
            hole_y,
            "PHYSICAL_FASTENER_STANDARD",
            cut_from_physical_fastener=standard_code != "NELSON",
        )
    return FastenerClassification(
        FastenerCategory.UNKNOWN,
        standard,
        catalog_name,
        hole_x,
        hole_y,
        "UNRECOGNISED_MECHANICAL_FASTENER",
    )


def audit_mechanical_fasteners(model: Any) -> dict[str, Any]:
    categories: Counter[str] = Counter()
    standards: Counter[str] = Counter()
    round_holes = 0
    slotted_holes = 0
    unknown_samples: list[dict[str, str]] = []
    for entity in model.by_type("IfcMechanicalFastener"):
        classified = classify_mechanical_fastener(entity)
        categories[classified.category.value] += 1
        standards[classified.standard or "<vacío>"] += 1
        if classified.category in {
            FastenerCategory.PHYSICAL,
            FastenerCategory.HOLE,
        }:
            x_value = classified.hole_diameter_x
            y_value = classified.hole_diameter_y
            if x_value and y_value:
                if math.isclose(x_value, y_value, rel_tol=0.0, abs_tol=1e-3):
                    round_holes += 1
                else:
                    slotted_holes += 1
        if classified.category == FastenerCategory.UNKNOWN and len(unknown_samples) < 20:
            unknown_samples.append(
                {
                    "sourceGlobalId": str(entity.GlobalId),
                    "standard": classified.standard or "",
                    "name": classified.catalog_name or "",
                }
            )
    return {
        "classificationVersion": "1.0.0",
        "physicalFastenerCount": categories[FastenerCategory.PHYSICAL.value],
        "fabricationHoleCount": categories[FastenerCategory.HOLE.value],
        "fabricationMarkCount": categories[FastenerCategory.MARK.value],
        "unknownMechanicalFastenerCount": categories[FastenerCategory.UNKNOWN.value],
        "roundHoleDefinitionCount": round_holes,
        "slottedHoleDefinitionCount": slotted_holes,
        "standards": dict(sorted(standards.items())),
        "unknownSamples": unknown_samples,
    }
