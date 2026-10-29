# 前端接手指南

更新时间：2026-10-29。现有前端是原生微信小程序：JS / WXML / WXSS / JSON，不是 Vue、React 或 uni-app。根目录 package.json 只运行检查，不负责打包页面。

## 第一次打开

1. 安装并登录微信开发者工具，由项目管理员添加你为该小程序开发者或体验成员。
2. 导入仓库内 `miniprogram`，AppID 为 `wx6e38f6c9a4635691`。不要把外层工作区或整个仓库根目录当作小程序目录。
3. 保持合法域名校验开启。request 与 uploadFile 域名均为 `https://hardware.plain-cookie.com`；控制台配置是否生效仍需真机确认。
4. 向后端负责人领取专用联调账号。不要使用微信审核账号或默认演示账号，不把密码写进代码。进入后默认打开小五页面。
5. 根目录运行 `npm run verify`，然后在开发者工具编译、预览并用真机检查。没有新增 npm 库时无需“构建 npm”。

## 代码地图

| 范围 | 入口 | 职责 |
|---|---|---|
| 页面注册 | `miniprogram/app.json` | 当前45个注册页面；磁盘还有未注册旧页面，不能按目录数量判断入口 |
| 会话 | `app.js`、`pages/login/index.js` | 登录、恢复、退出与页面鉴权 |
| 服务器配置 | `config/cloud.js` | baseURL与/api前缀；历史文件名，不是CloudBase配置 |
| 公共接口 | `utils/api.js` | JSON、Bearer、上传、业务响应解包、401退出 |
| 图片 | `utils/source-photo.js` | 带鉴权请求读取原图，写为本机临时文件，预览与裁剪 |
| 小五与文件 | `pages/agent`、`agent-files`、`file-detail` | 对话、轮询任务、审批、附件 |
| 开单 | `pages/billing-editor`、`billing-list` | 草稿恢复、拍照识别、核对、保存确认 |
| 商品库存 | `pages/products`、`product-detail`、`product-form` | 目录、供应商筛选、库存修正 |
| 账本 | `pages/factory-ledgers`及同前缀页面 | 工厂流水、冲销、结算；月度汇总在账本内 |
| Excel | `pages/excel-*`、`workbook-editor`、`price-history` | 上传、字段映射、复核、生成与下载 |
| 样式 | `app.wxss`、`theme.wxss`及各页WXSS | 全局风格与页面布局 |

## 需要守住的业务语义

- 快捷开单与历史销售订单是两条链路；主要开单记录使用 `/billing/records`，不要重新挂回旧销售单页面。
- 原生字段 `documentType=PURCHASE` 表示客户拿货/销售单，`PROCUREMENT` 表示本店进货，`RETURN` 表示客户退货。不要按英文直译修改字段。
- 草稿、确认、导出、记入账本是不同动作。确认成功不代表已记账，生成草稿不代表生成了Excel。
- 无库存记录显示“未记录”，不能转换成确定的0库存。数量、单位和单价缺失不得当0补齐。
- OCR结果必须可编辑，原文与原图可核对；识别有错误，不展示“全部正确”或直接触发入账。
- 已记账单据的删除、重开与作废受后端约束，前端隐藏按钮不能代替后端鉴权。

## 联调与自检

`npm run check` 检查所有JS/JSON和注册页面必要文件；`npm test`运行42项现有回归。两者不能替代微信编译、页面点击、真机权限和相册/文件打开验证。

云端查询冒烟：设置 `HARDWARE_USERNAME` 和 `HARDWARE_PASSWORD`，执行 `npm run smoke:live`。可用 `HARDWARE_API_URL` 指定其他HTTPS API根路径；脚本不写业务数据，但登录退出会写审计记录。不要将账号环境变量提交到Git。

后端本地开发需Java 17+、Maven 3.9.x和MySQL 8。设好DB环境变量，在 `springboot-demo` 执行 `mvn spring-boot:run`；默认8084/api。若本地测试需演示数据，只对独立空库显式加载demo。模型和OCR配置参考 `agent.local.env.example`；前端同学连接云端时不需要这些密钥。

## 优先交接任务

先做真机登录、会话恢复和失败状态验收，再做商品/开单/账本页面交互。OCR错误、字段归属和合计不完整属于前后端共同问题：前端负责清晰标记与人工核对，后端负责识别及确定性校验。详见本目录验证记录。
