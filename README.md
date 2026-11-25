# 模型驱动低代码开发平台（Low-Code Platform v2.1.0）

基于 **Java 17 + Spring Boot 2.7 + SQLite + Groovy + Vue 3 / Element Plus** 构建的**模型驱动低代码开发平台**。严格采用 **DDD（领域驱动设计）** 分层架构，深度集成通用企业级元数据引擎特性。

本仓库为**纯通用低代码底座实现**，启动自举仅注入平台自身内核、默认租户与 RBAC 认证元数据，**不包含任何预设业务示例**。开发者可通过 JSON 应用包或在线建模工作台动态定义任意领域模型与服务，平台即时同步物理 Schema、生成 JSON-RPC 2.0 服务接口、动态菜单与多形态视图，并提供数据字典、自动流水编码、多租户隔离、组织权限树及自动化 Swagger 风格接口文档。

---

## 核心特性

1. **纯正 DDD 四层架构 (`com.sie.lowcode`)**：
   - `interfaces`：`JsonRpcController` 4 环职责链（`AuthRpcHandler` -> `BuildMetaRpcHandler` -> `PermissionRpcHandler` -> `ExecuteMetaRpcHandler`）及 REST 文件附件接口 `FileController`（`/api/files/upload`、`/api/files/{fileId}`）。
   - `application`：`MetaGovernanceAppService`、`UiSchemaAppService`、`FoundationAppService`（字典 / 编码规则 / 自动 API 文档 / 权限目录）、`IamAppService`（多租户 / 组织树 / 用户 / 角色）、`PlatformKernelBootstrap`。
   - `domain`：`ModelAggregate`、`AppAggregate`、`ModelVersion`、`ModelInheritanceDomainService`（抽象继承 `abstractModel`/`parents` 与非侵入扩展包 `extendModel`）、`RecordSet`（多层级 `callSuper` 重写链、`validate` 前置钩子、`ServiceActionResult` UI 指令）、`FilterDsl`（扁平/嵌套波兰前缀 `&` / `|`）、`DictTypeDef`/`DictItemDef`/`CodeRuleDef`/`TenantDef`/`OrgNode`。
   - `infrastructure`：`DynamicSqlEngine`（跳过抽象/扩展表 DDL、复合索引、中间表 DDL）、`DynamicDataAccessorImpl`（多租户 `tenant_id` 隔离、`related` 级联带出、`computeMethod`/`computeScript`、`codeRule` 自动编码、`@Validate` 全约束、`ManyToMany` `[4,id]`/`[3,id]`/`[6,0,ids]`、`useDisplayForModel` 字典与外键翻译）、`GroovyScriptExecutorImpl`、`SqliteFoundationRepository`。
2. **统一 JSON-RPC 2.0 协议与通用能力服务**：
   - 内置标准通用服务：`search`、`count`、`exists`、`find`、`create`、`update`（含乐观锁）、`delete`、`lookup`、`addMissingDefaultValues`、`getSelectionOptions`。
   - 通用平台中心服务：`meta_dict`（动态字典与标签染色）、`meta_code_rule`（前缀+日期+流水号自增生成器）、`rbac_tenant`（租户管理及授权应用范围）、`rbac_org`（多租户组织架构树）、`rbac_role`（细粒度权限目录树）、`api_doc`（自动 API 文档与试调模板）。
3. **版本演进治理与异人双审清除**：
   - **影响检查 (`checkImpact`)**：校验 Groovy AST 语法、继承/扩展拓扑、`displayForModel` 告警提示及视图/引用图阻断。
   - **双人互斥清除 (`offline` -> `exportData` -> `applyPurge` -> `confirmPurge`)**：强制导出凭据，禁止同身份自审终批（`-32004`），保留 `meta_purge_audit` 审计记录。
4. **纯元视图驱动前端 (`static/index.html`)**：
   - **开发者工作台**：多页签顶部导航、多皮肤主题切换、模型设计器（含继承/扩展/复合索引/字段高级规则）、列表/卡片应用视图、数据字典与规则配置、租户/组织/权限树配置、自动 API 接口文档与调试台。
   - **动态运行端**：遵循 `ui_view.loadView` 返回的 `auth` 裁切按钮，支持 `保存并新增`、动态默认值初始化、多选 `Dict`、文件上传下载、`ManyToMany` 左右选择器（`addEr`）、`OneToMany` 内嵌与弹窗编辑（`updateEr`）、列表/卡片切换、列显隐设置及服务端 UI 指令弹窗联动。

---

## 快速构建、测试与 Docker 部署

> **端口约定**：默认运行端口为 **`8088`**（已避开 `8080`、`80`、`9000`、`9001`、`6379`、`5432`）。

### 1. 编译与集成测试

```bash
cd lowcode-platform
mvn clean package
```

### 2. Docker 一键部署（推荐）

```bash
cd lowcode-platform
docker compose up -d --build
```

启动完成后访问：
- **平台工作台与动态运行端**：`http://localhost:8088/`
- **健康检查**：`GET http://localhost:8088/api/health`
- **统一 JSON-RPC 网关**：`POST http://localhost:8088/rbac_tenant_root/rpc/service`

### 3. 内置体验账号（用于切换验证 RBAC 与双人互斥）

| 用户名 | 密码 | 角色 | 典型用途 |
| :--- | :--- | :--- | :--- |
| `developer` | `123456` | `ROLE_DEVELOPER`, `ROLE_PUBLISHER` | 建模、生成视图、影响检查、发布、回滚、下线与申请清除 |
| `auditor` | `123456` | `ROLE_ADMIN` | 独立终审确认清除（与 `developer` 构成异人双审互斥） |
| `admin` | `123456` | `ROLE_ADMIN`, `ROLE_DEVELOPER`, `ROLE_PUBLISHER` | 超级管理员（用户与角色管理） |
| `user` | `123456` | `ROLE_APP_USER` | 普通业务用户（仅操作动态运行端页面 CRUD 与业务动作） |

---

## 设计文档索引 (`D:\MES\docs\`)

- [01_SRS_软件需求规格说明书.md](docs/01_SRS_软件需求规格说明书.md)
- [02_DDD领域建模与数据模型设计.md](docs/02_DDD领域建模与数据模型设计.md)
- [03_状态机与引擎生命周期设计.md](docs/03_状态机与引擎生命周期设计.md)
- [04_JSONRPC动态接口与元模型规范契约.md](docs/04_JSONRPC动态接口与元模型规范契约.md)
- 应用包校验 Schema：[schemas/app-package-schema.json](schemas/app-package-schema.json)
