# Excel 文件任务后端接口

基础路径：`/api/excel-tasks`

## 当前能力

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/excel-tasks` | multipart 上传 XLS、XLSX 或 CSV，并创建任务 |
| POST | `/excel-tasks/cloud` | 从 CloudBase 文件 ID 或受信下载地址创建任务 |
| GET | `/excel-tasks` | 查询当前用户任务，可用 `status` 筛选 |
| GET | `/excel-tasks/{taskId}` | 查询任务和工作表结构 |
| GET | `/excel-tasks/{taskId}/sheets/{sheetId}/rows` | 分页查看原始数据行，默认 50 行，最大 200 行 |
| PUT | `/excel-tasks/{taskId}/sheets/{sheetId}/mapping` | 保存字段映射并生成商品匹配和变更预览 |
| GET | `/excel-tasks/{taskId}/sheets/{sheetId}/review-summary` | 查看当前工作表的映射及审核统计 |
| GET | `/excel-tasks/{taskId}/sheets/{sheetId}/review-rows` | 分页查看标准化值、商品候选和变更前后值 |
| PUT | `/excel-tasks/{taskId}/sheets/{sheetId}/review-rows/{resultId}` | 人工选择商品、批准新增商品或排除数据行 |
| POST | `/excel-tasks/{taskId}/validate` | 提交前整单校验，返回全部阻塞原因 |
| POST | `/excel-tasks/{taskId}/commit` | 使用任务版本和幂等键提交正式变更 |

multipart 上传可传 `purpose`：

- `BUYER_QUOTE`：买家询价或需求表
- `SUPPLIER_PRICE`：厂家价格表
- `PRODUCT_IMPORT`：商品资料导入
- `INVENTORY_COUNT`：库存盘点
- `PURCHASE_RECEIPT`：采购到货
- `UNCLASSIFIED`：尚未确定用途

## 当前状态

```text
PARSING -> READY_FOR_MAPPING -> READY_FOR_REVIEW -> COMMITTED
        -> FAILED
```

`READY_FOR_MAPPING` 只表示原文件结构已经安全保存，可以进入字段映射；不表示数据已经进入商品、库存或订单表。

每个原始数据行初始为 `UNREVIEWED`。完成映射后会变为 `MATCHED`、`READY_TO_CREATE`、`NEEDS_REVIEW` 或 `INVALID`。多工作表文件只有在每张表都完成映射后，任务才进入 `READY_FOR_REVIEW`。

## 字段映射

源列序号从 `0` 开始。任务详情返回的 `version` 必须作为 `expectedVersion` 传回，避免两个操作人或两个页面相互覆盖。

```json
{
  "expectedVersion": 1,
  "mappings": [
    { "sourceColumnIndex": 0, "targetField": "BARCODE" },
    { "sourceColumnIndex": 1, "targetField": "PRODUCT_NAME" },
    { "sourceColumnIndex": 2, "targetField": "SPEC" },
    { "sourceColumnIndex": 3, "targetField": "UNIT_PRICE" }
  ]
}
```

可用标准字段：`PRODUCT_NAME`、`BARCODE`、`SPEC`、`UNIT`、`QUANTITY`、`UNIT_PRICE`、`AMOUNT`、`REMARK`、`SUPPLIER_SKU`、`CUSTOMER_SKU`、`IGNORE`。

用途校验规则：

- 所有任务至少映射商品名称或条码。
- `SUPPLIER_PRICE` 必须映射单价。
- `INVENTORY_COUNT` 和 `PURCHASE_RECEIPT` 必须映射数量。
- `PRODUCT_IMPORT` 必须映射商品名称。

## 匹配与预览规则

- 条码精确命中时直接匹配。
- 没有条码时，商品名称以及可选规格精确且唯一才自动匹配。
- 条码未命中、同名多商品或只找到包含关系时，返回最多 10 个候选并标记 `NEEDS_REVIEW`。
- 商品导入没有任何候选时标记 `READY_TO_CREATE`，只生成 `CREATE_PRODUCT` 预览。
- 无效数字、负数或盘点数量不是整数时标记 `INVALID`。

`review-rows` 同时返回 `normalized`、`candidates`、`before`、`after`、`issues` 和 `actionType`。这些字段来自暂存结果，当前阶段不会修改 `product`、`inventory` 或订单表。

每行还会返回 `id` 和 `sourceRowId`。修改审核结果时使用结果记录的 `id`，不要使用原始行 ID。

## 人工审核

```json
{
  "expectedVersion": 2,
  "decision": "SELECT_PRODUCT",
  "productId": 18,
  "reason": "与采购员确认规格一致"
}
```

支持以下决定：

- `SELECT_PRODUCT`：人工选择现有商品；数据本身仍有错误时不能选择。
- `APPROVE_CREATE`：批准创建新商品，仅限 `PRODUCT_IMPORT`，且名称和条码必须完整。
- `EXCLUDE`：本次提交跳过该行，必须填写原因。重新执行工作表映射可恢复该行。

审核操作会增加任务 `version`。前端每次操作后应重新读取任务详情或校验结果，避免继续使用旧版本。

## 整单校验与提交

校验请求：

```json
{ "expectedVersion": 3 }
```

校验会一次性返回未映射工作表、待人工确认行、无效行、新商品缺少条码和不支持的任务用途等阻塞原因。

提交请求：

```json
{
  "expectedVersion": 3,
  "idempotencyKey": "excel-task-42-submit-20260930"
}
```

相同操作人使用同一幂等键重试同一任务时，返回第一次提交结果，不会重复写入。任务提交时会锁定任务记录，并重新比较预览时的进价或库存与当前数据库值；如果业务数据已经变化，返回 HTTP 409，要求重新生成预览。

当前正式提交支持：

- `SUPPLIER_PRICE`：更新匹配商品的进价。
- `PRODUCT_IMPORT`：更新匹配商品，或创建已批准的新商品及初始库存记录。
- `INVENTORY_COUNT`：把库存调整为表格中的实际数量，同时同步商品库存并写库存日志。
- `PURCHASE_RECEIPT`：把表格数量累加到现有库存，同时同步商品库存并写库存日志。

`BUYER_QUOTE` 和 `UNCLASSIFIED` 暂时只支持审核预览。报价单数据模型完成前不能提交，避免把买家需求错误写成商品或库存变更。

## CloudBase 请求示例

```json
{
  "cloudFileId": "cloud://环境.桶/路径/买家需求.xlsx",
  "downloadUrl": "https://受信的临时下载地址",
  "originalName": "买家需求.xlsx",
  "fileSize": 28314,
  "purpose": "BUYER_QUOTE"
}
```

后端仍会校验文件大小、扩展名和文件头，不能仅相信客户端传入的 MIME 类型。

## 后续能力

下一阶段可补充行内标准值修正、映射模板复用、买家报价单生成、厂家价格版本对比和 Excel 结果导出。
