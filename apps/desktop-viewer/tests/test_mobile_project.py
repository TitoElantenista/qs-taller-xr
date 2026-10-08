from dataclasses import replace
import json
from pathlib import Path
import zipfile

import pytest

from qs_desktop_viewer.catalog import SourcePackage
from qs_desktop_viewer.mobile_project import (
    _build_gpu_target_export,
    build_mobile_project,
    resolve_export_packages,
)
from qs_pose_service.targets_cli import import_target_archive


class FakeCatalog:
    def __init__(self, packages: list[SourcePackage]) -> None:
        self._packages = packages

    def packages(self) -> list[SourcePackage]:
        return self._packages

    def get_revision(
        self, project_id: str, source_id: str, revision: int
    ) -> SourcePackage:
        package = next(
            item
            for item in self._packages
            if item.project_id == project_id and item.source_id == source_id
        )
        return replace(package, raw={**package.raw, "revision": revision})

    def revision(self, package: SourcePackage, revision: int) -> SourcePackage:
        return replace(package, raw={**package.raw, "revision": revision})


def source(tmp_path: Path, source_id: str, position: str, revisions: list[int]):
    return SourcePackage(
        project_id="P1",
        project_name="Proyecto",
        producer_folder="desktop",
        producer="DESKTOP",
        source_id=source_id,
        producer_dir=tmp_path,
        package_dir=tmp_path,
        raw={
            "positionNumber": position,
            "revision": max(revisions),
            "revisions": [{"revision": number} for number in revisions],
        },
    )


def test_export_scope_is_project_drawing_or_assembly(tmp_path: Path) -> None:
    catalog = FakeCatalog(
        [
            source(tmp_path, "S1", "100001.1", [1, 2]),
            source(tmp_path, "S2", "100001.2", [1]),
            source(tmp_path, "S3", "100002.1", [1, 2, 3]),
        ]
    )

    complete = resolve_export_packages(catalog, "P1", "COMPLETE", [], [])
    drawing = resolve_export_packages(catalog, "P1", "DRAWINGS", ["100001"], [])
    assembly = resolve_export_packages(catalog, "P1", "ASSEMBLIES", [], ["S3"])

    assert [(item.source_id, item.raw["revision"]) for item in complete] == [
        ("S1", 1), ("S1", 2), ("S2", 1), ("S3", 1), ("S3", 2), ("S3", 3)
    ]
    assert [(item.source_id, item.raw["revision"]) for item in drawing] == [
        ("S1", 1), ("S1", 2), ("S2", 1)
    ]
    assert [(item.source_id, item.raw["revision"]) for item in assembly] == [
        ("S3", 1), ("S3", 2), ("S3", 3)
    ]


@pytest.mark.parametrize(
    ("mode", "drawings", "assemblies", "message"),
    [
        ("DRAWINGS", [], [], "Zeichnung"),
        ("ASSEMBLIES", [], [], "Baugruppe"),
    ],
)
def test_partial_export_requires_a_selection(
    tmp_path: Path,
    mode: str,
    drawings: list[str],
    assemblies: list[str],
    message: str,
) -> None:
    catalog = FakeCatalog([source(tmp_path, "S1", "100001.1", [1])])
    with pytest.raises(ValueError, match=message):
        resolve_export_packages(catalog, "P1", mode, drawings, assemblies)


def test_project_export_skips_empty_geometry_and_finishes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "source.ifc").write_text("IFC", encoding="utf-8")
    (tmp_path / "source_manifest.json").write_text(
        json.dumps({"files": {"ifc": "source.ifc"}}), encoding="utf-8"
    )
    (tmp_path / "occurrences.json").write_text(
        json.dumps(
            {
                "occurrences": [
                    {"sourceGlobalId": "EMPTY", "name": "Empty", "memberCount": 0},
                    {"sourceGlobalId": "GOOD", "name": "Good", "memberCount": 1},
                ]
            }
        ),
        encoding="utf-8",
    )
    package = source(tmp_path, "S1", "100001.1", [1])
    catalog = FakeCatalog([package])

    monkeypatch.setattr(
        "qs_desktop_viewer.mobile_project.occurrence_glb_is_current",
        lambda *args: False,
    )
    monkeypatch.setattr(
        "qs_desktop_viewer.mobile_project._export_worker_count",
        lambda *args: 1,
    )

    def ensure_geometry(package_dir, occurrence_id, output, ifc_path, model_cache):
        if occurrence_id == "EMPTY":
            raise ValueError("Assembly EMPTY has no renderable geometry")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"glTF")
        return output

    def build_package(package, occurrence, glb_path, output):
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(
                "manifest.json",
                json.dumps({"packageId": "11111111-1111-1111-8111-111111111111"}),
            )
        return output

    monkeypatch.setattr(
        "qs_desktop_viewer.mobile_project.ensure_occurrence_glb", ensure_geometry
    )
    monkeypatch.setattr(
        "qs_desktop_viewer.mobile_project.build_mobile_package", build_package
    )
    result = build_mobile_project(
        catalog=catalog,
        artifacts_root=tmp_path,
        project_id="P1",
        mode="ASSEMBLIES",
        drawings=[],
        assemblies=["S1"],
        output_path=tmp_path / "project.qsproject",
    )
    with zipfile.ZipFile(result.project_path) as archive:
        manifest = json.loads(archive.read("project_bundle_manifest.json"))
    assert len(manifest["packages"]) == 1
    assert manifest["packages"][0]["occurrenceId"] == "GOOD"
    assert manifest["skipped"][0]["occurrenceId"] == "EMPTY"
    assert "no renderable geometry" in manifest["skipped"][0]["reason"]


def test_gpu_target_export_matches_phone_package_and_provisions_server(tmp_path: Path) -> None:
    repository_root = Path(__file__).resolve().parents[3]
    qsxr = repository_root / "apps/mobile-android/app/src/debug/assets/bundled/heb140-300.qsxr"
    with zipfile.ZipFile(qsxr) as archive:
        package_id = json.loads(archive.read("manifest.json"))["packageId"]

    output = tmp_path / "project.qsgputargets"
    target_root = tmp_path / "server-targets"
    manifest_path = target_root / "targets.json"
    count, provisioned = _build_gpu_target_export(
        inputs=[(package_id, qsxr)],
        output_path=output,
        server_target_root=target_root,
        server_target_manifest=manifest_path,
        progress_callback=None,
    )

    assert count == 1
    assert provisioned is True
    with zipfile.ZipFile(output) as archive:
        bundle = json.loads(archive.read("gpu_targets_manifest.json"))
        assert bundle["targets"][0]["targetId"] == package_id
        assert bundle["targets"][0]["bundleManifest"].startswith(
            f"targets/{package_id}/"
        )
    server_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert server_manifest["targets"][0]["targetId"] == package_id
    assert (target_root / package_id / "target-tracking.obj").is_file()

    # Repeating the same export must be idempotent and cannot duplicate the registry entry.
    second_output = tmp_path / "project-second.qsgputargets"
    _, second_provisioned = _build_gpu_target_export(
        inputs=[(package_id, qsxr)],
        output_path=second_output,
        server_target_root=target_root,
        server_target_manifest=manifest_path,
        progress_callback=None,
    )
    assert second_provisioned is True
    assert len(json.loads(manifest_path.read_text(encoding="utf-8"))["targets"]) == 1

    imported_root = tmp_path / "imported-server-targets"
    imported_manifest = imported_root / "targets.json"
    assert import_target_archive(
        archive_path=output,
        target_root=imported_root,
        manifest_path=imported_manifest,
    ) == 1
    imported = json.loads(imported_manifest.read_text(encoding="utf-8"))
    assert imported["targets"][0]["targetId"] == package_id
