#!/usr/bin/env python3
"""Export the app's German legal text as offline HTML, UTF-8 and a Google Docs-ready DOCX.

No third-party Python modules, network requests or document services are required.
Run after updating legal.properties or the legal assets; the privacy source stays shared.
"""
from __future__ import annotations

import html
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "store/legal"
ASSETS = ROOT / "app/src/main/assets/legal"
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
LINK = re.compile(r"https?://[^\s<>]+|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}")


def blocks(text: str):
    paragraph = []
    for line in text.splitlines() + [""]:
        if not line or line.startswith(("# ", "## ", "- ")):
            if paragraph:
                yield "Normal", "\n".join(paragraph)
                paragraph.clear()
            if line.startswith("## "):
                yield "Heading1", line[3:]
            elif line.startswith("# "):
                yield "Title", line[2:]
            elif line.startswith("- "):
                yield "ListParagraph", line[2:]
        else:
            paragraph.append(line)


def docx(text: str, path: Path):
    ET.register_namespace("w", W)
    ET.register_namespace("r", R)
    document = ET.Element(f"{{{W}}}document")
    body = ET.SubElement(document, f"{{{W}}}body")
    relations = [
        ("styles", "styles", "styles.xml", False),
        ("numbering", "numbering", "numbering.xml", False),
        ("footer", "footer", "footer1.xml", False),
    ]

    def run(parent, value, link=False):
        for i, line in enumerate(value.split("\n")):
            r = ET.SubElement(parent, f"{{{W}}}r")
            if link:
                props = ET.SubElement(r, f"{{{W}}}rPr")
                ET.SubElement(props, f"{{{W}}}rStyle", {f"{{{W}}}val": "Hyperlink"})
            if i:
                ET.SubElement(r, f"{{{W}}}br")
            t = ET.SubElement(r, f"{{{W}}}t", {"{http://www.w3.org/XML/1998/namespace}space": "preserve"})
            t.text = line

    for style, value in blocks(text):
        p = ET.SubElement(body, f"{{{W}}}p")
        props = ET.SubElement(p, f"{{{W}}}pPr")
        ET.SubElement(props, f"{{{W}}}pStyle", {f"{{{W}}}val": style})
        if style == "ListParagraph":
            num = ET.SubElement(props, f"{{{W}}}numPr")
            ET.SubElement(num, f"{{{W}}}ilvl", {f"{{{W}}}val": "0"})
            ET.SubElement(num, f"{{{W}}}numId", {f"{{{W}}}val": "1"})
        start = 0
        for match in LINK.finditer(value):
            run(p, value[start:match.start()])
            rid = f"link{len(relations)}"
            url = match[0] if match[0].startswith("http") else "mailto:" + match[0]
            relations.append((rid, "hyperlink", url, True))
            hyperlink = ET.SubElement(p, f"{{{W}}}hyperlink", {f"{{{R}}}id": rid})
            run(hyperlink, match[0], link=True)
            start = match.end()
        run(p, value[start:])

    sect = ET.SubElement(body, f"{{{W}}}sectPr")
    ET.SubElement(sect, f"{{{W}}}footerReference", {f"{{{W}}}type": "default", f"{{{R}}}id": "footer"})
    ET.SubElement(sect, f"{{{W}}}pgSz", {f"{{{W}}}w": "11906", f"{{{W}}}h": "16838"})
    ET.SubElement(sect, f"{{{W}}}pgMar", {f"{{{W}}}{k}": v for k, v in
        dict(top="1134", right="1134", bottom="1134", left="1134", header="567", footer="567", gutter="0").items()})

    styles = f'''<w:styles xmlns:w="{W}">
      <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/>
      <w:sz w:val="21"/><w:lang w:val="de-DE"/></w:rPr></w:rPrDefault>
      <w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="276" w:lineRule="auto"/>
      <w:widowControl/></w:pPr></w:pPrDefault></w:docDefaults>
      <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
      <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/>
      <w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/>
      <w:spacing w:after="260"/></w:pPr><w:rPr><w:b/><w:color w:val="17394B"/><w:sz w:val="36"/></w:rPr></w:style>
      <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
      <w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/>
      <w:keepLines/><w:spacing w:before="260" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>
      <w:rPr><w:b/><w:color w:val="17394B"/><w:sz w:val="25"/></w:rPr></w:style>
      <w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/>
      <w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="80"/></w:pPr></w:style>
      <w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/>
      <w:rPr><w:color w:val="156082"/><w:u w:val="single"/></w:rPr></w:style>
    </w:styles>'''
    numbering = f'''<w:numbering xmlns:w="{W}"><w:abstractNum w:abstractNumId="0">
      <w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/>
      <w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/>
      <w:pPr><w:tabs><w:tab w:val="num" w:pos="300"/></w:tabs><w:ind w:left="300" w:hanging="220"/></w:pPr>
      </w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>'''
    footer = f'''<w:ftr xmlns:w="{W}"><w:p><w:pPr><w:jc w:val="right"/></w:pPr>
      <w:r><w:rPr><w:sz w:val="17"/><w:color w:val="666666"/></w:rPr><w:t>QS Taller XR · </w:t></w:r>
      <w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>'''
    rels = '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    for rid, kind, url, external in relations:
        rels += f'<Relationship Id="{rid}" Type="{R}/{kind}" Target="{html.escape(url, quote=True)}"' + (' TargetMode="External"' if external else '') + '/>'
    rels += '</Relationships>'
    types = '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    types += '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    types += '<Default Extension="xml" ContentType="application/xml"/>'
    for name, kind in [('document', 'document.main'), ('styles', 'styles'), ('numbering', 'numbering'), ('footer1', 'footer')]:
        types += f'<Override PartName="/word/{name}.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{kind}+xml"/>'
    types += '</Types>'
    package = {
        '[Content_Types].xml': types,
        '_rels/.rels': f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="{R}/officeDocument" Target="word/document.xml"/></Relationships>',
        'word/document.xml': ET.tostring(document, encoding="unicode"),
        'word/styles.xml': styles, 'word/numbering.xml': numbering,
        'word/footer1.xml': footer, 'word/_rels/document.xml.rels': rels,
    }
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in package.items():
            ET.fromstring(data)  # Fail before delivering malformed XML.
            z.writestr(name, '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + data)


def main():
    subprocess.run([sys.executable, str(ROOT / 'tools/build-privacy-page.py')], check=True)
    OUT.mkdir(parents=True, exist_ok=True)
    text = (ROOT / 'store/datenschutz.txt').read_text()
    assert '{{' not in text and '[Angabe fehlt' not in text
    base = OUT / 'QS-Taller-XR-Datenschutzerklaerung-DE'
    base.with_suffix('.txt').write_text(text, encoding='utf-8')
    base.with_suffix('.html').write_bytes((ROOT / 'store/datenschutz.html').read_bytes())
    docx(text, base.with_suffix('.docx'))

    notices = (ASSETS / 'lizenzen.txt').read_text()
    for title, name in [('Apache License 2.0', 'apache-2.0.txt'), ('Mozilla Public License 2.0', 'mpl-2.0.txt'),
                        ('MIT – Fuel und Result', 'mit-fuel-result.txt'), ('Native Drittanbieterhinweise', 'native-notices.txt')]:
        notices += '\n\n## ' + title + '\n\n' + (ASSETS / name).read_text()
    (OUT / 'QS-Taller-XR-Lizenzen-und-Drittanbieterhinweise-DE.txt').write_text(notices, encoding='utf-8')
    page = ['<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">',
            '<title>QS Taller XR – Lizenzen und Drittanbieterhinweise</title>',
            '<style>body{font:15px/1.6 system-ui,sans-serif;max-width:52rem;margin:2rem auto;padding:0 1rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}a{overflow-wrap:anywhere}</style><body>']
    for style, value in blocks(notices):
        tag = {'Title':'h1', 'Heading1':'h2', 'ListParagraph':'p', 'Normal':'pre'}[style]
        escaped = html.escape(('• ' if style == 'ListParagraph' else '') + value)
        escaped = re.sub(r'https?://[^\s<]+', lambda m: '<a href="' + m[0] + '">' + m[0] + '</a>', escaped)
        page.append(f'<{tag}>{escaped}</{tag}>')
    page.append('</body></html>')
    (OUT / 'QS-Taller-XR-Lizenzen-und-Drittanbieterhinweise-DE.html').write_text('\n'.join(page), encoding='utf-8')
    print('DOCX, HTML, TXT und Lizenzanhang:', OUT)


if __name__ == '__main__':
    main()
