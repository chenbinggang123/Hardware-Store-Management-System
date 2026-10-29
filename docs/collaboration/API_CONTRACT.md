# 小程序联调接口约定

这是一份常用链路导航，不是完整OpenAPI。最终字段以 `miniprogram/utils/api.js`、后端Controller/DTO和对应测试为准。所有下列路径均追加在 `/api` 后，线上根路径 `https://hardware.plain-cookie.com/api`。

## 通用协议

JSON请求使用 `Content-Type: application/json`，登录后加 `Authorization: Bearer <token>`。GET参数放query，POST/PUT参数放JSON；文件上传是multipart，不手工填写boundary。

```json
{"success":true,"message":"OK","data":{},"timestamp":"..."}
```

`utils/api.js` 已解包data并处理success=false，不要在页面二次解包。字段可能为null或因non_null配置省略。二进制图片/文件不是此JSON结构。

非登录接口401会清除会话并回登录页；403表示无权限；400等业务错误应展示服务端message并保留输入。不要将网络失败伪装成空列表或成功。最终状态码以接口实际响应为准，不能只检查HTTP200。

## 常用链路

| 模块 | 方法与路径 | 前端入口/注意事项 |
|---|---|---|
| 登录 | POST `/auth/login` | body `{username,password}`；data含token、id、username、role等扁平字段；api.login映射为本机会话user |
| 当前用户/退出 | GET `/auth/me`、POST `/auth/logout` | Token不可拼在URL中 |
| 商品目录 | GET `/catalog`、`/catalog/suppliers` | 目录返回items及分页统计，不是普通数组 |
| 商品与库存 | GET `/products`、`/inventories` | 旧商品列表与目录返回形态不同 |
| 客户/供应商/进货 | GET `/customers`、`/suppliers`、`/purchase-orders` | 对应api.js同名方法；保存/确认动作要单独验收 |
| 开单记录 | GET `/billing/records` | 分页items/page/totalPages/total；筛选由服务端执行 |
| 草稿 | POST `/billing/documents`、GET/PUT `/billing/documents/{id}` | 内容包括documentType、date、customerName、lines等；GET返回id/version/status/content/total |
| 确认 | POST `/billing/documents/{id}/confirm` | body `{version}`，必须使用最新版本；不要重复提交 |
| 账本关联 | GET/POST `/billing/documents/{id}/ledger` | POST可带ledgerId；不能仅凭确认状态标记已入账 |
| 作废/重开 | POST `/billing/documents/{id}/void`、`/reopen` | 按后端规则操作，作废可带reason |
| 工厂账本 | GET `/factory-ledgers`、`/factory-ledgers/{id}` | GET可带month；列表为month/ledgers/totals |
| 汇总 | GET `/reports/monthly-summary` | query可带month，返回billing/ledger/factories |
| 附件上传 | POST `/agent/attachments` | multipart的file；可传originalName、conversationId、saveToFiles、category；返回id/conversationId/parseStatus/errorMessage |
| 拍照开单 | POST `/billing/recognitions` | body `{attachmentId,documentType}`；按当前页面轮询run，不要把接收任务当识别完成 |
| 对话 | POST `/agent/messages`或`/messages/stream` | body `{content,conversationId,attachmentIds}`；stream入口返回任务JSON，非浏览器EventSource协议 |
| 任务 | GET `/agent/runs/{runId}` | status/output/pendingAction/files；有pendingAction需用户确认 |
| 私有原图 | GET `/agent/attachments/{id}/image` | Bearer鉴权后二进制；本人附件或已入账共享原图按服务端权限读取 |
| 商品图 | POST `/media/product-images` | multipart上传；server://只是引用标识，不是公开URL |
| Excel任务 | POST/GET `/excel-tasks` | multipart上传，保留原文件名与purpose；详细字段见下述文档 |
| Excel下载 | GET `/excel-tasks/{taskId}/outputs/{outputId}/download` | 鉴权下载，完成后再交给wx.openDocument |
| 设置 | `/settings/*` | 只允许ADMIN；CLERK不可管理用户或查看操作日志 |

商品图限制3MB并检查文件头，Agent附件30MB，Excel文件10MB；支持格式以具体上传入口为准。私有附件、Excel任务和对话的归属由后端核对；不要在前端自行放宽。

## 延伸阅读

- [Excel任务详细协议](../agent/08-Excel文件任务后端接口.md)：文档含旧cloud上传兼容端点，新小程序使用multipart直传。
- [当前业务规则](../当前实现与前后端修正说明.md)：较早实现记录；与2026-10-29迁移说明冲突时按当前源码和本目录约定核对。
- [小程序配置](../../miniprogram/README.md)。
