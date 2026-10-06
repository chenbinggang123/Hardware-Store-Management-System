from __future__ import annotations

import os
import json
import tempfile
import threading
import uuid
from functools import lru_cache
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from paddleocr import PaddleOCR, PPStructureV3
from PIL import Image

MAX_BYTES = int(os.getenv("OCR_MAX_FILE_BYTES", "10485760"))
ALLOWED_TYPES = {"image/jpeg", "image/png", "image/webp"}

app = FastAPI(title="Hardware Store PaddleOCR", version="1.0.0")
inference_lock = threading.Lock()


@lru_cache(maxsize=1)
def text_ocr() -> PaddleOCR:
    return PaddleOCR(
        lang="ch",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
    )


@lru_cache(maxsize=1)
def table_ocr() -> PPStructureV3:
    return PPStructureV3(
        lang="ch",
        use_doc_orientation_classify=True,
        use_doc_unwarping=True,
        use_textline_orientation=True,
        use_formula_recognition=False,
        use_chart_recognition=False,
        use_seal_recognition=False,
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


def json_value(value: Any) -> Any:
    """Convert Paddle/PaddleX result wrappers and numpy values into JSON-safe values."""
    if isinstance(value, str):
        if value.lstrip().startswith(("{", "[")):
            try:
                return json_value(json.loads(value))
            except json.JSONDecodeError:
                return value
        return value
    if isinstance(value, dict):
        return {str(key): json_value(child) for key, child in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_value(child) for child in value]
    if hasattr(value, "tolist"):
        return json_value(value.tolist())
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def result_payload(result: Any) -> dict[str, Any]:
    value = json_value(getattr(result, "json", result))
    if isinstance(value, dict) and isinstance(value.get("res"), dict):
        return value["res"]
    return value if isinstance(value, dict) else {}


def polygon(value: Any) -> list[list[int]]:
    value = json_value(value)
    if not isinstance(value, list) or not value:
        return []
    if len(value) >= 4 and all(isinstance(item, (int, float)) for item in value[:4]):
        if len(value) >= 8:
            return [[int(value[index]), int(value[index + 1])] for index in range(0, 8, 2)]
        x1, y1, x2, y2 = value[:4]
        return [[int(x1), int(y1)], [int(x2), int(y1)], [int(x2), int(y2)], [int(x1), int(y2)]]
    points: list[list[int]] = []
    for item in value:
        if isinstance(item, list) and len(item) >= 2:
            points.append([int(item[0]), int(item[1])])
    return points


class TableHtmlParser(HTMLParser):
    """Turn Paddle's predicted HTML table into stable row/column cell coordinates."""

    def __init__(self, boxes: list[Any], scores: list[Any]) -> None:
        super().__init__(convert_charrefs=True)
        self.boxes = boxes
        self.scores = scores
        self.cells: list[dict[str, Any]] = []
        self.row = -1
        self.column = 0
        self.occupied: set[tuple[int, int]] = set()
        self.current: dict[str, Any] | None = None
        self.text_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() == "tr":
            self.row += 1
            self.column = 0
            return
        if tag.lower() not in {"td", "th"} or self.row < 0:
            return
        while (self.row, self.column) in self.occupied:
            self.column += 1
        values = {key.lower(): value for key, value in attrs}
        row_span = max(int(values.get("rowspan") or 1), 1)
        column_span = max(int(values.get("colspan") or 1), 1)
        index = len(self.cells)
        self.current = {
            "rowStart": self.row,
            "rowEnd": self.row + row_span - 1,
            "columnStart": self.column,
            "columnEnd": self.column + column_span - 1,
            "rawText": "",
            "confidence": float(self.scores[index]) if index < len(self.scores) else None,
            "polygon": polygon(self.boxes[index]) if index < len(self.boxes) else [],
        }
        for target_row in range(self.row, self.row + row_span):
            for target_column in range(self.column, self.column + column_span):
                if target_row != self.row or target_column != self.column:
                    self.occupied.add((target_row, target_column))
        self.column += column_span
        self.text_parts = []

    def handle_data(self, data: str) -> None:
        if self.current is not None and data.strip():
            self.text_parts.append(data.strip())

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() not in {"td", "th"} or self.current is None:
            return
        self.current["rawText"] = " ".join(self.text_parts).strip()
        self.cells.append(self.current)
        self.current = None
        self.text_parts = []


def structured_tables(payloads: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[str], float | None]:
    tables: list[dict[str, Any]] = []
    warnings: list[str] = []
    angle: float | None = None
    for payload in payloads:
        preprocessor = payload.get("doc_preprocessor_res") or {}
        if angle is None and isinstance(preprocessor, dict) and preprocessor.get("angle") is not None:
            angle = float(preprocessor["angle"])
        table_results = payload.get("table_res_list") or []
        for table_result in table_results:
            if not isinstance(table_result, dict):
                continue
            html = table_result.get("pred_html") or ""
            boxes = table_result.get("cell_box_list") or []
            table_prediction = table_result.get("table_ocr_pred") or {}
            scores = table_prediction.get("rec_scores") if isinstance(table_prediction, dict) else []
            parser = TableHtmlParser(json_value(boxes) or [], json_value(scores) or [])
            parser.feed(str(html))
            if not parser.cells:
                warnings.append("检测到表格区域，但没有恢复出单元格结构")
                continue
            tables.append({
                "index": len(tables),
                "name": f"识别表格{len(tables) + 1}",
                "cells": parser.cells,
            })
    return tables, warnings, angle


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
        with inference_lock:
            results = list(text_ocr().predict(input=path))
        raw_results = [getattr(result, "json", result) for result in results]
        lines = collect_text(raw_results)
        if not lines:
            raise HTTPException(status_code=422, detail="图片中没有识别到文字")
        return {"text": "\n".join(lines), "lineCount": len(lines)}
    finally:
        if path:
            Path(path).unlink(missing_ok=True)


@app.post("/table-ocr")
async def recognize_table(file: UploadFile = File(...)) -> dict[str, Any]:
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
        with Image.open(path) as image:
            width, height = image.size
        with inference_lock:
            results = list(table_ocr().predict(input=path))
        payloads = [result_payload(result) for result in results]
        tables, warnings, angle = structured_tables(payloads)
        if not tables:
            raise HTTPException(status_code=422, detail="图片中没有识别到可恢复的表格结构")
        return {
            "provider": "paddle-ppstructure-v3",
            "modelVersion": os.getenv("OCR_MODEL_VERSION", "paddleocr-3.0.0"),
            "requestId": str(uuid.uuid4()),
            "detectedAngle": angle,
            "imageWidth": width,
            "imageHeight": height,
            "tables": tables,
            "warnings": warnings,
        }
    finally:
        if path:
            Path(path).unlink(missing_ok=True)
