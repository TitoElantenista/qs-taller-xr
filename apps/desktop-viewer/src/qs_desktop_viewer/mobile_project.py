"""Build an offline Android project for a project, drawings or assemblies."""

from __future__ import annotations

import hashlib
import json
import multiprocessing
import os
import uuid
import zipfile
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
import shutil
from typing import Any, Callable, Iterable

from .catalog import ProjectCatalog, SourcePackage, load_occurrences
from .glb import (
    GEOMETRY_CACHE_VERSION,
    ensure_occurrence_glb,
    occurrence_glb_is_current,
)
from .mobile_package import build_mobile_package
from .tracking_target import export_tracking_target


BUNDLE_SCHEMA_VERSION = "1.0.0"
BUNDLE_NAMESPACE = uuid.UUID("24bbfeaa-216f-4bf8-9223-f942cc809696")
_WORKER_MODEL_CACHE: dict[Path, Any] = {}


@dataclass(frozen=True, slots=True)
class MobileProjectBuildResult:
    project_path: Path
    gpu_targets_path: Path | None
    gpu_target_count: int
    gpu_targets_provisioned: bool


def _build_gpu_target_export(
    *,
    inputs: list[tuple[str, Path]],
    output_path: Path,
    server_target_root: Path | None,
    server_target_manifest: Path | None,
    progress_callback: Callable[[dict[str, Any]], None] | None,
) -> tuple[int, bool]:
    """Build a portable target archive and optionally provision the local GPU server."""

    if bool(server_target_root) != bool(server_target_manifest):
        raise ValueError("GPU target root and manifest must be configured together")
    stage = output_path.parent / f".{output_path.name}.{uuid.uuid4().hex}.tmpdir"
    temporary_archive = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_archive.unlink(missing_ok=True)
    records: list[dict[str, Any]] = []
    metadata_paths: list[Path] = []
    try:
        for index, (package_id, package_path) in enumerate(inputs):
            if progress_callback:
                progress_callback(
                    {
                        "phase": "GPU_TARGETS",
                        "completed": index,
                        "total": len(inputs),
                        "stageCompleted": index,
                        "stageTotal": len(inputs),
                        "percentage": 98 + int(index * 2 / max(len(inputs), 1)),
                        "current": None,
                    }
                )
            target_dir = stage / "targets" / package_id
            files = export_tracking_target(
                package_path,
                target_dir,
                base_name="target",
            )
            metadata_paths.append(files.metadata_path)
            records.append(
                {
                    "targetId": package_id,
                    "bundleManifest": (
                        Path("targets") / package_id / files.metadata_path.name
                    ).as_posix(),
                    "referenceSha256": files.metadata["registrationReference"]["sha256"],
                    "modelSha256": files.metadata["objTarget"]["sha256"],
                    "positionNumber": files.metadata["source"].get("positionNumber"),
                    "revision": files.metadata["source"].get("revision"),
                }
            )

        index_document = {
            "schemaVersion": "1.0.0",
            "targetCount": len(records),
            "targets": records,
        }
        with zipfile.ZipFile(
            temporary_archive,
            "w",
            compression=zipfile.ZIP_DEFLATED,
        ) as archive:
            archive.writestr(
                "gpu_targets_manifest.json",
                json.dumps(index_document, ensure_ascii=False, indent=2) + "\n",
            )
            archive.writestr(
                "LEEME.txt",
                "Targets GPU generados junto al proyecto Android. "
                "Cada bundle contiene OBJ, QSRG, GLB y manifiesto enlazados por hash.\n",
            )
            for path in sorted((stage / "targets").rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(stage).as_posix())

        provisioned = False
        if server_target_root is not None and server_target_manifest is not None:
            try:
                from qs_pose_service.targets_cli import install_target_bundle
            except ImportError as exc:
                raise RuntimeError(
                    "El servidor GPU está configurado pero qs-cad-pose-service no está instalado."
                ) from exc
            for metadata_path in metadata_paths:
                install_target_bundle(
                    source_metadata_path=metadata_path,
                    target_root=server_target_root,
                    manifest_path=server_target_manifest,
                )
            provisioned = True

        temporary_archive.replace(output_path)
        return len(records), provisioned
    except BaseException:
        temporary_archive.unlink(missing_ok=True)
        raise
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def _ensure_geometry_worker(arguments: tuple[str, str, str, str]) -> str:
    package_dir, occurrence_id, output_path, ifc_path = arguments
    output = ensure_occurrence_glb(
        Path(package_dir),
        occurrence_id,
        Path(output_path),
        Path(ifc_path),
        model_cache=_WORKER_MODEL_CACHE,
    )
    return str(output)


def _export_worker_count(pending_count: int, largest_ifc_bytes: int = 0) -> int:
    default = min(4, max(1, (os.cpu_count() or 2) // 2))
    if largest_ifc_bytes >= 350 * 1024 * 1024:
        default = 1
    elif largest_ifc_bytes >= 150 * 1024 * 1024:
        default = min(default, 2)
    try:
        configured = int(os.environ.get("QS_EXPORT_WORKERS", default))
    except ValueError:
        configured = default
    return max(1, min(configured, pending_count, 8))


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _skipped_item(item: dict[str, Any], stage: str, error: BaseException) -> dict[str, Any]:
    current = item["current"]
    return {
        "sourceId": item["package"].source_id,
        "occurrenceId": item["occurrenceId"],
        "positionNumber": current["positionNumber"],
        "drawingNumber": current["drawingNumber"],
        "revision": current["revision"],
        "occurrence": current["occurrence"],
        "stage": stage,
        "reason": str(error),
    }


def _is_empty_geometry_error(error: BaseException) -> bool:
    return isinstance(error, ValueError) and "no renderable geometry" in str(error)


def _available_revisions(package: SourcePackage) -> list[int]:
    revisions = {
        int(item.get("revision", 1)) for item in package.raw.get("revisions", [])
    }
    revisions.add(int(package.raw.get("revision", 1)))
    return sorted(revisions)


def resolve_export_packages(
    catalog: ProjectCatalog,
    project_id: str,
    mode: str,
    drawings: Iterable[str],
    assemblies: Iterable[str],
) -> list[SourcePackage]:
    current = [item for item in catalog.packages() if item.project_id == project_id]
    if not current:
        raise KeyError(project_id)

    selected: list[SourcePackage]
    if mode == "COMPLETE":
        selected = current
    elif mode == "DRAWINGS":
        requested_drawings = {str(item) for item in drawings}
        if not requested_drawings:
            raise ValueError("Selecciona al menos una Zeichnung para exportar.")
        available = {package.drawing_and_position[0] for package in current}
        unknown = requested_drawings - available
        if unknown:
            raise ValueError(f"Zeichnung no encontrada: {sorted(unknown)[0]}")
        selected = [
            package
            for package in current
            if package.drawing_and_position[0] in requested_drawings
        ]
    elif mode == "ASSEMBLIES":
        requested_assemblies = {str(item) for item in assemblies}
        if not requested_assemblies:
            raise ValueError("Selecciona al menos una Baugruppe para exportar.")
        available = {package.source_id for package in current}
        unknown = requested_assemblies - available
        if unknown:
            raise ValueError("Una de las Baugruppen seleccionadas ya no existe.")
        selected = [
            package for package in current if package.source_id in requested_assemblies
        ]
    else:
        raise ValueError(f"Modo de exportación desconocido: {mode}")

    requested = sorted(
        (
            (package.source_id, revision, package)
            for package in selected
            for revision in _available_revisions(package)
        ),
        key=lambda item: (item[0], item[1]),
    )
    return [
        catalog.revision(package, revision)
        for _, revision, package in requested
    ]


def build_mobile_project(
    *,
    catalog: ProjectCatalog,
    artifacts_root: Path,
    project_id: str,
    mode: str,
    drawings: Iterable[str],
    assemblies: Iterable[str],
    output_path: Path,
    gpu_targets_output_path: Path | None = None,
    server_target_root: Path | None = None,
    server_target_manifest: Path | None = None,
    progress_callback: Callable[[dict[str, Any]], None] | None = None,
) -> MobileProjectBuildResult:
    """Create a checksummed phone project and its matching GPU target archive."""

    packages = resolve_export_packages(
        catalog,
        project_id,
        mode,
        drawings,
        assemblies,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.unlink(missing_ok=True)
    records: list[dict[str, Any]] = []
    gpu_inputs: list[tuple[str, Path]] = []
    skipped: list[dict[str, Any]] = []
    created_at = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
    project_name = packages[0].project_name
    work_items = []
    for package in packages:
        revision = int(package.raw.get("revision", 1))
        ifc_path = package.ifc_path
        for occurrence in load_occurrences(package):
            occurrence_id = str(occurrence["sourceGlobalId"])
            glb_path = (
                artifacts_root
                / project_id
                / "desktop"
                / "cache"
                / GEOMETRY_CACHE_VERSION
                / package.source_id
                / f"rev-{revision:04d}"
                / f"{occurrence_id}.glb"
            )
            package_path = (
                artifacts_root
                / project_id
                / "desktop"
                / "mobile-packages"
                / package.source_id
                / f"rev-{revision:04d}"
                / f"{occurrence_id}.qsxr"
            )
            work_items.append(
                {
                    "package": package,
                    "occurrence": occurrence,
                    "occurrenceId": occurrence_id,
                    "revision": revision,
                    "ifcPath": ifc_path,
                    "glbPath": glb_path,
                    "packagePath": package_path,
                    "current": {
                        "positionNumber": package.position_number,
                        "drawingNumber": package.drawing_and_position[0],
                        "revision": revision,
                        "occurrence": int(occurrence.get("index", 1)),
                    },
                }
            )
    total = len(work_items)
    if progress_callback:
        progress_callback(
            {
                "phase": "PREPARING",
                "completed": 0,
                "total": total,
                "stageCompleted": 0,
                "stageTotal": total,
                "percentage": 0,
                "current": None,
                "skippedCount": 0,
                "skipped": [],
            }
        )

    try:
        pending_geometry = [
            item
            for item in work_items
            if not occurrence_glb_is_current(
                item["package"].package_dir,
                item["glbPath"],
                item["ifcPath"],
            )
        ]
        geometry_done = total - len(pending_geometry)
        largest_ifc_bytes = max(
            (item["ifcPath"].stat().st_size for item in pending_geometry),
            default=0,
        )
        workers = _export_worker_count(
            max(len(pending_geometry), 1),
            largest_ifc_bytes,
        )
        if progress_callback:
            progress_callback(
                {
                    "phase": "GEOMETRY",
                    "completed": 0,
                    "total": total,
                    "stageCompleted": geometry_done,
                    "stageTotal": total,
                    "percentage": int(geometry_done * 70 / max(total, 1)),
                    "current": None,
                    "workers": workers,
                }
            )
        if pending_geometry and workers == 1:
            model_cache: dict[Path, Any] = {}
            for item in pending_geometry:
                try:
                    ensure_occurrence_glb(
                        item["package"].package_dir,
                        item["occurrenceId"],
                        item["glbPath"],
                        item["ifcPath"],
                        model_cache=model_cache,
                    )
                except ValueError as exc:
                    if not _is_empty_geometry_error(exc):
                        raise
                    skipped.append(_skipped_item(item, "GEOMETRY", exc))
                geometry_done += 1
                if progress_callback:
                    progress_callback(
                        {
                            "phase": "GEOMETRY",
                            "completed": 0,
                            "total": total,
                            "stageCompleted": geometry_done,
                            "stageTotal": total,
                            "percentage": int(geometry_done * 70 / max(total, 1)),
                            "current": item["current"],
                            "workers": workers,
                            "skippedCount": len(skipped),
                            "skipped": skipped[-100:],
                        }
                    )
        elif pending_geometry:
            context = multiprocessing.get_context("spawn")
            with ProcessPoolExecutor(max_workers=workers, mp_context=context) as executor:
                pending_iterator = iter(pending_geometry)
                futures: dict[Any, dict[str, Any]] = {}

                def submit_next() -> bool:
                    try:
                        item = next(pending_iterator)
                    except StopIteration:
                        return False
                    future = executor.submit(
                        _ensure_geometry_worker,
                        (
                            str(item["package"].package_dir),
                            item["occurrenceId"],
                            str(item["glbPath"]),
                            str(item["ifcPath"]),
                        ),
                    )
                    futures[future] = item
                    return True

                for _ in range(min(len(pending_geometry), workers * 2)):
                    submit_next()
                while futures:
                    completed, _ = wait(futures, return_when=FIRST_COMPLETED)
                    for future in completed:
                        item = futures.pop(future)
                        try:
                            future.result()
                        except ValueError as exc:
                            if not _is_empty_geometry_error(exc):
                                raise
                            skipped.append(_skipped_item(item, "GEOMETRY", exc))
                        geometry_done += 1
                        submit_next()
                        if progress_callback:
                            progress_callback(
                                {
                                    "phase": "GEOMETRY",
                                    "completed": 0,
                                    "total": total,
                                    "stageCompleted": geometry_done,
                                    "stageTotal": total,
                                    "percentage": int(geometry_done * 70 / max(total, 1)),
                                    "current": item["current"],
                                    "workers": workers,
                                    "skippedCount": len(skipped),
                                    "skipped": skipped[-100:],
                                }
                            )

        skipped_occurrences = {
            (item["sourceId"], item["revision"], item["occurrenceId"])
            for item in skipped
        }
        exportable_items = [
            item
            for item in work_items
            if (
                item["package"].source_id,
                item["revision"],
                item["occurrenceId"],
            )
            not in skipped_occurrences
        ]
        package_total = len(exportable_items)
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for index, item in enumerate(exportable_items):
                package = item["package"]
                occurrence = item["occurrence"]
                occurrence_id = item["occurrenceId"]
                revision = item["revision"]
                glb_path = item["glbPath"]
                package_path = item["packagePath"]
                current = item["current"]
                if progress_callback:
                    progress_callback(
                        {
                            "phase": "PACKAGES",
                            "completed": index,
                            "total": package_total,
                            "stageCompleted": index,
                            "stageTotal": package_total,
                            "percentage": 70 + int(index * 28 / max(package_total, 1)),
                            "current": current,
                            "workers": workers,
                            "skippedCount": len(skipped),
                            "skipped": skipped[-100:],
                        }
                    )
                try:
                    build_mobile_package(
                        package,
                        occurrence,
                        glb_path,
                        package_path,
                    )
                except (KeyError, ValueError) as exc:
                    skipped.append(_skipped_item(item, "PACKAGE", exc))
                    if progress_callback:
                        progress_callback(
                            {
                                "phase": "PACKAGES",
                                "completed": index + 1,
                                "total": package_total,
                                "stageCompleted": index + 1,
                                "stageTotal": package_total,
                                "percentage": 70
                                + int((index + 1) * 28 / max(package_total, 1)),
                                "current": current,
                                "workers": workers,
                                "skippedCount": len(skipped),
                                "skipped": skipped[-100:],
                            }
                        )
                    continue
                with zipfile.ZipFile(package_path) as inner:
                    manifest = json.loads(inner.read("manifest.json"))
                package_id = str(manifest["packageId"])
                archive_path = f"packages/{package_id}.qsxr"
                archive.write(package_path, archive_path)
                records.append(
                    {
                        "packageId": package_id,
                        "sourceId": package.source_id,
                        "occurrenceId": occurrence_id,
                        "positionNumber": package.position_number,
                        "revision": revision,
                        "path": archive_path,
                        "sha256": _sha256_file(package_path),
                        "byteLength": package_path.stat().st_size,
                    }
                )
                gpu_inputs.append((package_id, package_path))
                if progress_callback:
                    progress_callback(
                        {
                            "phase": "PACKAGES",
                            "completed": index + 1,
                            "total": package_total,
                            "stageCompleted": index + 1,
                            "stageTotal": package_total,
                            "percentage": 70
                            + int((index + 1) * 28 / max(package_total, 1)),
                            "current": current,
                            "workers": workers,
                            "skippedCount": len(skipped),
                            "skipped": skipped[-100:],
                        }
                    )

            if not records:
                reasons = "; ".join(item["reason"] for item in skipped[:3])
                raise ValueError(
                    "El proyecto no contiene ocurrencias con geometría exportable."
                    + (f" {reasons}" if reasons else "")
                )
            if progress_callback:
                progress_callback(
                    {
                        "phase": "PACKAGING",
                        "completed": package_total,
                        "total": package_total,
                        "stageCompleted": package_total,
                        "stageTotal": package_total,
                        "percentage": 99,
                        "current": None,
                        "workers": workers,
                        "skippedCount": len(skipped),
                        "skipped": skipped[-100:],
                    }
                )
            identity = "\n".join(
                f"{item['packageId']}:{item['sha256']}" for item in records
            )
            manifest = {
                "schemaVersion": BUNDLE_SCHEMA_VERSION,
                "bundleId": str(
                    uuid.uuid5(BUNDLE_NAMESPACE, f"{project_id}:{identity}")
                ),
                "createdAt": created_at,
                "producer": {"name": "QS Desktop Viewer", "version": "0.1.0"},
                "project": {
                    "projectId": str(
                        uuid.uuid5(
                            uuid.UUID("53a1209f-e62d-47f7-92cd-e8dd37b53dde"),
                            f"project:{project_id}",
                        )
                    ),
                    "name": project_name,
                },
                "exportMode": mode,
                "packages": records,
                "skipped": skipped,
            }
            archive.writestr(
                "project_bundle_manifest.json",
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            )
        gpu_target_count = 0
        gpu_targets_provisioned = False
        if gpu_targets_output_path is not None:
            gpu_targets_output_path.parent.mkdir(parents=True, exist_ok=True)
            gpu_target_count, gpu_targets_provisioned = _build_gpu_target_export(
                inputs=gpu_inputs,
                output_path=gpu_targets_output_path,
                server_target_root=server_target_root,
                server_target_manifest=server_target_manifest,
                progress_callback=progress_callback,
            )
        temporary.replace(output_path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    return MobileProjectBuildResult(
        project_path=output_path,
        gpu_targets_path=gpu_targets_output_path,
        gpu_target_count=gpu_target_count,
        gpu_targets_provisioned=gpu_targets_provisioned,
    )
