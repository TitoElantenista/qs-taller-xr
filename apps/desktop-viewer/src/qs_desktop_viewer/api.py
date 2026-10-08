"""FastAPI application serving the local catalog, GLB cache and frontend."""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import threading
import uuid
from pathlib import Path
from typing import Annotated, Literal

from fastapi import (
    FastAPI,
    BackgroundTasks,
    File,
    Form,
    HTTPException,
    Path as ApiPath,
    Query,
    Response,
    UploadFile,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from .catalog import ProjectCatalog, load_occurrences
from .glb import GEOMETRY_CACHE_VERSION, ensure_occurrence_glb
from .import_history import ImportHistoryStore
from .index_ifc import ImportValidationError, _folder_name, build_desktop_index
from .measurements import MeasurementStore
from .mobile_package import build_mobile_package
from .mobile_project import build_mobile_project, resolve_export_packages


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_ARTIFACTS_ROOT = REPOSITORY_ROOT / "artifacts"
DEFAULT_FRONTEND_DIST = REPOSITORY_ROOT / "apps" / "desktop-viewer" / "frontend" / "dist"
SafeId = Annotated[str, ApiPath(pattern=r"^[A-Za-z0-9_.-]+$")]
IfcId = Annotated[str, ApiPath(min_length=1, max_length=64)]


class MeasurementInput(BaseModel):
    start: list[float] = Field(min_length=3, max_length=3)
    end: list[float] = Field(min_length=3, max_length=3)
    label: str | None = Field(default=None, max_length=120)


class ProjectMobileExportInput(BaseModel):
    mode: Literal["COMPLETE", "DRAWINGS", "ASSEMBLIES"]
    drawings: list[str] = Field(default_factory=list, max_length=10_000)
    assemblies: list[str] = Field(default_factory=list, max_length=100_000)


def create_app(
    artifacts_root: Path = DEFAULT_ARTIFACTS_ROOT,
    frontend_dist: Path = DEFAULT_FRONTEND_DIST,
) -> FastAPI:
    artifacts_root = artifacts_root.resolve()
    frontend_dist = frontend_dist.resolve()
    configured_target_root = os.environ.get("QS_DESKTOP_GPU_TARGET_ROOT")
    configured_target_manifest = os.environ.get("QS_DESKTOP_GPU_TARGET_MANIFEST")
    if bool(configured_target_root) != bool(configured_target_manifest):
        raise ValueError(
            "QS_DESKTOP_GPU_TARGET_ROOT and QS_DESKTOP_GPU_TARGET_MANIFEST must be set together"
        )
    gpu_target_root = (
        Path(configured_target_root).expanduser().resolve()
        if configured_target_root
        else None
    )
    gpu_target_manifest = (
        Path(configured_target_manifest).expanduser().resolve()
        if configured_target_manifest
        else None
    )
    catalog = ProjectCatalog(artifacts_root)
    measurements = MeasurementStore(artifacts_root)
    import_history = ImportHistoryStore(artifacts_root)
    import_lock = asyncio.Lock()
    mobile_export_jobs: dict[str, dict] = {}
    mobile_export_jobs_lock = threading.RLock()

    app = FastAPI(
        title="QS Desktop Viewer API",
        version="0.1.0",
        description="Local project catalog, 3D conversion and measurement persistence.",
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    def package_or_404(project_id: str, source_id: str):
        try:
            return catalog.get(project_id, source_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Baugruppe package not found") from exc

    def revision_package_or_404(project_id: str, source_id: str, revision: int | None):
        try:
            return catalog.get_revision(project_id, source_id, revision)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Revision not found") from exc

    def update_mobile_export_job(job_id: str, **values) -> None:
        with mobile_export_jobs_lock:
            mobile_export_jobs[job_id].update(values)

    def public_mobile_export_job(job: dict) -> dict:
        completed = int(job.get("completed", 0))
        total = int(job.get("total", 0))
        if job["status"] == "COMPLETED":
            percentage = 100
        elif job.get("percentage") is not None:
            percentage = int(job["percentage"])
        elif total:
            percentage = min(98, int(completed * 100 / total))
        else:
            percentage = 0
        result = {
            key: job.get(key)
            for key in (
                "jobId",
                "projectId",
                "status",
                "phase",
                "completed",
                "total",
                "stageCompleted",
                "stageTotal",
                "workers",
                "skippedCount",
                "skipped",
                "current",
                "error",
                "filename",
                "gpuTargetsFilename",
                "gpuTargetCount",
                "gpuTargetsProvisioned",
            )
        }
        result["percentage"] = percentage
        if job["status"] == "COMPLETED":
            result["downloadUrl"] = (
                f"/api/projects/{job['projectId']}/mobile-project-exports/"
                f"{job['jobId']}/download"
            )
            result["gpuTargetsDownloadUrl"] = (
                f"/api/projects/{job['projectId']}/mobile-project-exports/"
                f"{job['jobId']}/gpu-targets/download"
            )
        return result

    def run_mobile_export_job(
        job_id: str,
        project_id: str,
        value: ProjectMobileExportInput,
        output: Path,
    ) -> None:
        update_mobile_export_job(job_id, status="RUNNING", phase="PREPARING")

        def report(event: dict) -> None:
            update_mobile_export_job(job_id, **event)

        try:
            result = build_mobile_project(
                catalog=catalog,
                artifacts_root=artifacts_root,
                project_id=project_id,
                mode=value.mode,
                drawings=value.drawings,
                assemblies=value.assemblies,
                output_path=output,
                gpu_targets_output_path=output.with_suffix(".qsgputargets"),
                server_target_root=gpu_target_root,
                server_target_manifest=gpu_target_manifest,
                progress_callback=report,
            )
        except (FileNotFoundError, KeyError, ValueError, RuntimeError) as exc:
            update_mobile_export_job(
                job_id,
                status="FAILED",
                phase="FAILED",
                error=str(exc),
                current=None,
            )
        except BaseException:
            update_mobile_export_job(
                job_id,
                status="FAILED",
                phase="FAILED",
                error="La exportación se interrumpió de forma inesperada.",
                current=None,
            )
            raise
        else:
            with mobile_export_jobs_lock:
                total = int(mobile_export_jobs[job_id].get("total", 0))
            update_mobile_export_job(
                job_id,
                status="COMPLETED",
                phase="COMPLETED",
                completed=total,
                percentage=100,
                current=None,
                gpuTargetCount=result.gpu_target_count,
                gpuTargetsProvisioned=result.gpu_targets_provisioned,
            )

    async def save_ifc_upload(upload: UploadFile) -> Path:
        filename = Path(upload.filename or "upload.ifc").name
        if Path(filename).suffix.casefold() != ".ifc":
            raise HTTPException(status_code=422, detail="The selected file must use the .ifc extension")
        upload_dir = artifacts_root / ".uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        request_dir = upload_dir / uuid.uuid4().hex
        request_dir.mkdir()
        output = request_dir / filename
        byte_count = 0
        with output.open("wb") as handle:
            while chunk := await upload.read(1024 * 1024):
                byte_count += len(chunk)
                if byte_count > 2 * 1024 * 1024 * 1024:
                    handle.close()
                    output.unlink(missing_ok=True)
                    request_dir.rmdir()
                    raise HTTPException(status_code=413, detail="The IFC exceeds the 2 GB limit")
                handle.write(chunk)
        await upload.close()
        with output.open("rb") as handle:
            header = handle.read(128)
        if byte_count < 32 or b"ISO-10303-21" not in header:
            output.unlink(missing_ok=True)
            request_dir.rmdir()
            raise HTTPException(status_code=422, detail="The uploaded file is not a valid IFC STEP file")
        return output

    def import_response(index_path: Path) -> dict:
        document = json.loads(index_path.read_text(encoding="utf-8"))
        project = document.get("project") or {}
        return {
            "projectId": project.get("folder") or index_path.parent.parent.name,
            "projectName": project.get("name") or index_path.parent.parent.name,
            "summary": document.get("lastImport") or {},
            "sourceGeometryAudit": document.get("sourceGeometryAudit") or {},
        }

    @app.get("/api/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/catalog")
    def get_catalog() -> dict:
        return catalog.document()

    @app.post("/api/projects", status_code=status.HTTP_201_CREATED)
    async def create_project(
        project_name: Annotated[str, Form(min_length=1, max_length=120)],
        ifc: Annotated[UploadFile, File()],
    ) -> dict:
        clean_name = project_name.strip()
        if not clean_name:
            raise HTTPException(status_code=422, detail="Project name is required")
        project_id = _folder_name(clean_name)
        index_path = artifacts_root / project_id / "desktop" / "project-index.json"
        if index_path.exists():
            raise HTTPException(status_code=409, detail="A project with that name already exists")
        temporary = await save_ifc_upload(ifc)
        try:
            async with import_lock:
                result = await run_in_threadpool(
                    build_desktop_index,
                    temporary,
                    artifacts_root,
                    clean_name,
                    False,
                )
            return import_response(result)
        except ImportValidationError as exc:
            raise HTTPException(status_code=422, detail=exc.as_detail()) from exc
        except (FileNotFoundError, FileExistsError, ValueError, RuntimeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        finally:
            temporary.unlink(missing_ok=True)
            temporary.parent.rmdir()

    @app.post("/api/projects/{project_id}/imports")
    async def update_project(project_id: SafeId, ifc: Annotated[UploadFile, File()]) -> dict:
        index_path = artifacts_root / project_id / "desktop" / "project-index.json"
        if not index_path.is_file():
            raise HTTPException(status_code=404, detail="Project not found")
        current = json.loads(index_path.read_text(encoding="utf-8"))
        project_name = str((current.get("project") or {}).get("name") or project_id)
        temporary = await save_ifc_upload(ifc)
        try:
            async with import_lock:
                result = await run_in_threadpool(
                    build_desktop_index,
                    temporary,
                    artifacts_root,
                    project_name,
                    True,
                )
            return import_response(result)
        except ImportValidationError as exc:
            raise HTTPException(status_code=422, detail=exc.as_detail()) from exc
        except (FileNotFoundError, FileExistsError, ValueError, RuntimeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        finally:
            temporary.unlink(missing_ok=True)
            temporary.parent.rmdir()

    @app.delete(
        "/api/projects/{project_id}",
        status_code=status.HTTP_204_NO_CONTENT,
    )
    async def delete_project(project_id: SafeId) -> Response:
        project_root = (artifacts_root / project_id).resolve()
        if project_root.parent != artifacts_root or not project_root.is_dir():
            raise HTTPException(status_code=404, detail="Project not found")
        if not any(project_root.glob("*/project-index.json")):
            raise HTTPException(status_code=404, detail="Project not found")

        async with import_lock:
            with mobile_export_jobs_lock:
                project_jobs = [
                    job
                    for job in mobile_export_jobs.values()
                    if job.get("projectId") == project_id
                ]
                if any(
                    job.get("status") in {"QUEUED", "RUNNING"}
                    for job in project_jobs
                ):
                    raise HTTPException(
                        status_code=409,
                        detail=(
                            "No se puede borrar el proyecto mientras hay una "
                            "exportación para Android en curso"
                        ),
                    )

            await run_in_threadpool(shutil.rmtree, project_root)

            with mobile_export_jobs_lock:
                for job_id in [
                    job_id
                    for job_id, job in mobile_export_jobs.items()
                    if job.get("projectId") == project_id
                ]:
                    mobile_export_jobs.pop(job_id, None)

        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @app.get("/api/projects/{project_id}/imports")
    def list_project_imports(project_id: SafeId) -> dict:
        try:
            return import_history.list(project_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Project not found") from exc

    @app.delete("/api/projects/{project_id}/imports/{import_id}")
    async def delete_project_import(project_id: SafeId, import_id: SafeId) -> dict:
        try:
            async with import_lock:
                return await run_in_threadpool(
                    import_history.delete_latest, project_id, import_id
                )
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Import not found") from exc
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc

    @app.get("/api/projects/{project_id}/sources/{source_id}")
    def get_source(
        project_id: SafeId,
        source_id: SafeId,
        revision: int | None = Query(default=None, ge=1),
    ) -> dict:
        package = revision_package_or_404(project_id, source_id, revision)
        return {"source": package.summary(), "occurrences": load_occurrences(package)}

    @app.get(
        "/api/projects/{project_id}/sources/{source_id}/occurrences/{occurrence_id}/model.glb",
        response_class=FileResponse,
    )
    def get_model(
        project_id: SafeId,
        source_id: SafeId,
        occurrence_id: IfcId,
        revision: int | None = Query(default=None, ge=1),
    ):
        package = revision_package_or_404(project_id, source_id, revision)
        revision_number = int(package.raw.get("revision", 1))
        known_ids = {item["sourceGlobalId"] for item in load_occurrences(package)}
        if occurrence_id not in known_ids:
            raise HTTPException(status_code=404, detail="Occurrence not found")
        output = (
            artifacts_root
            / project_id
            / "desktop"
            / "cache"
            / GEOMETRY_CACHE_VERSION
            / source_id
            / f"rev-{revision_number:04d}"
            / f"{occurrence_id}.glb"
        )
        try:
            ensure_occurrence_glb(
                package.package_dir,
                occurrence_id,
                output,
                package.ifc_path,
            )
        except (FileNotFoundError, KeyError, ValueError, RuntimeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return FileResponse(output, media_type="model/gltf-binary", filename=f"{source_id}.glb")

    @app.get(
        "/api/projects/{project_id}/sources/{source_id}/occurrences/"
        "{occurrence_id}/mobile-package"
    )
    def get_mobile_package(
        project_id: SafeId,
        source_id: SafeId,
        occurrence_id: IfcId,
        revision: int | None = Query(default=None, ge=1),
    ):
        package = revision_package_or_404(project_id, source_id, revision)
        revision_number = int(package.raw.get("revision", 1))
        occurrence = next(
            (
                item
                for item in load_occurrences(package)
                if item["sourceGlobalId"] == occurrence_id
            ),
            None,
        )
        if occurrence is None:
            raise HTTPException(status_code=404, detail="Occurrence not found")
        glb_path = (
            artifacts_root
            / project_id
            / "desktop"
            / "cache"
            / GEOMETRY_CACHE_VERSION
            / source_id
            / f"rev-{revision_number:04d}"
            / f"{occurrence_id}.glb"
        )
        output = (
            artifacts_root
            / project_id
            / "desktop"
            / "mobile-packages"
            / source_id
            / f"rev-{revision_number:04d}"
            / f"{occurrence_id}.qsxr"
        )
        try:
            ensure_occurrence_glb(
                package.package_dir,
                occurrence_id,
                glb_path,
                package.ifc_path,
            )
            build_mobile_package(
                package,
                occurrence,
                glb_path,
                output,
            )
        except (FileNotFoundError, KeyError, ValueError, RuntimeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        filename = f"{_folder_name(package.position_number)}-rev-{revision_number}.qsxr"
        return FileResponse(output, media_type="application/zip", filename=filename)

    @app.post(
        "/api/projects/{project_id}/mobile-project-exports",
        status_code=status.HTTP_202_ACCEPTED,
    )
    def start_mobile_project_export(
        project_id: SafeId,
        value: ProjectMobileExportInput,
        background_tasks: BackgroundTasks,
    ) -> dict:
        try:
            selected = resolve_export_packages(
                catalog,
                project_id,
                value.mode,
                value.drawings,
                value.assemblies,
            )
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Project not found") from exc
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        project_package = selected[0]
        job_id = uuid.uuid4().hex
        output = (
            artifacts_root
            / project_id
            / "desktop"
            / "mobile-projects"
            / f"{job_id}.qsproject"
        )
        filename = f"{_folder_name(project_package.project_name)}.qsproject"
        gpu_targets_output = output.with_suffix(".qsgputargets")
        gpu_targets_filename = f"{_folder_name(project_package.project_name)}.qsgputargets"
        job = {
            "jobId": job_id,
            "projectId": project_id,
            "status": "QUEUED",
            "phase": "QUEUED",
            "completed": 0,
            "total": 0,
            "current": None,
            "error": None,
            "filename": filename,
            "outputPath": output,
            "gpuTargetsFilename": gpu_targets_filename,
            "gpuTargetOutputPath": gpu_targets_output,
            "gpuTargetCount": 0,
            "gpuTargetsProvisioned": False,
        }
        with mobile_export_jobs_lock:
            mobile_export_jobs[job_id] = job
        background_tasks.add_task(
            run_mobile_export_job,
            job_id,
            project_id,
            value,
            output,
        )
        return public_mobile_export_job(job)

    @app.get("/api/projects/{project_id}/mobile-project-exports/{job_id}")
    def get_mobile_project_export(project_id: SafeId, job_id: SafeId) -> dict:
        with mobile_export_jobs_lock:
            job = mobile_export_jobs.get(job_id)
            if job is None or job["projectId"] != project_id:
                raise HTTPException(status_code=404, detail="Export not found")
            return public_mobile_export_job(dict(job))

    @app.get(
        "/api/projects/{project_id}/mobile-project-exports/{job_id}/download",
        response_class=FileResponse,
    )
    def download_mobile_project_export(project_id: SafeId, job_id: SafeId):
        with mobile_export_jobs_lock:
            job = mobile_export_jobs.get(job_id)
            if job is None or job["projectId"] != project_id:
                raise HTTPException(status_code=404, detail="Export not found")
            if job["status"] != "COMPLETED":
                raise HTTPException(status_code=409, detail="Export is not complete")
            output = Path(job["outputPath"])
            filename = str(job["filename"])
        if not output.is_file():
            raise HTTPException(status_code=410, detail="Export file is no longer available")
        return FileResponse(output, media_type="application/zip", filename=filename)

    @app.get(
        "/api/projects/{project_id}/mobile-project-exports/{job_id}/gpu-targets/download",
        response_class=FileResponse,
    )
    def download_gpu_targets_export(project_id: SafeId, job_id: SafeId):
        with mobile_export_jobs_lock:
            job = mobile_export_jobs.get(job_id)
            if job is None or job["projectId"] != project_id:
                raise HTTPException(status_code=404, detail="Export not found")
            if job["status"] != "COMPLETED":
                raise HTTPException(status_code=409, detail="Export is not complete")
            output = Path(job["gpuTargetOutputPath"])
            filename = str(job["gpuTargetsFilename"])
        if not output.is_file():
            raise HTTPException(status_code=410, detail="GPU targets are no longer available")
        return FileResponse(output, media_type="application/zip", filename=filename)

    measurement_route = (
        "/api/projects/{project_id}/sources/{source_id}/occurrences/"
        "{occurrence_id}/measurements"
    )

    @app.get(measurement_route)
    def list_measurements(
        project_id: SafeId,
        source_id: SafeId,
        occurrence_id: IfcId,
        revision: int = Query(default=1, ge=1),
    ) -> dict:
        revision_package_or_404(project_id, source_id, revision)
        return {
            "measurements": measurements.list(
                project_id, source_id, occurrence_id, revision
            )
        }

    @app.post(measurement_route, status_code=status.HTTP_201_CREATED)
    def create_measurement(
        project_id: SafeId,
        source_id: SafeId,
        occurrence_id: IfcId,
        value: MeasurementInput,
        revision: int = Query(default=1, ge=1),
    ) -> dict:
        package = revision_package_or_404(project_id, source_id, revision)
        known_ids = {item["sourceGlobalId"] for item in load_occurrences(package)}
        if occurrence_id not in known_ids:
            raise HTTPException(status_code=404, detail="Occurrence not found")
        try:
            return measurements.create(
                project_id,
                source_id,
                occurrence_id,
                value.start,
                value.end,
                value.label,
                revision,
            )
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.delete(measurement_route + "/{measurement_id}", status_code=status.HTTP_204_NO_CONTENT)
    def delete_measurement(
        project_id: SafeId,
        source_id: SafeId,
        occurrence_id: IfcId,
        measurement_id: SafeId,
        revision: int = Query(default=1, ge=1),
    ) -> Response:
        revision_package_or_404(project_id, source_id, revision)
        if not measurements.delete(
            project_id, source_id, occurrence_id, measurement_id, revision
        ):
            raise HTTPException(status_code=404, detail="Measurement not found")
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @app.delete(measurement_route, status_code=status.HTTP_204_NO_CONTENT)
    def delete_all_measurements(
        project_id: SafeId,
        source_id: SafeId,
        occurrence_id: IfcId,
        revision: int = Query(default=1, ge=1),
    ) -> Response:
        revision_package_or_404(project_id, source_id, revision)
        measurements.delete_all(project_id, source_id, occurrence_id, revision)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    if frontend_dist.is_dir():
        assets = frontend_dist / "assets"
        if assets.is_dir():
            app.mount("/assets", StaticFiles(directory=assets), name="assets")

        @app.get("/{path:path}", include_in_schema=False)
        def frontend(path: str):
            candidate = (frontend_dist / path).resolve()
            if path and candidate.is_relative_to(frontend_dist) and candidate.is_file():
                return FileResponse(candidate)
            return FileResponse(frontend_dist / "index.html")
    else:
        @app.get("/", include_in_schema=False)
        def frontend_missing() -> Response:
            return Response(
                "Frontend not built. Run: cd apps/desktop-viewer/frontend && npm install && npm run build",
                media_type="text/plain",
                status_code=503,
            )

    return app


app = create_app()
