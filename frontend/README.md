# 云枢低代码平台前端 (Vue 3 + Vite + Element Plus)

独立工程化的 Vue 3 单文件组件（SFC）前端，负责承载平台登录门禁、管理中心可视化建模/授权/安全治理，以及动态渲染的业务运行工作区。

## 目录结构

```text
frontend/
├── index.html
├── package.json
├── vite.config.js               # 开发代理 (/rbac_tenant_root, /rpc, /api -> localhost:8088)
├── scripts/sync-static.mjs      # 将打包产物同步到 ../lowcode-platform/src/main/resources/static
└── src/
    ├── api/rpc.js               # JSON-RPC 2.0 统一请求与附件上传封装
    ├── composables/
    │   └── useRuntimeFieldMeta.js
    ├── layouts/MainLayout.vue   # 顶部工作区切换、主题切换、多页签与动态导航栏
    ├── router/index.js          # Vue Router 路由守卫与动态模型页面路由
    ├── stores/
    │   ├── auth.js              # Pinia 登录会话、角色与菜单权限状态
    │   └── app.js               # Pinia 主题、菜单树、多页签与平台元信息缓存
    ├── styles/theme.css         # 多主题 CSS 变量与全局卡片/布局样式
    ├── utils/formatters.js      # 类型标签、数据格式化与常用辅助函数
    ├── components/
    │   ├── studio/ModelDesignerModal.vue
    │   └── runtime/
    │       ├── RecordDetailDrawer.vue
    │       └── RecordFormModal.vue
    └── views/
        ├── LoginView.vue
        ├── iam/IamManagementView.vue
        ├── runtime/
        │   ├── RuntimePortalView.vue
        │   └── DynamicModelView.vue
        └── studio/
            ├── OverviewView.vue
            ├── AppsView.vue
            ├── ModelsView.vue
            ├── ViewsMenusView.vue
            ├── DictView.vue
            ├── CodeRuleView.vue
            ├── GovernanceView.vue
            ├── ApiDocView.vue
            └── RpcConsoleView.vue
```

## 本地开发与联调

确保后端服务已监听 `http://localhost:8088` 后：

```bash
npm install
npm run dev
```

Vite 开发服务器默认运行在 `http://localhost:5173`，自动将 `/rbac_tenant_root/*`、`/rpc/*`、`/api/*` 代理到后端 `8088` 端口。

## 构建与同步部署

- 仅构建前端静态产物到 `frontend/dist/`：

```bash
npm run build
```

- 构建并同步到后端 Spring Boot 静态资源目录（用于一体化打 JAR / Docker 部署在 `8088` 端口）：

```bash
npm run build:sync
```
