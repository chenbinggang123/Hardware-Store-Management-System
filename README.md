# Hardware-Store-Management-System

当前仓库中的后端位于 [springboot-demo](/F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo)，这一版先按 `docs` 的商品管理模块进行开发联调，默认使用内存存储启动，方便在没有 MySQL 的情况下直接运行接口。

启动方式：

```powershell
cd .\springboot-demo
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

如果 `8080` 端口已被占用，可以直接指定别的端口：

```powershell
cd .\springboot-demo
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1 -Port 8081
```

编译校验：

```powershell
cd .\springboot-demo
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1 -CompileOnly
```

默认地址：

- 接口根路径：`http://localhost:8080/api`
- 商品列表：`GET http://localhost:8080/api/products`
- 商品搜索：`GET http://localhost:8080/api/products?keyword=电钻&status=1`
