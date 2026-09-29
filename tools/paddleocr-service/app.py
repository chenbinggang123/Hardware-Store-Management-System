from __future__ import annotations

import os
import json
import tempfile
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from paddleocr import PaddleOCR

MAX_BYTES = int(os.getenv("OCR_MAX_FILE_BYTES", "10485760"))
ALLOWED_TYPES = {"image/jpeg", "image/png", "image/webp"}

app = FastAPI(title="Hardware Store PaddleOCR", version="1.0.0")
ocr = PaddleOCR(
    lang="ch",
    use_doc_orientation_classify=False,
    use_doc_unwarping=False,
    use_textline_orientation=False,
)


def collect_text(value: Any) -> list[str]:
    """Extract recognized strings from PaddleOCR 3.x result dictionaries."""
    if isinstance(value, dict):
        if isinstance(value.get("rec_texts"), list):
            return [str(item).strip() for item in value["rec_texts"] if str(item).strip()]
        lines: list[str] = []
        for child in value.values():
            lines.extend(collect_text(child))
        return lines
    if isinstance(value, list):
        lines: list[str] = []
        for child in value:
            lines.extend(collect_text(child))
        return lines
    if isinstance(value, str) and value.lstrip().startswith(("{", "[")):
        try:
            return collect_text(json.loads(value))
        except json.JSONDecodeError:
            return []
    return []


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ocr")
async def recognize(file: UploadFile = File(...)) -> dict[str, Any]:
    if file.content_type not in ALLOWED_TYPES:
        raise HTTPException(status_code=415, detail="仅支持 JPG、PNG 和 WEBP 图片")
    content = await file.read(MAX_BYTES + 1)
    if not content:
        raise HTTPException(status_code=400, detail="文件为空")
    if len(content) > MAX_BYTES:
        raise HTTPException(status_code=413, detail="文件过大")

    suffix = Path(file.filename or "image.jpg").suffix.lower()
    if suffix not in {".jpg", ".jpeg", ".png", ".webp"}:
        suffix = ".jpg"
    path = ""
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as target:
            target.write(content)
            path = target.name
        results = list(ocr.predict(input=path))
        raw_results = [getattr(result, "json", result) for result in results]
        lines = collect_text(raw_results)
        if not lines:
            raise HTTPException(status_code=422, detail="图片中没有识别到文字")
        return {"text": "\n".join(lines), "lineCount": len(lines)}
    finally:
        if path:
            Path(path).unlink(missing_ok=True)
