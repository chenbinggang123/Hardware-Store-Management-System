# 五金店管理系统阿里云 RDS 搭建与接入

## 一、这份文档解决什么问题

本文档用于把当前项目接到阿里云 RDS MySQL。

你可以把整个过程理解成 4 步：

1. 在阿里云控制台创建一台 MySQL 云数据库
2. 为它创建数据库、账号和白名单
3. 用项目脚本把当前表结构和初始化数据导入进去
4. 让本地 Spring Boot 应用改连这台云数据库

## 二、为什么先选阿里云 RDS

当前我先按阿里云 RDS MySQL 路线准备，原因是：

- 你现在是第一次接触云数据库，阿里云官方入门文档比较完整
- 阿里云官方文档明确提供了创建实例、创建数据库、创建高权限账号、命令行连接的完整路径
- 现有项目已经是标准 MySQL 接入方式，迁移到阿里云 RDS MySQL 成本最低

根据阿里云官方帮助文档：

- 创建流程是“购买 RDS MySQL 实例 -> 在实例内创建数据库 -> 创建高权限账号”  
  来源：阿里云《第一步：创建RDS MySQL实例与配置数据库》  
  https://help.aliyun.com/zh/rds/apsaradb-rds-for-mysql/step-1-create-an-apsaradb-rds-for-mysql-instance-and-configure-databases
- 通过命令行或客户端连接 RDS 前，需要先创建账号并设置 IP 白名单  
  来源：阿里云《通过命令行、客户端连接RDS MySQL实例》  
  https://help.aliyun.com/zh/rds/apsaradb-rds-for-mysql/use-a-client-or-cli-to-connect-to-an-apsaradb-rds-for-mysql-instance

## 三、云控制台里你需要做的事

### 3.1 创建 RDS MySQL 实例

建议第一次先这样选：

- 引擎版本：`MySQL 8.0`
- 计费方式：先用 `按量付费`
- 实例系列：先选 `高可用系列`
- 地域：优先选离你近的地域
- 存储：先从小规格起步

为什么这样选：

- `MySQL 8.0` 和当前项目兼容性最好
- 按量付费适合第一次试用，避免一上来就锁长期费用
- 高可用系列比单节点更接近正式生产形态

阿里云官方文档提到：

- 短期使用建议按量付费
- 无版本特殊要求时建议选择 MySQL 8.0
- 体验或短期试用时可选高可用系列

参考：

- [阿里云创建 RDS MySQL 实例官方文档](https://help.aliyun.com/zh/rds/apsaradb-rds-for-mysql/step-1-create-an-apsaradb-rds-for-mysql-instance-and-configure-databases)

### 3.2 创建业务数据库

实例创建完成后，在控制台里创建数据库：

- 数据库名建议：`hardware_store`
- 字符集建议：`utf8mb4`

这个数据库名要和我们项目配置保持一致。

### 3.3 创建数据库账号

建议先创建一个高权限账号，例如：

- 用户名：`hardware_admin`
- 密码：你自己设置一个强密码

阿里云 RDS MySQL 不支持 `root` 账号，官方文档说明每个实例只允许创建一个高权限账号。

### 3.4 配置白名单

这是新手最容易卡住的一步。

如果你从本地电脑连接阿里云 RDS，需要把你当前电脑的公网 IP 加到 RDS 白名单里。

阿里云官方文档说明：

- 连接前必须先设置 IP 白名单
- 如果从本地设备连接，建议先查询本机公网 IP 再加入白名单

常见现象：

- 实例明明创建好了，但本地连不上
- 大多数时候不是账号密码错，而是白名单没配对

## 四、项目里我已经帮你准备好的文件

### 4.1 RDS 配置模板

文件：

- [rds.config.example.json](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/scripts/rds/rds.config.example.json)

这是你要先复制再修改的文件。

建议复制成：

```powershell
Copy-Item `
  springboot-demo/scripts/rds/rds.config.example.json `
  springboot-demo/scripts/rds/rds.config.json
```

然后修改下面几个值：

- `connection.host`
- `connection.port`
- `connection.database`
- `connection.username`
- `connection.password`

### 4.2 测试连接脚本

文件：

- [connect-rds.ps1](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/scripts/rds/connect-rds.ps1)

作用：

- 检查你本地能不能连上阿里云 RDS
- 验证主机、端口、账号、密码、白名单是否正确

执行方式：

```powershell
cd springboot-demo
.\scripts\rds\connect-rds.ps1 -ConfigPath "scripts/rds/rds.config.json"
```

如果成功，它会返回：

- 连接的主机
- 连接的端口
- 当前数据库
- MySQL 版本

### 4.3 初始化数据库脚本

文件：

- [init-rds.ps1](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/scripts/rds/init-rds.ps1)

作用：

- 把当前项目的 `schema.sql` 和 `data.sql` 导入到阿里云 RDS

它内部复用了现有的：

- [init-db.ps1](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/init-db.ps1)

执行方式：

```powershell
cd springboot-demo
.\scripts\rds\init-rds.ps1 -ConfigPath "scripts/rds/rds.config.json"
```

### 4.4 让应用连接 RDS 的启动脚本

文件：

- [run-rds.ps1](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/run-rds.ps1)

作用：

- 设置 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`
- 再调用现有 [run-dev.ps1](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/run-dev.ps1)
- 让 Spring Boot 直接连接阿里云 RDS

执行方式：

```powershell
cd springboot-demo
.\run-rds.ps1 -ConfigPath "scripts/rds/rds.config.json"
```

## 五、推荐上云顺序

第一次建议严格按这个顺序走：

1. 在阿里云控制台创建 RDS 实例
2. 创建数据库 `hardware_store`
3. 创建高权限账号
4. 配置你当前电脑公网 IP 白名单
5. 复制并填写 `rds.config.json`
6. 执行 `connect-rds.ps1`
7. 执行 `init-rds.ps1`
8. 执行 `run-rds.ps1`

这个顺序很重要，因为：

- 先测连接，可以尽早发现白名单问题
- 先导入库表，应用启动时才不会缺表
- 启动应用放最后，排错最容易

## 六、第一次最可能遇到的 4 个问题

### 6.1 连不上实例

优先检查：

- 白名单
- 外网地址是否已开通
- 主机名和端口是否填错

### 6.2 账号能登录控制台，但脚本连不上

这是两个不同层面的账号：

- 阿里云控制台账号
- RDS 数据库账号

脚本里用的是数据库账号，不是阿里云网页登录账号。

### 6.3 导入失败

优先检查：

- 数据库是否已经创建
- 数据库账号是否有权限
- `mysql.exe` 是否可用

### 6.4 应用启动后报数据库连接失败

优先检查：

- `rds.config.json` 中的主机、端口、库名、用户名、密码
- 白名单是否仍然正确
- 是否把本地和云库配置混用了

## 七、你现在离“真正上云”还差什么

代码和脚本层面，我已经把项目侧准备好了。

你现在还需要自己在阿里云控制台完成的信息只有这些：

- 阿里云 RDS 实例地址
- 端口
- 数据库名
- 数据库账号
- 数据库密码
- 当前本机公网 IP 白名单

只要你拿到这 6 项，我们就可以把项目真正接到云数据库。

## 八、我建议你下一步怎么做

最顺的路径是：

1. 你先去阿里云控制台创建 RDS MySQL 8.0 实例
2. 把实例地址、端口、数据库名、账号名发给我
3. 密码你自己保留，不需要发我
4. 我再带你把 [rds.config.json](F:/Hardware-Store-Management-System/Hardware-Store-Management-System/springboot-demo/scripts/rds/rds.config.example.json) 填好
5. 然后我们一起执行连接测试和初始化

这样推进最稳，也最适合第一次上云。
