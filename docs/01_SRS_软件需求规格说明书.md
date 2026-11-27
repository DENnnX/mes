# 软件需求规格说明书 (SRS)
## 模型驱动开发的低代码平台（Java + DDD + JSON-RPC 2.0）

**文档版本**：v3.0（推倒重建版 — 严格对标 `iidp-backend-demo` / `sie-snest-engine` 引擎内核）  
**适用范围**：华南师范大学《中级软件设计实作》选题 4：实现模型驱动开发的低代码平台  
**基线参考**：`D:\MES\iidp-backend-demo`（`sie-snest-engine`、`sie-iidp-dev-center`、`sie-iidp-iam`、`sie-iidp-app-store`）

---

## 1. 引言

### 1.1 编写目的与定位
本文档明确定义**模型驱动低代码开发平台（Model-Driven Low-Code Platform）**的完整软件需求规格。  
平台本身作为可独立运行的通用底座（Core Platform Kernel），彻底剥离特定上层业务硬编码，以**“模型即一切（Model is Everything）”**为核心理念：
- 开发者通过 **声明式 JSON 文件** 或 **平台内置开发者中心（Dev Center）** 定义应用（App）、数据模型（Model）、属性（Property）、服务与扩展（Service / Script / Action）、菜单树（Menu）及三件套视图（Grid / Search / Form）；
- 后端 **元模型引擎（Meta-Model Engine）** 基于 DDD 领域驱动架构解析元数据，自动完成物理数据库 Schema 同步（DDL）、线程级执行上下文与 `RecordSet` 构建，并通过 **统一 JSON-RPC 2.0** 协议自动暴露标准 CRUD 服务、关系检索 `lookup` 服务、声明式状态转移及动态脚本（Groovy）与 Java 扩展服务；
- 前端 **动态渲染引擎** 在不写死任何业务页面代码的前提下，请求 `loadMenu` 与 `loadView` 获取元模型契约，自动注册前端路由与导航菜单，动态渲染搜索区域、数据表格、多形态表单及一对多子表页签（Tabs），并将所有视图按钮动态绑定到对应的模型服务。

### 1.2 与基准系统 `iidp-backend-demo` 的架构对齐
| 平台维度 | `iidp-backend-demo` (`sie-snest-engine`) | 本平台设计实现 |
| :--- | :--- | :--- |
| **核心理念** | `@Model` + `BaseModel extends LinkedHashMap` + `RecordSet` | `ModelAggregate` + `RecordData(LinkedHashMap)` + `RecordSet` 统一模型句柄 |
| **执行环境** | `Meta`（`ThreadLocal` 绑定 `uid`/`tenantId`/`locale`，`meta.get(model)`） | `MetaContext`（线程绑定环境上下文，`ctx.get(model)` 返回 `RecordSet`） |
| **协议网关** | `POST /{tenant}/rpc/service` + 责任链 (`RpcHandler`) | `POST /{tenant}/rpc/service` + `Auth -> BuildMeta -> Permission -> ExecuteMeta` 责任链 |
| **请求格式** | `method:"service"`, `params:{app, tag, model, service, context, args}` | 完全兼容标准 `iidp` 信封格式，同时兼容 `<model>.<service>` 便捷路由 |
| **视图契约** | `UiMenu.loadMenu` + `UiView.loadView` + `grid`/`search`/`form` + `tabs` | 同构三件套视图契约 + 子表 `tabs` + 按钮参数弹窗 `paramsForm` 与表达式条件 |
| **自举建模** | `sie-iidp-dev-center`（`MetaModel`/`MetaProperty`/`MetaView.buildDefaultViews`） | 内置 **平台元模型自举应用**，支持在线建模、生成默认视图、JSON 应用包导入导出 |
| **扩展机制** | `@MethodService` 重写链 `callSuper` + Groovy/Aviator 脚本引擎 | `ExtensionRegistry` Java 扩展重写链（支持 `callSuper`） + **内置 Groovy 动态脚本引擎** + 声明式状态迁移 |
| **架构范式** | 企业级工业平台内核 | 严格 **DDD 四层架构**（Interfaces / Application / Domain / Infrastructure） |

---

## 2. 用户角色与职责边界

本平台聚焦开发者与治理者视角，所有业务应用的使用权由通用应用运行态承载：

| 角色标识 | 角色名称 | 核心职责与平台权限 |
| :--- | :--- | :--- |
| `ROLE_ADMIN` | **平台管理员** | 管理平台成员、角色与权限分配；配置模型下线最低保留策略；对符合守卫条件的模型清除申请执行**独立双人终审确认**；管理全平台运行状态与审计日志。 |
| `ROLE_DEVELOPER` | **模型开发者** | 编写/上传 JSON 应用包或通过开发者中心在线建模（模型/属性/关联/视图/菜单/脚本服务）；一键生成默认视图；执行元数据校验与变更影响分析。 |
| `ROLE_PUBLISHER` | **发布授权开发者** | 审查校验结果与引用影响报告；执行模型版本显式发布、兼容回滚；执行模型受控下线与申请永久清除（与清除终审人互斥）。 |
| `ROLE_APP_USER` | **应用终端用户** | 访问动态生成的业务菜单与视图页面；按当前角色功能权限与行/列数据范围执行检索、查看、新增、修改、删除及点击触发已绑定的模型服务。 |

---

## 3. 核心功能需求规格 (F01 – F06)

### F01：元模型声明定义与平台自举（Developer Studio & JSON Loader）
- **REQ-F01-01 JSON 应用包规范**：支持通过单个或多文件 JSON 定义完整的应用包，包括 App 基本信息、Models（含表名、自动审计列、逻辑删除、排序规则）、Properties（全数据类型、约束、关联关系）、Services（脚本/声明式/扩展标识）、Menus（树形结构）与 Views（`grid`/`search`/`form` 三件套）。
- **REQ-F01-02 全数据类型与 ER 关系支持**：
  - 基础标量类型：`String`、`Text`、`Integer`、`Long`、`Double`、`BigDecimal`/`Decimal`、`Boolean`、`Date`、`DateTime`、`Json`、`File`（附件上传/下载）；
  - 枚举与字典类型：`Selection`（静态枚举/动态 `selectionMethod`）与 `Dict`（`@Dict` 关联平台数据字典 `meta_dict`，支持 `multiple` 多选与标签颜色回显）；
  - 领域关联类型：`ManyToOne`、`OneToMany`、`ManyToMany`（自动生成中间表及 `[4,id]`/`[3,id]`/`[6,0,ids]` 维护）；
  - 高级注解与计算属性：`abstractModel`（抽象基类）、`parents` 多继承、`extendModel` 非侵入增量扩展包、`related` 跨关联带出、`computeMethod`/`computeScript` 动态计算、`codeRule` 自动编码、`defaultMethod` 动态默认值、`@Validate`（`email`、`minLength`、`maxLength`、`validationRegex`）、复合 `indexes`。
- **REQ-F01-03 在线建模工作台与基础中心**：开发者中心提供模型设计器、数据字典管理（`meta_dict`）、通用流水编码规则（`meta_code_rule`）、多租户与组织/权限树（`rbac_tenant`/`rbac_org`/`rbac_role`）、以及实时生成的 Swagger 风格 API 接口文档（`api_doc`）。
- **REQ-F01-04 一键生成默认三件套视图 (`buildDefaultViews`)**：当模型尚无自定义视图或触发视图生成时，引擎自动依据模型属性语义生成标准 `<model>_grid`、`<model>_search`、`<model>_form`（并将 `OneToMany` 自动装配为 `tabs` 子表）。

### F02：DDD 元模型引擎与动态 Schema 同步
- **REQ-F02-01 元模型语义校验**：解析模型时自动校验标识规范、字段重名、循环继承（`parents`）、合法数据类型、无效 `ManyToOne` 目标引用，以及存在关联视图列/按钮的悬空引用，精确返回报错路径与阻断原因。
- **REQ-F02-02 自动化 DDL Schema 同步**：模型发布生效时，基础设施层自动向底层关系型数据库下发 DDL：新建模型自动生成物理表及系统审计列（`id`, `tenant_id`, `bound_version_id`, `version`, `create_user`, `create_date`, `update_user`, `update_date`, `delete_flag`）；版本升级新增物理存储字段时，自动执行 `ALTER TABLE ... ADD COLUMN` 安全增量迁移。
- **REQ-F02-03 内存热注册容器**：发布成功后立即更新线程安全的 `MetaContainerRegistry`，无需重启 JVM，毫秒级激活路由、服务映射与视图缓存。

### F03：动态 JSON-RPC 2.0 服务生成与可扩展运行时
- **REQ-F03-01 统一网关与责任链**：提供 `POST /{tenant}/rpc/service` 统一端点（以及 `POST /rpc/open/service` 登录等开放接口），经由 `AuthRpcHandler -> BuildMetaRpcHandler -> PermissionRpcHandler -> ExecuteMetaRpcHandler` 责任链处理。
- **REQ-F03-02 自动标准服务生成**：任一已发布模型立即自动具备 7 项标准服务（无须手写任何 Java Controller/Service）：
  1. `search`：支持 Filter DSL（`[["field", "op", val]]` 与 `AND`/`OR`）、指定返回属性 `properties`、`limit`/`offset` 分页、`order` 排序，及 `useDisplayForModel`（自动展开 `ManyToOne` 目标显示名称与对象）；
  2. `count`：基于同一 `FilterDsl` 统计记录总数；
  3. `find`：按主键 `id`（或 `ids`）读取完整单条或多条详情（含关联 `OneToMany` 明细加载）；
  4. `create`：执行默认值填充、非空/类型/唯一性/范围校验、写入 `bound_version_id` 与审计列、`OneToMany` 子表联级写入；
  5. `update`：带乐观锁版本号（`version`）校验局部更新，并发冲突返回 `-32005` 提示刷新；
  6. `delete`：支持单条/批量删除及逻辑删除标记（`delete_flag = 1`），并检查外键被引用级联保护策略（`RESTRICT` / `SET_NULL` / `CASCADE`）；
  7. `lookup`：专为 `ManyToOne` 下拉远程搜索提供 `[{id, label, record}]` 轻量检索服务。
- **REQ-F03-03 动态业务扩展与脚本服务**：
  - **Groovy 脚本服务**：开发者可在 JSON 或 UI 中直接编写 Groovy 业务服务脚本，运行时容器向脚本注入 `env`（`MetaContext`）、`rs`（当前 `RecordSet`）、`args`（入参）与 `log`，可执行跨模型查询/修改与本地事务控制；
  - **声明式状态转移服务**：支持在 JSON 中声明 `fromStatus -> toStatus`、`guardScript`、`updateFields` 与前置/后置联动，零代码实现状态机流转；
  - **Java 重写链扩展**：支持通过 `ExtensionRegistry` 注册 Java 模型扩展，方法签名为 `(RecordSet rs, Map args)`，支持 `rs.callSuper(...)` 沿重写链回落引擎默认实现。
- **REQ-F03-04 本地事务原子性**：所有写操作及扩展服务均在统一 Spring / JDBC 本地事务中运行，任何步骤抛出异常立即整笔回滚，确保跨模型联动绝对一致。

### F04：动态前端渲染引擎与动态路由
- **REQ-F04-01 动态菜单与动态路由**：前端启动或登录后调用 `ui_menu.loadMenu`，由授权菜单树的 `routePath` 生成导航。业务地址采用 `/#/menu/{menuName}`，同一模型的不同菜单可绑定各自的视图。刷新、直链和浏览器前进后退使用同一套路径解析。
- **REQ-F04-02 视图三件套通用渲染器**：进入任意模型路由时，前端调用 `ui_view.loadView`，依据返回契约全动态渲染：
  - **Search 渲染器**：根据字段数据类型自动生成文本框、数字框、日期/时间区间选择器、`Selection` 下拉框及 `ManyToOne` 远程搜索选择器，自动组装标准 `Filter` DSL 提交查询；
  - **Grid 渲染器**：按列配置自动格式化显示值（`Selection` 标签染色、`ManyToOne` 主显值、Boolean 开关标签、排序、列宽、隐藏控制），渲染顶部 `tbar` 与行级 `buttons`；
  - **Form 渲染器**：按 `groupConf` 分组布局渲染创建/编辑/详情表单，完整支持必填与正则校验、`OneToMany` 内嵌 `tabs` 子表格的在线增删改、以及动态按钮参数收集弹窗。
- **REQ-F04-03 视图按钮与模型服务全动态绑定**：
  - 支持标准动作：`create`、`edit`、`preview`、`delete`；
  - 支持任意自定义服务按钮：`{ name, service, model, args, paramsForm, enableCondition, confirm, actionAfter }`；支持按当前行上下文表达式（如 `row.status === 'DRAFT'`）动态计算按钮可见性/禁用态，自动替换参数占位符（如 `$row.id`、`$selectedIds`），若有 `paramsForm` 自动弹出动态表单收集入参并调用对应 JSON-RPC 服务，随后按 `actionAfter` 自动刷新表格或通知提示。

### F05：版本演化、变更影响检查与下线清除治理
- **REQ-F05-01 版本不可变与双轨绑定**：
  - 模型可存在多个版本记录（`DRAFT` / `VALIDATED` / `ACTIVE` / `RETIRED`）；
  - 模型聚合根维护唯一 `active_version_id` 指针，新写入记录强制绑定当前 `active_version_id` 为自身的 `bound_version_id`；已发布版本快照**绝对不可变**，历史记录永久按提交时绑定的版本契约运行。
- **REQ-F05-02 变更影响检查 (`checkImpact`)**：发布候选版本前必须通过影响检查，引擎比对上一活动版本与候选版本差异，检索 `meta_model_ref` 引用图及存量数据：
  - **直接物理删除**已被视图/搜索/规则/关联引用的字段，或破坏性变更不兼容字段类型 -> **强制阻断**并列出全部受影响引用位置；
  - 将不再使用的旧字段标记为 **`display: false`（兼容退役）** -> 校验通过，新版表单隐藏录入入口而历史单据仍可安全读取。
- **REQ-F05-03 显式发布与兼容回滚**：通过检查后授权开发者显式发布（切指针+生成差异发布单 `meta_publish_record`）；支持安全回滚至兼容的 `RETIRED` 历史版本，并**保留全部既有业务数据不丢失**。
- **REQ-F05-04 受控下线与双人复核清除**：
  - 下线（`offline`）立即隐藏菜单、注销普通 RPC 入口但保留全部数据供导出；
  - 永久清除（`applyPurge` -> `confirmPurge`）须同时满足四大硬性守卫（满足保留期配置、存在有效数据导出凭据、外部模型零引用、在途未结清数据为零），且终审管理员 **严禁等于申请人**（`confirmBy != applyBy`）；完成后销毁物理表与元数据，但永久保留不含业务明细的解耦审计记录 `meta_purge_audit`。

### F06：身份认证与通用权限控制 (IAM)
- **REQ-F06-01 登录认证与上下文会话**：内置 `rbac_user`、`rbac_role`、`rbac_permission`，支持用户名/密码登录生成会话 Token，每次 RPC 请求均携带并解析当前操作人身份。
- **REQ-F06-02 角色功能授权与行级数据范围**：支持针对模型与服务的访问授权控制，以及基于角色的数据范围过滤策略（如 `ALL` 全局数据、`SELF` 仅本人创建数据 `create_user = currentUserId`、自定义 Filter 表达式）。

---

## 4. 非功能性需求 (NFR)

1. **高通用性与零代码复用性 (NFR-01)**：平台内核代码（`com.edu.scnu.*`）与前端通用引擎（`index.html`）不得包含任何特定业务领域的分支判断、表名或流程硬编码。任何新业务仅靠定义 JSON 元模型即可立即运行。
2. **动态热生效性能 (NFR-02)**：单次 JSON-RPC 元数据查询与标准 CRUD 接口 P95 响应时间 < 100ms；新模型发布 DDL 同步与前端路由热刷新耗时 < 500ms。
3. **并发安全性与事务一致性 (NFR-03)**：所有模型实例默认开启乐观锁版本控制（`version`）；多表复合写入（含 `OneToMany` 与 Groovy 跨模型写入）处于同一 ACID 本地事务边界内，异常 100% 自动回滚。
4. **容器化部署与端口安全约束 (NFR-04)**：提供轻量化单镜像或多阶段 `Dockerfile` 与 `docker-compose.yml`；容器服务端口明确绑定 **`8088`**，严格避开禁用端口 `8080`、`80`、`9000`、`9001`、`6379`、`5432`。
