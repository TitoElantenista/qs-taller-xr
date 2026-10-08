"""Command-line launcher for the local desktop viewer."""

from __future__ import annotations

import argparse
import threading
import webbrowser
from pathlib import Path

import uvicorn

from .api import DEFAULT_ARTIFACTS_ROOT, DEFAULT_FRONTEND_DIST, create_app


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="QS desktop Baugruppen viewer")
    result.add_argument("--artifacts", type=Path, default=DEFAULT_ARTIFACTS_ROOT)
    result.add_argument("--frontend", type=Path, default=DEFAULT_FRONTEND_DIST)
    result.add_argument("--host", default="127.0.0.1")
    result.add_argument("--port", type=int, default=8765)
    result.add_argument("--no-browser", action="store_true")
    return result


def main() -> None:
    arguments = parser().parse_args()
    url = f"http://{arguments.host}:{arguments.port}"
    if not arguments.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    uvicorn.run(
        create_app(arguments.artifacts, arguments.frontend),
        host=arguments.host,
        port=arguments.port,
    )


if __name__ == "__main__":
    main()

