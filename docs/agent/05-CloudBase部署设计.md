# CloudBase 云托管部署设计

## 1. 目标架构

```text
微信小程序
  → wx.cloud.callContainer
CloudBase 云托管服务 hardware-api
  → Spring Boot /api
CloudBase MySQL
  → 业务数据、Agent Run、工具轨迹
模型服务
  → 仅由 Spring Boot 服务端调用
```

小程序不保存数据库密码或模型密钥。模型 API Key、MySQL 密码只配置在云托管服务的环境变量中。

## 2. 创建资源

1. 在微信开发者工具中开通云开发环境。
2. 创建云托管服务，建议命名 `hardware-api`。
3. 构建目录选择 `springboot-demo`，使用目录中的 `Dockerfile`。
4. 服务监听端口填写 `80`。
5. 创建 CloudBase MySQL，并执行 `src/main/resources/schema.sql` 和 `data.sql`。
6. 首期实例数设为 1；验证稳定后再评估自动扩缩容。

## 3. 云托管环境变量

```text
APP_PROFILE=mysql
SERVER_PORT=80
DB_HOST=CloudBase MySQL 内网地址
DB_PORT=3306
DB_NAME=hardware_store
DB_USERNAME=数据库用户
DB_PASSWORD=数据库密码

AGENT_MODEL_ENABLED=true
AGENT_MODEL_BASE_URL=模型服务的 OpenAI-compatible /v1 地址
AGENT_MODEL_API_KEY=模型密钥
AGENT_MODEL_NAME=模型名称
AGENT_MODEL_TIMEOUT_SECONDS=20
```

不得把真实密码和模型密钥提交到 Git。

## 4. 小程序配置

编辑 `miniprogram/config/cloud.js`：

```js
module.exports = {
  envId: '云开发环境 ID',
  serviceName: 'hardware-api',
  apiPrefix: '/api'
}
```

配置留空时，经营助手会明确提示尚未配置，不会回落到旧公网域名。

## 5. 发布前检查

- `/api/hello` 能通过云托管访问。
- 登录、商品查询和库存查询正常。
- Agent 模型密钥只存在于服务端。
- 创建草稿必须出现 `R1_DRAFT` 确认卡。
- 正式订单必须出现 `R2_WRITE` 二次确认卡。
- 重复点击确认不会重复创建订单。
- MySQL 已配置自动备份。
- 日志中不输出 Authorization、数据库密码或模型密钥。

## 6. 当前上线限制

现有登录 Token 保存在单个 Java 进程内存中。云托管重启会要求用户重新登录，多实例扩容还可能出现实例间登录态不一致。因此 MVP 阶段应固定单实例；正式扩容前必须把登录态改为数据库、Redis 或签名 Token。

现有账号密码比较仍是明文逻辑。正式邀请真实用户前，必须完成密码哈希迁移、登录限流和默认账号清理。

## 7. 域名策略

小程序统一使用 CloudBase：业务接口通过 `wx.cloud.callContainer` 调用云托管，附件通过 `wx.cloud.uploadFile` 写入云存储，不依赖自定义域名。若未来增加浏览器或第三方系统访问，再单独评估是否开启公网入口。
