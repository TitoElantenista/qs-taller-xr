#!/usr/bin/env python3
"""Builds store/datenschutz.html from the same text the app shows.

Google Play needs a public URL with the privacy policy.  Upload the generated file to your
website and put its address into legal.properties (privacy.url).  The text and the provider
details come from app/src/main/assets/legal/datenschutz.txt and legal.properties, so the page in
the store and the screen in the app always say the same.
"""
from __future__ import annotations

import html
import re
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MISSING = "[Angabe fehlt – legal.properties ausfüllen]"


def properties(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def main() -> int:
    legal = properties(ROOT / "legal.properties")
    text = (ROOT / "app/src/main/assets/legal/datenschutz.txt").read_text(encoding="utf-8")
    identity = "\n".join(value for value in (
        legal.get("provider.name") or MISSING,
        legal.get("provider.street"), legal.get("provider.city"),
        legal.get("provider.country") if legal.get("provider.street") or legal.get("provider.city") else None,
    ) if value)
    block = identity
    contact = "E-Mail: " + (legal.get("provider.email") or MISSING)
    if legal.get("provider.phone"):
        contact += "\nTelefon: " + legal["provider.phone"]
    text = (text.replace("{{provider.block}}", block)
            .replace("{{provider.identity}}", identity)
            .replace("{{provider.contact}}", contact)
            .replace("{{privacy.date}}", legal.get("privacy.date") or MISSING))
    if "{{" in text:
        raise ValueError("Unaufgelöster Platzhalter im Datenschutztext")
    policy_ready = all(legal.get(key) for key in ("provider.name", "provider.email", "privacy.date"))

    def inline(value: str) -> str:
        # Escape all text, creating only explicit links; the page needs no external assets.
        return re.sub(r"https?://[^\s<]+", lambda m: '<a href="' + m[0] + '">' + m[0] + '</a>', html.escape(value))

    body: list[str] = []
    paragraph: list[str] = []
    bullets: list[str] = []

    def flush() -> None:
        if paragraph:
            body.append("<p>" + "<br>".join(inline(line) for line in paragraph) + "</p>")
            paragraph.clear()
        if bullets:
            body.append("<ul>" + "".join(f"<li>{inline(item)}</li>" for item in bullets) + "</ul>")
            bullets.clear()

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line:
            flush()
        elif line.startswith("## "):
            flush()
            body.append(f"<h2>{html.escape(line[3:])}</h2>")
        elif line.startswith("# "):
            flush()
            body.append(f"<h1>{html.escape(line[2:])}</h1>")
        elif line.startswith("- "):
            if paragraph:
                flush()
            bullets.append(line[2:])
        else:
            if bullets:
                flush()
            paragraph.append(line)
    flush()

    page = (
        '<!doctype html>\n<html lang="de">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        "<title>Datenschutzerklärung – QS Taller XR</title>\n"
        "<style>body{font-family:system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;"
        "line-height:1.55;color:#16222b}a{overflow-wrap:anywhere}h1{font-size:1.7rem}h2{font-size:1.15rem;margin-top:2rem}</style>\n"
        "</head>\n<body>\n" + "\n".join(body) + "\n</body>\n</html>\n"
    )
    target = ROOT / "store/datenschutz.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(page, encoding="utf-8")
    (ROOT / "store/datenschutz.txt").write_text(text, encoding="utf-8")
    print(f"{target.relative_to(ROOT)} und store/datenschutz.txt geschrieben")
    return 0 if policy_ready else 1


if __name__ == "__main__":
    sys.exit(main())
