# 可视化模型驱动低代码开发平台（Low-Code Platform v2.1.0）

基于 **Java 17 + Spring Boot 2.7 + SQLite + Groovy + Vue 3 / Element Plus** 构建的**可视化模型驱动低代码开发平台**。后端严格遵循 **DDD（领域驱动设计）** 四层架构，前端具备独立统一登录页、通俗易懂的可视化管理后台与纯元视图动态渲染能力。

本仓库为**纯通用低代码底座实现**，首次启动仅初始化平台基础底座、默认根租户与内置权限角色，**不包含任何硬编码业务示例**。系统管理员可通过 JSON 应用包或在线设计台定义数据表、字段校验、编号规则与状态流转动作，平台自动完成建表改表、动态接口生成与页面渲染，并支持按**多租户**隔离数据与**应用授权登录控制**。

---

## 项目结构

```text
.
├── README.md                         # 项目说明与部署指南
├── docs/                             # 架构设计文档（SRS / DDD / 状态机 / JSON-RPC 契约）
├── schemas/app-package-schema.json   # 应用配置包 JSON Schema 规范
└── lowcode-platform/                 # 核心源码工程（Maven + Spring Boot + Vue 3 SPA）
    ├── pom.xml                       # Maven 构建与依赖配置（Java 17）
    ├── Dockerfile                    # Docker 镜像构建文件
    ├── docker-compose.yml            # Docker Compose 编排配置（默认端口 8088）
    └── src/
        ├── main/java/com/sie/lowcode/
        │   ├── interfaces/           # 统一 JSON-RPC 2.0 网关、4环责任链与文件上传下载接口
        │   ├── application/          # 应用编排（模型治理、UI渲染、字典编码、多租户IAM、API文档）
        │   ├── domain/               # 领域模型、继承与影响分析、RecordSet、Filter DSL、验证钩子
        │   └── infrastructure/       # 动态 SQL/DDL 引擎、SQLite 仓储、Groovy 执行器、内存容器
        ├── main/resources/
        │   ├── application.properties
        │   └── static/index.html     # 独立登录页 + 管理后台 + 动态应用使用端前端单页应用
        └── test/java/...             # 8 项端到端集成测试
```

---

## 核心特性

1. **独立安全登录与多租户应用授权管控**：
   - 未登录默认展示简洁登录页面，无需手动选择入口；管理员登录后默认拥有全部应用及管理中心权限，企业租户登录后直接展示全部已授权的应用列表；
   - **租户隔离与登录拦截**：不同租户的业务数据按 `tenant_id` 自动隔离；非系统管理员账号登录后仅能访问所属租户被管理员授权的应用，尝试登录管理后台或未授权应用将被登录门禁与网关双重拦截；
   - **系统管理员集中授权**：管理员可在管理后台「租户与应用授权」创建企业租户、勾选分配可登录应用、开设租户账号及重置密码。
2. **模型驱动零代码构建（可视化设计 + JSON 应用包）**：
   - 支持单行文本、多行文本、整数/小数、布尔、日期/时间、固定选项、动态数据字典（`@Dict`）、`ManyToOne` / `OneToMany` / `ManyToMany` 关联关系、文件附件、自动流水编号（`codeRule`）、`related` 关联带出与 `computeScript` 计算字段；
   - 支持模板表复用（`abstractModel` / `parents`）、非侵入式表扩展（`extendModel`）、防重复联合索引及 `rs.callSuper(...)`、`validate` 保存前钩子与服务端 `JsonRpcServiceResponse` UI 指令反馈。
3. **版本安全演进与受控安全清理**：
   - 发布前自动进行影响分析与引用扫描，阻断破坏性删字段并支持 `display=false` 兼容退役与无损版本回滚；
   - 数据表下线与彻底销毁由系统管理员集中审批管控，须严格满足保留期、备份导出凭据、零引用及零业务数据守卫，并永久记录清理审计台账。

---

## 完整部署指南

> **端口约定**：平台默认监听 **`8088`** 端口（避开 `8080`、`80`、`9000`、`9001`、`6379`、`5432` 常见占用端口）。

### 环境前提

| 工具 | 最低版本要求 | 说明 |
| :--- | :--- | :--- |
| **JDK** | `17+` | 运行与编译 Java 后端 |
| **Maven** | `3.8+` | 构建项目并运行集成测试（生成 Fat JAR） |
| **Docker & Docker Compose** | Docker Engine 20+ / Compose v2+ | 方式一容器化一键部署需要 |

### 0. 获取代码

```bash
git clone https://github.com/wonderful-sf/mes.git
cd mes/lowcode-platform
```

---

### 方式一：Docker Compose 容器化部署（推荐）

> **重要说明**：`Dockerfile` 会打包复制 `target/lowcode-platform-2.0.0.jar`，因此构建镜像前**必须先执行 `mvn clean package`** 编译出 JAR 包。

#### 1）编译打包并运行全量集成测试

```bash
mvn clean package
```

> 如需跳过测试快速打包，可执行 `mvn clean package -DskipTests`。

#### 2）启动容器（后台运行，数据自动持久化）

```bash
docker compose up -d --build
```

- 默认将宿主机 `lowcode-platform/data/` 目录挂载到容器内 `/app/data`，持久化保存 SQLite 数据库（`lowcode.db`）和用户上传的附件（`uploads/`）。
- 端口映射为宿主机 **`8088`** -> 容器内 **`8088`**。

#### 3）常用运维命令

```bash
# 查看启动日志（看到 "Started LowCodeApplication" 即表示就绪）
docker logs -f lowcode-platform-app

# 停止并移除容器（保留 data/ 内的数据）
docker compose down

# 如需清空数据恢复全新初始库
docker compose down
rm -rf ./data
docker compose up -d
```

---

### 方式二：本机直接运行（无需 Docker）

#### 选项 A：使用 Maven 直接启动开发模式

```bash
cd lowcode-platform
mvn spring-boot:run
```

#### 选项 B：先打包再通过 `java -jar` 运行可执行 JAR

```bash
cd lowcode-platform
mvn clean package
java -jar target/lowcode-platform-2.0.0.jar
```

- 默认会在当前工作目录下自动创建 `./data/lowcode.db` 与 `./data/uploads/`。
- 如需自定义端口或数据库路径，可通过命令行参数覆盖：

```bash
java -Dserver.port=8089 -DDB_PATH=./my-data/lowcode.db -jar target/lowcode-platform-2.0.0.jar
```

---

## 访问验证与首次使用指南

部署启动完成后，在浏览器打开：

- **平台访问地址（统一登录页）**：http://localhost:8088/
- **健康检查接口**：`GET http://localhost:8088/api/health`（返回 `{"status":"UP", ...}`）
- **统一 JSON-RPC 2.0 网关**：`POST http://localhost:8088/rbac_tenant_root/rpc/service`

### 1. 内置体验账号（默认密码均为 `123456`）

| 用户名 | 密码 | 角色定位 | 典型用途 |
| :--- | :--- | :--- | :--- |
| `admin` | `123456` | 统一系统管理员（全量管理 + 建模发布 + 安全清理） | 管理企业租户、分配应用授权、维护账号权限、设计/发布数据表及审批彻底销毁，可登录「管理中心」与全部应用 |
| `user` | `123456` | 示例业务用户（根租户普通操作员） | 仅进入「应用使用端」操作已开放的单据与页面，无权进入「管理中心」 |

### 2. 多租户与授权登录典型体验流程

1. 打开 http://localhost:8088/ ，输入 `admin` / `123456`（也可点击右下角「系统初始账号说明」一键填入）登录进入「管理中心」。
2. 在 **「应用管理与导入导出」** 中填入空白模板或自定义 JSON 应用包，点击「校验并导入」（默认自动上线生效）。
3. 切换至 **「租户与应用授权」**：
   - 点击 **「+ 新增租户」** 创建新租户；
   - 点击该租户行上的 **「管理应用授权」**，仅勾选允许该租户访问的应用并保存；
   - 点击 **「分配账号」** 为该租户创建业务账号（如 `ROLE_APP_USER`）。
4. 点击右上角头像 **「退出登录」**，使用新创建的租户账号直接登录：
   - 登录成功后系统自动进入「应用工作区 -> 全部授权应用」，仅展示且只允许操作该租户被授权的应用模块；未授权应用的数据与接口均会被后台安全门禁自动拦截。

---

## 设计文档与示例应用索引 (`docs/`)

- [01_SRS_软件需求规格说明书.md](docs/01_SRS_软件需求规格说明书.md)
- [02_DDD领域建模与数据模型设计.md](docs/02_DDD领域建模与数据模型设计.md)
- [03_状态机与引擎生命周期设计.md](docs/03_状态机与引擎生命周期设计.md)
- [04_JSONRPC动态接口与元模型规范契约.md](docs/04_JSONRPC动态接口与元模型规范契约.md)
- **快递站管理系统（低代码实战样例）**：
  - [快递站管理系统_设计时操作流程.md](docs/快递站管理系统_设计时操作流程.md)
  - [快递站管理系统_使用教程.md](docs/快递站管理系统_使用教程.md)
  - 可导入应用配置包：[express-station-package.json](docs/express-station-package.json)
- 应用包校验 Schema：[schemas/app-package-schema.json](schemas/app-package-schema.json)
