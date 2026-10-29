# Hardware-Store-Management-System

恒迪五金管理系统，使用微信小程序、Spring Boot 与 MySQL，提供小五经营助手、快捷开单、工厂账本、商品与库存、进货和文件处理。

> 文档更新：2026-10-29。当前业务及文件连接自有服务器，CloudBase 不再是小程序运行依赖。源码版本以 Git 记录为准；开发版 1.0.20261029 已提审，不能据此推断正式版已更新。

## 两人协作，从这里开始

- 前端同学先读 [前端接手指南](docs/collaboration/FRONTEND_HANDOFF.md)，导入 `miniprogram`，无需启动 Java 或安装前端打包框架。
- 接口与状态约定见 [联调接口约定](docs/collaboration/API_CONTRACT.md)，分工和提交约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安装 Node.js 22.8+（本机 Node 版本以 `node --version` 为准），根目录执行 `npm run verify`。此项目检查脚本无 npm 依赖，无需 `npm install`。
- `npm run smoke:live` 用环境变量中的专用联调账号检查云端，详见接手指南。不要把正式管理员密码发到群聊、提交到 Git 或填进前端源码。
- 本轮测试结果和已知问题见 [验证记录](docs/collaboration/VERIFICATION-20261029.md)。真实识别存在错误，前端必须保留人工核对流程。
- 可执行 `python tools/package-handoff.py` 生成不含密钥、依赖缓存和私人配置的交接 ZIP；这是当前工作区快照，不含 Git 历史。

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

本地脚本和正式配置均不自动导入演示数据。仅在独立的空开发库中显式设置 `SPRING_SQL_INIT_DATA_LOCATIONS=classpath:demo-data.sql` 才加载演示数据。测试配置使用独立 `hardware_store_test`；不可指向正式数据库。

跨电脑后端启动建议安装 Java 17+、Maven 3.9.x 和 MySQL 8，设置 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`，然后在 `springboot-demo` 执行 `mvn spring-boot:run`。`mvnw` / `mvnw.cmd` 只是调用已安装 Maven 的兼容入口，不会下载 Maven。原有 `run-dev.ps1` 依赖本机 Maven 缓存，优先供现有开发机使用。

## 小程序与部署

微信开发者工具导入 `miniprogram`。普通接口使用 `wx.request`，图片和附件使用 `wx.uploadFile`，目标为 `https://hardware.plain-cookie.com/api`；配置入口为 `miniprogram/config/cloud.js`。`cloud.js` 是历史文件名。

当前请求层不回退到模拟业务数据。运行本地后端不会自动切换小程序请求目标；联调前确认目标服务器与账号。保持合法域名校验开启。

已有数据库升级请查看 [当前实现说明中的部署与数据迁移](docs/当前实现与前后端修正说明.md#部署与数据迁移)。本轮仅同步文档，未执行数据库迁移或发布。
