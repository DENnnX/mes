# JSON-RPC 2.0 动态接口与元模型规范契约文档
## 模型驱动开发的低代码平台（Java + DDD + JSON-RPC 2.0）

**文档版本**：v3.0（推倒重建版 — 严格对标 `iidp-backend-demo` 请求信封与 Filter DSL）  
**统一接入端点**：`POST /{tenant}/rpc/service`（默认租户 `rbac_tenant_root`）

---

## 1. JSON-RPC 2.0 统一报文规范

### 1.1 请求格式 (兼容 `iidp` 标准信封与简写调用)
1. **标准 `iidp` 完整信封格式（平台前端主用）**：
```json
{
  "jsonrpc": "2.0",
  "id": "req-1732500001",
  "method": "service",
  "params": {
    "app": "my_app",
    "tag": "master",
    "model": "my_model",
    "service": "search",
    "context": { "uid": "admin", "timeZone": "UTC+8", "lang": "zh-CN" },
    "args": {
      "filter": [["status", "=", "ACTIVE"], ["name", "like", "测试"]],
      "properties": ["name", "code", "status"],
      "limit": 20,
      "offset": 0,
      "order": "create_date desc",
      "useDisplayForModel": true
    }
  }
}
```
2. **便捷简写调用格式（适合 curl / 外部系统集成）**：
当 `"method"` 不为 `"service"`，而是 `"<model>.<service>"`（如 `"meta_model.search"` 或 `"my_model.create"`）时，协议网关自动将其归一化解析为 `model = "<model>"`、`service = "<service>"`、`args = params`。

### 1.2 响应报文与标准错误码
- **成功响应**：`{"jsonrpc": "2.0", "result": <Any>, "id": "..."}`
- **失败响应**：`{"jsonrpc": "2.0", "error": {"code": <int>, "message": "<msg>", "data": {"errorCode": "...", "details": [...]}}, "id": "..."}`

| 错误码 | 异常类型 | 含义说明 |
| :--- | :--- | :--- |
| `-32700` | Parse Error | 非法 JSON 请求体 |
| `-32601` | Method Not Found | 模型或目标服务不存在（或模型已下线拒绝普通调用） |
| `-32602` | Invalid Params | 参数格式、必填、长度、数值范围或正则校验不通过 |
| `-32001` | Permission Denied | 未登录（Token 无效）或角色不具备该模型/服务访问权限 |
| `-32002` | Impact Block Error | 变更影响分析不通过，存在破坏性字段删除引用阻断 |
| `-32003` | State Guard Error | 声明式状态迁移守卫拦截，前置状态不匹配或条件表达式失败 |
| `-32004` | Dual Review Forbidden | 永久清除双人互斥违反（申请人与终审人不能为同一账号）或守卫不满足 |
| `-32005` | Concurrent Conflict | 乐观锁 `version` 冲突或候选版本检查哈希过期 |

---

## 2. Filter DSL 查询语法规范

对标 `com.sie.snest.engine.rule.Filter`，支持波兰前缀表达式与三元组原子条件：
- **原子条件三元组**：`[field, operator, value]`
  - `=` / `!=` / `>` / `>=` / `<` / `<=`
  - `like`（自动包裹 `%val%`）/ `llike` / `rlike` / `not like`
  - `in` / `not in`（`value` 为数组 `["A", "B"]`）
  - `is` / `is not`（`value` 为 `null`）
- **逻辑组合**：
  - 默认多条件列表隐含 `AND` 关系：`[["status", "=", "1"], ["age", ">=", 18]]`
  - 兼容标准扁平与嵌套波兰前缀操作符 `"&"` / `"|"` / `"AND"` / `"OR"`：`["&", ["name", "=", "张三"], "|", ["age", ">", 10], ["id", "in", ["1", "2"]]]`

---

## 3. 平台核心内建模型与服务契约

### 3.1 动态前端驱动契约（对标 `sie-iidp-iam`）
| 模型 (`model`) | 服务 (`service`) | 入参 (`args`) | 返回结构 (`result`) |
| :--- | :--- | :--- | :--- |
| `ui_menu` | `loadMenu` | `{}` | 授权菜单树。业务节点带 `routePath`，格式为 `/menu/{menuName}` |
| `ui_view` | `loadView` | 页面传 `{menu}`，模型调用可传 `{model, type:"m_grid,m_search,m_form"}` | `{modelMeta, views, fields, dictOptions, dynamicOptions, services, auth: {read,create,update,delete,services}}` |

按菜单加载时，后端校验菜单状态、应用与菜单授权，并使用菜单绑定的模型和视图。对有菜单白名单的调用者，按模型加载也受其有权访问的视图范围约束。

### 3.2 动态模型自动标准 CRUD 与生命周期钩子服务（任一已发布模型通用）
假设发布模型为 `<M>`：
| 服务名 | 关键 `args` | 返回值与核心行为 |
| :--- | :--- | :--- |
| `<M>.search` | `{filter, properties, limit, offset, order, useDisplayForModel}` | `{records: [...], total: N}`（支持多租户隔离、`related`/`compute` 字段、M2O 与 `@Dict` 染色展开） |
| `<M>.count` / `exists` | `{filter}` | 统计总数或存在性布尔判定 |
| `<M>.find` | `{id}` 或 `{ids: [...]}` | 单条或多条详情（含 `OneToMany` 子明细与 `ManyToMany` 关联列表） |
| `<M>.addMissingDefaultValues` | `{values: {...}}` | 按字段 `defaultValue` 与 `defaultMethod` 计算并返回表单新增默认值 |
| `<M>.getSelectionOptions` | `{field}` | 动态执行 `selectionMethod` 服务并返回下拉选项 |
| `<M>.create` | `{values: {...}}` 或 `{valuesList: [{...}]}` | 触发 `validate` 前置钩子、自动生成 `codeRule` 编码、`@Validate` 全校验、复合唯一约束检查及 ER 级联保存 |
| `<M>.update` | `{id, values: {...}, version}` | 带乐观锁局部更新，触发 `validate` 钩子，联动 `OneToMany` (`[0/1/2]`) 与 `ManyToMany` (`[4,id]` / `[3,id]` / `[6,0,ids]`) |
| `<M>.delete` | `{id}` 或 `{ids: [...]}` | 级联约束校验后物理或逻辑删除记录（自动清理 M2M 中间表） |
| `<M>.lookup` | `{field, keyword, filter, limit}` | 目标 `ManyToOne` / `ManyToMany` 模型轻量列表 `[{id, label}]` |

### 3.3 平台基础中心、RBAC 与自动化文档接口
| 服务调用 | 说明 |
| :--- | :--- |
| `meta_dict.*` | 数据字典类型与字典项管理（`listTypes`/`saveType`/`deleteType`/`listItems`/`saveItem`/`deleteItem`/`itemsByType`） |
| `meta_code_rule.*` | 通用流水编码规则管理与生成（`listRules`/`saveRule`/`deleteRule`/`generateCode`） |
| `rbac_tenant.*` | 多租户管理与应用范围授权（`listTenants`/`saveTenant`/`authorizeApps`） |
| `rbac_org.*` | 租户内树形组织架构管理（`listOrgs`/`saveOrg`/`deleteOrg`） |
| `rbac_user.*` | 登录/注销/个人档案/用户 CRUD/密码重置（`login`/`logout`/`profile`/`listUsers`/`saveUser`/`resetPassword`/`deleteUser`） |
| `rbac_role.*` | 角色 CRUD 与权限目录树读取（`listRoles`/`saveRole`/`deleteRole`/`permissionCatalog`） |
| `api_doc.*` | 自动 Swagger 风格 API 目录与模型规范导出（`listCatalog`/`describeModel`） |
| `/api/files/upload` & `/{id}` | REST 附件上传（Bearer Token 鉴权）与流式下载 |

### 3.3 元模型治理与自举中心接口 (`meta_app` / `meta_model` / `rbac_user`)
| 服务调用 | 说明 |
| :--- | :--- |
| `meta_app.importPackage` | 解析完整应用 JSON 包，批量校验并落库 `App`/`Models`/`Views`/`Menus` |
| `meta_app.exportPackage` | 导出选定应用的完整可移植 JSON 包 |
| `meta_model.saveDraft` | 在线保存模型定义与属性草稿（支持图形化建模或 JSON 编辑器提交），生成 `DRAFT` 版本 |
| `meta_model.buildDefaultViews` | 依据模型当前字段与关系自动合成标准的 `grid` / `search` / `form`（含 `tabs`）视图 |
| `meta_model.checkImpact` | 执行语法合法性与字段引用影响检查，返回 `ImpactReport` 与 `validationHash` |
| `meta_model.publish` | 校验 `validationHash`，同步底层物理表 DDL，切换 `active_version_id` 并热更新内存容器 |
| `meta_model.rollback` | 兼容性检查通过后切回指定 `RETIRED` 历史版本，完整保留业务表数据 |
| `meta_model.offline` | 下线模型，注销普通入口与隐藏菜单 |
| `meta_model.exportData` | 导出模型当前所有业务记录快照并签发 `exportTicket` |
| `meta_model.applyPurge` | 校验四大清除守卫后提交清除申请（`applyBy` 记录当前操作者） |
| `meta_model.confirmPurge` | 独立管理员校验 `confirmBy != applyBy` 并二次复核后物理清理，留存 `meta_purge_audit` |
| `rbac_user.login` | 验证账号密码签发会话 Token 并返回用户角色权限上下文 |

---

## 4. 视图按钮绑定的前端契约规范

视图 JSON 中的 `grid.body.tbar`、`grid.body.buttons` 及 `form.body.tbar` 均采用一致的按钮模型：
```json
{
  "name": "按钮展示文本",
  "type": "primary | success | warning | danger | default",
  "action": "create | edit | preview | delete | custom",
  "service": "要调用的后端服务名 (当 action='custom' 或自定义服务时必填)",
  "model": "目标模型名 (缺省默认为当前视图模型)",
  "auth": "权限标识 (默认与 service 一致)",
  "confirm": "可选：点击前二次确认提示文案",
  "enableCondition": "可选：JS 表达式，如 row.status === 'DRAFT'",
  "args": { "id": "$row.id", "ids": "$selectedIds" },
  "paramsForm": [
    { "name": "reason", "displayName": "审批原因", "dataType": "Text", "required": true }
  ],
  "actionAfter": "refreshTable | closeDialog | none"
}
```
frontend 通用引擎统一解释此契约：**无需编写任何定制前端组件**，点击按钮即可完成条件判断 -> 可选二次确认 -> 可选动态参数表单采集 -> 占位符求值 -> JSON-RPC 执行 -> 表格自刷新。
