# PaddleOCR 附件识别服务

该服务只负责把订单图片转换为文本，Spring Boot 会继续完成商品匹配、草稿生成和人工确认。

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
```

容器部署时应把 OCR 服务放在内网，不要将 `/ocr` 直接暴露到公网。
