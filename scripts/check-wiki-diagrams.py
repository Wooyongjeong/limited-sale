#!/usr/bin/env python3
"""Check local wiki links and editable Excalidraw/SVG pairs without npm dependencies."""

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WIKI = ROOT / "docs/wiki"
DIAGRAMS = WIKI / "diagrams"


def normalized(value):
    return "".join(value.split())


def check():
    errors = []
    pages = sorted(WIKI.rglob("*.md"))
    links = 0
    for page in pages:
        content = page.read_text(encoding="utf-8")
        if re.search(r"^```mermaid\s*$", content, re.MULTILINE):
            errors.append(f"Mermaid block remains: {page.relative_to(ROOT)}")
        if content.count("```") % 2:
            errors.append(f"Unclosed code fence: {page.relative_to(ROOT)}")
        for target in re.findall(r"\]\(([^)]+)\)", content):
            if "://" in target or target.startswith("#"):
                continue
            links += 1
            if not (page.parent / target.split("#")[0]).exists():
                errors.append(f"Missing link: {page.relative_to(ROOT)} -> {target}")

    index = json.loads((DIAGRAMS / "index.json").read_text(encoding="utf-8"))
    names = [item["id"] for item in index]
    if len(set(names)) != len(names):
        errors.append("Duplicate IDs in diagram index")
    for extension in (".excalidraw", ".svg"):
        found = {path.stem for path in DIAGRAMS.glob(f"*{extension}")}
        if found != set(names):
            errors.append(f"Index does not match {extension} files: {found ^ set(names)}")

    for item in index:
        name = item["id"]
        try:
            scene = json.loads((DIAGRAMS / f"{name}.excalidraw").read_text(encoding="utf-8"))
            svg = ET.parse(DIAGRAMS / f"{name}.svg").getroot()
            if scene.get("type") != "excalidraw" or not scene.get("elements"):
                raise ValueError("Empty or invalid scene")
            ids = [element["id"] for element in scene["elements"]]
            if len(set(ids)) != len(ids):
                raise ValueError("Duplicate element IDs")
            visible = normalized("".join(svg.itertext()))
            for element in scene["elements"]:
                if element.get("isDeleted"):
                    continue
                if element["type"] == "text" and normalized(element["text"]) not in visible:
                    raise ValueError(f"SVG lacks scene text: {element['text']}")
                for key in ("containerId",):
                    if element.get(key) and element[key] not in ids:
                        raise ValueError(f"Broken {key}")
                for key in ("startBinding", "endBinding"):
                    if element.get(key) and element[key]["elementId"] not in ids:
                        raise ValueError(f"Broken {key}")
            if svg.tag != "{http://www.w3.org/2000/svg}svg":
                raise ValueError("Not an SVG image")
            page = (WIKI / item["page"]).read_text(encoding="utf-8")
            if f"diagrams/{name}.svg" not in page or f"diagrams/{name}.excalidraw" not in page:
                raise ValueError("Owning page does not link both image and source")
            if normalized(item["title"]) not in visible:
                raise ValueError("SVG lacks diagram title")
            if item["status"] == "planned" and "미구현" not in visible:
                raise ValueError("Planned diagram lacks implementation status")
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
            errors.append(f"{name}: {error}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"OK: {len(pages)} wiki pages, {links} local links, {len(index)} Excalidraw/SVG pairs")


if __name__ == "__main__":
    check()
