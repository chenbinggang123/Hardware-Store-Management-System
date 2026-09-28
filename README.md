# Hardware-Store-Management-System

当前仓库中的后端位于 [springboot-demo](/F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo)，这一版先按 `docs` 的商品管理模块进行开发联调，默认使用内存存储启动，方便在没有 MySQL 的情况下直接运行接口。

启动方式：

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

如果 `8084` 端口已被占用，可以直接指定别的端口：

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1 -Port 8085
```

编译校验：

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1 -CompileOnly
```

如果你已经进入 [springboot-demo](/F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo) 目录，也可以直接执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

默认地址：

- 接口根路径：`http://localhost:8084/api`
- 商品列表：`GET http://localhost:8084/api/products`
- 商品搜索：`GET http://localhost:8084/api/products?keyword=电钻&status=1`

公网部署地址：

- HTTPS 接口根路径：`https://api.hardware1122.xin/api`
- 微信小程序后台服务器域名：`https://api.hardware1122.xin`

## Agent 方向设计

项目正在规划面向个体经营者的五金店经营 Agent。当前设计阶段的产品、Harness、数据接口和 MVP 开发计划统一维护在 [docs/agent/README.md](docs/agent/README.md)。
