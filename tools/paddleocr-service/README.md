# PaddleOCR 附件与表格识别服务

该服务提供两条互不替代的接口：

- `POST /ocr`：兼容原 Agent 附件流程，只返回普通文本；
- `POST /table-ocr`：使用 PP-StructureV3 返回表格、单元格行列、合并关系、置信度和坐标，供正式文件任务使用。

Spring Boot 继续完成字段映射、商品匹配、人工复核、版本控制和业务审批。

## 本地启动

```powershell
python -m venv .venv
.\.venv\Scripts\pip.exe install -r requirements.txt
.\.venv\Scripts\uvicorn.exe app:app --host 0.0.0.0 --port 8090
```

首次启动 PaddleOCR 会下载中文模型。启动后访问 `GET /health`，应返回 `{"status":"ok"}`。

后端配置：

```text
AGENT_ATTACHMENT_OCR_URL=http://127.0.0.1:8090/ocr
TABLE_OCR_URL=http://127.0.0.1:8090/table-ocr
TABLE_OCR_PROVIDER=paddle-ppstructure-v3
```

PP-StructureV3 首次调用会下载并加载版面、文字与表格模型，耗时和内存占用明显高于普通 `/ocr`。生产环境应预热 `/table-ocr`，设置并发限制，并把服务部署在内网；不要把两个识别接口直接暴露到公网。

`/table-ocr` 的响应遵守后端统一契约：

```json
{
  "provider": "paddle-ppstructure-v3",
  "requestId": "...",
  "imageWidth": 1080,
  "imageHeight": 1920,
  "tables": [{
    "index": 0,
    "name": "识别表格1",
    "cells": [{
      "rowStart": 0,
      "rowEnd": 0,
      "columnStart": 0,
      "columnEnd": 1,
      "rawText": "商品名称",
      "confidence": 0.98,
      "polygon": [[10, 20], [200, 20], [200, 60], [10, 60]]
    }]
  }]
}
```
