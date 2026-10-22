# Hardware-Store-Management-System

恒迪五金管理系统，使用微信小程序、Spring Boot 与 MySQL，提供小五经营助手、快捷开单、工厂账本、商品与库存、进货和文件处理。

> 文档更新：2026-10-21。说明依据当前工作区代码（包含尚未提交的前后端修正），不代表线上版本已发布或完成真机验收。

## 文档入口

- [当前实现与前后端修正说明](docs/当前实现与前后端修正说明.md)：当前功能、接口、数据规则、迁移和验收清单。
- [小程序开发说明](miniprogram/README.md)：页面入口和联调方式。
- [Agent 文档索引](docs/agent/README.md)：小五架构、文件任务、审批与开发记录。
- [数据库结构](springboot-demo/src/main/resources/schema.sql)、[数据安全设计](docs/数据安全设计.md)：当前表结构与安全设计参考，字段同时参照 JPA 实体和迁移脚本。

## 当前业务入口

日常使用集中在小五、快捷开单、开单记录、工厂账本、商品（含库存）、进货和文件。月度汇总从工厂账本进入；设置保留往来账、联系人、价格历史及批量导入等管理工具。旧销售单的前端列表、详情、表单与页面注册已移除，历史数据和后端兼容接口保留。

快捷开单与旧销售订单是不同业务链路：单据确认、导出、记入工厂账本分别执行；记入账本采用 `BILLING` 来源，避免同一单据重复记账。具体规则见当前实现说明。

## 目录

| 目录 | 用途 |
| --- | --- |
| `miniprogram` | 微信小程序页面、公共请求与云环境配置 |
| `springboot-demo` | Spring Boot 接口、Agent、业务服务与数据库结构 |
| `docs` | 业务设计、当前实现、部署和迁移文档 |
| `tools` | OCR 服务与开发工具 |

## 本地后端

默认启用 `mysql` 配置，需要可连接的 MySQL；已不适用早期“无数据库内存启动”的说明。数据库配置参见 `springboot-demo/src/main/resources/application-mysql.properties`，通过环境变量配置连接信息，勿把生产凭据写入文档。

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
# 改端口
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1 -Port 8085
# 仅编译
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1 -CompileOnly
```

也可在 `springboot-demo` 目录调用其中的 `run-dev.ps1`。默认接口根路径为 `http://localhost:8084/api`。

本地脚本在 `DB_HOST` 为空或为 `127.0.0.1` / `localhost`，且未显式设置 `SPRING_SQL_INIT_DATA_LOCATIONS` 时加载 `demo-data.sql`。通用 MySQL 配置不默认灌入演示数据；远程库部署须检查环境变量，避免演示数据覆盖真实记录。

## 小程序与部署

微信开发者工具导入 `miniprogram`。普通接口通过 `wx.cloud.callContainer` 调用 CloudBase 环境 `cloud1-d0gvllpf639d10665` 的 `hardware-store-api` 服务，附件通过云存储上传；配置入口为 `miniprogram/config/cloud.js`。

当前请求层不再回退到内置模拟业务数据。运行本地后端并不会自动切换小程序请求目标；联调须确认所连接的云托管服务和版本。

已有数据库升级请查看 [当前实现说明中的部署与数据迁移](docs/当前实现与前后端修正说明.md#部署与数据迁移)。本轮仅同步文档，未执行数据库迁移或发布。
