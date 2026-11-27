package com.edu.scnu.interfaces.rest;

import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.metamodel.repository.AppRepository;
import com.edu.scnu.infrastructure.container.MetaContainerMemoryRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * OpenAPI 3.0.3 动态文档与 Swagger / Apifox 集成配置：
 * - 自动暴露系统 REST 接口（/api/health、/api/files/upload、/api/files/{fileId}）
 * - 为认证入口、平台管理接口及所有已发布的低代码动态模型实时生成 per-service OpenAPI Path：
 *   POST /{tenant}/rpc/service/{model}/{service}
 * - 可直接导入 Apifox 或通过 /swagger-ui.html 在线调试。
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_BEARER = "bearerAuth";

    @Bean
    public OpenAPI cloudForgeOpenAPI() {
        Components components = new Components()
                .addSecuritySchemes(SECURITY_SCHEME_BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("先调用 POST /rpc/open/service/rbac_user/login 获取 token，再在 Swagger Authorize 或 Apifox Bearer Token 中填入。"))
                .addSchemas("JsonRpcErrorObject", new ObjectSchema()
                        .addProperty("code", new IntegerSchema().example(-32602))
                        .addProperty("message", new StringSchema().example("参数错误"))
                        .addProperty("data", new Schema<>().nullable(true)))
                .addSchemas("JsonRpcSuccessResponse", new ObjectSchema()
                        .addProperty("jsonrpc", new StringSchema().example("2.0"))
                        .addProperty("id", new StringSchema().example("req-1"))
                        .addProperty("result", new Schema<>().description("业务返回值（对象、列表或状态消息）")))
                .addSchemas("JsonRpcRequestEnvelope", new ObjectSchema()
                        .addProperty("jsonrpc", new StringSchema()._default("2.0").example("2.0"))
                        .addProperty("id", new StringSchema()._default("1").example("1"))
                        .addProperty("method", new StringSchema()._default("service").example("service"))
                        .addProperty("params", new ObjectSchema()
                                .addProperty("app", new StringSchema().example("apifox"))
                                .addProperty("tag", new StringSchema().example("master"))
                                .addProperty("model", new StringSchema().example("meta_model"))
                                .addProperty("service", new StringSchema().example("search"))
                                .addProperty("context", new ObjectSchema().description("可选扩展上下文，如 {\"lang\":\"zh-CN\"}"))
                                .addProperty("args", new ObjectSchema().description("接口业务参数对象"))));

        OpenAPI openAPI = new OpenAPI()
                .components(components)
                .info(new Info()
                        .title("云枢低代码平台 OpenAPI 3.0 接口文档")
                        .version("3.0.3")
                        .description("""
                                遵循 OpenAPI 3.0.3 规范，支持导入 **Apifox** 或通过 **Swagger UI** 直接调试。

                                ### 调用说明
                                1. 登录获取 Token：调用 `POST /rpc/open/service/rbac_user/login`（默认租户 `rbac_tenant_root`，如 `admin` / `123`）。
                                2. 鉴权方式：在请求头携带 `Authorization: Bearer <token>`。
                                3. 参数兼容格式：对所有 `/{tenant}/rpc/service/{model}/{service}` 端点，既支持完整 JSON-RPC 2.0 信封：
                                   `{"jsonrpc":"2.0","id":"1","method":"service","params":{"args":{...}}}`，
                                   也支持 Apifox 简写 JSON `{"args":{...}}` 或直接传参 `{...}`。"""));
        openAPI.setOpenapi("3.0.3");
        return openAPI;
    }

    @Bean
    public OpenApiCustomizer dynamicLowCodeOpenApiCustomizer(MetaContainerMemoryRegistry registry,
                                                            AppRepository appRepository) {
        return openApi -> {
            openApi.setOpenapi("3.0.3");
            if (openApi.getPaths() == null) {
                openApi.setPaths(new Paths());
            }
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            // 确保基础组件与鉴权方案不被静态扫描裁剪
            if (openApi.getComponents().getSecuritySchemes() == null
                    || !openApi.getComponents().getSecuritySchemes().containsKey(SECURITY_SCHEME_BEARER)) {
                openApi.getComponents().addSecuritySchemes(SECURITY_SCHEME_BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("先调用 POST /rpc/open/service/rbac_user/login 获取 token，再在 Swagger Authorize 或 Apifox Bearer Token 中填入。"));
            }
            openApi.getComponents()
                    .addSchemas("JsonRpcErrorObject", new ObjectSchema()
                            .addProperty("code", new IntegerSchema().example(-32602))
                            .addProperty("message", new StringSchema().example("参数错误"))
                            .addProperty("data", new Schema<>().nullable(true)))
                    .addSchemas("JsonRpcSuccessResponse", new ObjectSchema()
                            .addProperty("jsonrpc", new StringSchema().example("2.0"))
                            .addProperty("id", new StringSchema().example("req-1"))
                            .addProperty("result", new Schema<>().description("业务返回值（对象、列表或状态消息）")))
                    .addSchemas("JsonRpcRequestEnvelope", new ObjectSchema()
                            .addProperty("jsonrpc", new StringSchema()._default("2.0").example("2.0"))
                            .addProperty("id", new StringSchema()._default("1").example("1"))
                            .addProperty("method", new StringSchema()._default("service").example("service"))
                            .addProperty("params", new ObjectSchema()
                                    .addProperty("app", new StringSchema().example("apifox"))
                                    .addProperty("tag", new StringSchema().example("master"))
                                    .addProperty("model", new StringSchema().example("meta_model"))
                                    .addProperty("service", new StringSchema().example("search"))
                                    .addProperty("context", new ObjectSchema().description("可选扩展上下文，如 {\"lang\":\"zh-CN\"}"))
                                    .addProperty("args", new ObjectSchema().description("接口业务参数对象"))));

            // 完善静态 REST 端点认证信息（/api/files/** 需要 Bearer Token）
            normalizeStaticRestOperations(openApi);

            Map<String, String> appDisplayNames = new LinkedHashMap<>();
            for (AppAggregate app : appRepository.findAll()) {
                appDisplayNames.put(app.getName(), app.getDisplayName() != null ? app.getDisplayName() : app.getName());
            }

            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag().name("00. 系统与文件 REST").description("健康检查、文件上传与受保护下载接口"));
            tags.add(new Tag().name("01. 认证与门户入口").description("登录认证、当前用户信息及门户公共应用"));
            tags.add(new Tag().name("02. 模型与版本生命周期 (meta_model)").description("模型创建、草稿版本、影响检查、发布回滚、停用/上线与数据备份"));
            tags.add(new Tag().name("03. 应用包与视图菜单").description("应用导出/导入、表单列表视图与动态菜单契约"));
            tags.add(new Tag().name("04. 基础能力与组织权限").description("数据字典、编码规则、租户、组织架构、角色与用户管理"));
            tags.add(new Tag().name("05. JSON-RPC 2.0 统一网关").description("统一 JSON-RPC 2.0 POST 入口（前端与通用客户端使用）"));

            registerAuthAndPortalEndpoints(openApi);
            registerPlatformCoreEndpoints(openApi);
            registerDynamicModelEndpoints(openApi, registry, appDisplayNames, tags);

            openApi.setTags(tags);
        };
    }

    private void normalizeStaticRestOperations(OpenAPI openApi) {
        SecurityRequirement bearerReq = new SecurityRequirement().addList(SECURITY_SCHEME_BEARER);
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/files")) {
                item.readOperations().forEach(op -> {
                    op.setTags(List.of("00. 系统与文件 REST"));
                    op.addSecurityItem(bearerReq);
                });
            } else if ("/api/health".equals(path)) {
                item.readOperations().forEach(op -> {
                    op.setTags(List.of("00. 系统与文件 REST"));
                    op.setSecurity(Collections.emptyList());
                });
            } else if (path.endsWith("/rpc/service") || "/rpc/open/service".equals(path)) {
                item.readOperations().forEach(op -> {
                    op.setTags(List.of("05. JSON-RPC 2.0 统一网关"));
                    if (!"/rpc/open/service".equals(path)) {
                        op.addSecurityItem(bearerReq);
                    } else {
                        op.setSecurity(Collections.emptyList());
                    }
                });
            }
        });
    }

    private void registerAuthAndPortalEndpoints(OpenAPI openApi) {
        String tag = "01. 认证与门户入口";
        addServiceOperation(openApi, "/rpc/open/service/rbac_user/login", tag, false, false,
                "账号登录获取 Token",
                "匿名登录接口。使用用户名、密码和租户编码（默认 rbac_tenant_root）换取 Bearer Token。",
                "rpc_open_rbac_user_login",
                Map.of("args", Map.of("username", "admin", "password", "123456", "tenantCode", "rbac_tenant_root")),
                Map.of("username", new StringSchema().example("admin"),
                        "password", new StringSchema().example("123456"),
                        "tenantCode", new StringSchema().example("rbac_tenant_root")));

        addServiceOperation(openApi, "/rpc/open/service/sys_portal/publicApps", tag, false, false,
                "获取开放门户应用列表",
                "匿名获取可对外展示的应用及首页入口元数据。",
                "rpc_open_sys_portal_publicApps",
                Collections.emptyMap(),
                Collections.emptyMap());

        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_user/profile", tag, true, true,
                "获取当前登录用户信息",
                "返回当前 Bearer Token 对应的用户、租户、角色与权限信息。",
                "rpc_rbac_user_profile",
                Collections.emptyMap(),
                Collections.emptyMap());

        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_user/changePassword", tag, true, true,
                "修改当前用户密码",
                "需传入 oldPassword 与 newPassword。",
                "rpc_rbac_user_changePassword",
                Map.of("args", Map.of("oldPassword", "123", "newPassword", "123456")),
                Map.of("oldPassword", new StringSchema().example("123"),
                        "newPassword", new StringSchema().example("123456")));
    }

    private void registerPlatformCoreEndpoints(OpenAPI openApi) {
        // 02. 模型与版本生命周期
        String modelTag = "02. 模型与版本生命周期 (meta_model)";
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/search", modelTag, true, true,
                "查询模型清单 (search)", "查询当前所有模型定义、生命周期状态与激活版本。", "rpc_meta_model_search",
                Map.of("args", Map.of("appName", "")),
                Map.of("appName", new StringSchema().description("可选，按应用编码过滤")));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/saveModel", modelTag, true, true,
                "新建或更新模型基本信息 (saveModel)", "创建新模型或更新模型名称、应用归属等基本信息。", "rpc_meta_model_saveModel",
                Map.of("args", Map.of("name", "mes_sample", "displayName", "示例模型", "appName", "mes_app")),
                Map.of("name", new StringSchema(), "displayName", new StringSchema(), "appName", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/listVersions", modelTag, true, true,
                "查询模型版本历史 (listVersions)", "按 modelName 列出草稿、已检查、生效与历史版本。", "rpc_meta_model_listVersions",
                Map.of("args", Map.of("modelName", "mes_material")),
                Map.of("modelName", new StringSchema().example("mes_material")));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/saveDraft", modelTag, true, true,
                "保存模型草稿快照 (saveDraft)", "提交字段、索引、服务与状态机规则草稿，需通过影响检查后发布。", "rpc_meta_model_saveDraft",
                Map.of("args", Map.of("modelName", "mes_material", "versionNo", "1.0.1", "properties", List.of())),
                Map.of("modelName", new StringSchema(), "versionNo", new StringSchema(), "properties", new ArraySchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/checkImpact", modelTag, true, true,
                "执行变更影响检查 (checkImpact)", "校验版本兼容性并生成发布凭据 validationHash。", "rpc_meta_model_checkImpact",
                Map.of("args", Map.of("modelName", "mes_material", "versionId", "")),
                Map.of("modelName", new StringSchema(), "versionId", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/publish", modelTag, true, true,
                "发布并上线模型版本 (publish)", "激活指定已校验版本并同步动态 SQLite 表结构。", "rpc_meta_model_publish",
                Map.of("args", Map.of("modelName", "mes_material", "versionId", "", "expectedHash", "")),
                Map.of("modelName", new StringSchema(), "versionId", new StringSchema(), "expectedHash", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/rollback", modelTag, true, true,
                "回滚到历史兼容版本 (rollback)", "切换模型活动版本指针到指定历史 RETIRED 版本。", "rpc_meta_model_rollback",
                Map.of("args", Map.of("modelName", "mes_material", "targetVersionId", "")),
                Map.of("modelName", new StringSchema(), "targetVersionId", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/offline", modelTag, true, true,
                "停用数据表 (offline)", "将非内核模型置为 OFFLINE 状态，关闭普通业务数据读写入口。", "rpc_meta_model_offline",
                Map.of("args", Map.of("modelName", "mes_material")),
                Map.of("modelName", new StringSchema().example("mes_material")));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/online", modelTag, true, true,
                "恢复上线数据表 (online)", "将 OFFLINE 状态的模型重新切回 ONLINE_ACTIVE 运行态。", "rpc_meta_model_online",
                Map.of("args", Map.of("modelName", "mes_material")),
                Map.of("modelName", new StringSchema().example("mes_material")));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/exportData", modelTag, true, true,
                "备份导出模型全量数据 (exportData)", "导出模型当前物理表全部记录并返回备份凭据与记录集。", "rpc_meta_model_exportData",
                Map.of("args", Map.of("modelName", "mes_material")),
                Map.of("modelName", new StringSchema().example("mes_material")));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/listPublishRecords", modelTag, true, true,
                "查询发布与回滚日志 (listPublishRecords)", "按可选 modelName 查询发布记录。", "rpc_meta_model_listPublishRecords",
                Map.of("args", Map.of("modelName", "")),
                Map.of("modelName", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_model/deleteModel", modelTag, true, true,
                "删除表单与数据表 (meta_model.deleteModel)", "彻底删除指定非内核表单/数据表及其所有版本、关联视图、导航菜单和业务物理表。", "rpc_meta_model_deleteModel",
                Map.of("args", Map.of("modelName", "custom_entity")),
                Map.of("modelName", new StringSchema().example("custom_entity")));

        // 03. 应用包与视图菜单
        String viewTag = "03. 应用包与视图菜单";
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_app/search", viewTag, true, true,
                "查询应用列表 (meta_app.search)", "查询平台已注册应用。", "rpc_meta_app_search", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_app/saveApp", viewTag, true, true,
                "新建或更新应用 (meta_app.saveApp)", "保存低代码应用信息。", "rpc_meta_app_saveApp",
                Map.of("args", Map.of("name", "mes_app", "displayName", "智能制造 MES")),
                Map.of("name", new StringSchema(), "displayName", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_app/exportPackage", viewTag, true, true,
                "导出应用元数据安装包 (exportPackage)", "导出应用下所有模型、视图、菜单、字典与编码规则 JSON 包。", "rpc_meta_app_exportPackage",
                Map.of("args", Map.of("appName", "mes_app")),
                Map.of("appName", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_app/importPackage", viewTag, true, true,
                "导入应用元数据安装包 (importPackage)", "批量导入并部署应用模型、视图与菜单。", "rpc_meta_app_importPackage",
                Map.of("args", Map.of("packageJson", "{}")),
                Map.of("packageJson", new Schema<>().description("应用包 JSON 对象或字符串")));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_app/deleteApp", viewTag, true, true,
                "删除应用及关联表单菜单 (meta_app.deleteApp)", "彻底删除指定业务应用及其对应的模型表单、页面视图、导航菜单和业务数据表（platform_kernel 不可删除）。", "rpc_meta_app_deleteApp",
                Map.of("args", Map.of("appName", "sample_app")),
                Map.of("appName", new StringSchema().example("sample_app")));

        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_view/listViews", viewTag, true, true,
                "查询视图定义 (meta_view.listViews)", "获取所有或指定模型的 grid/form/search 视图配置。", "rpc_meta_view_listViews",
                Map.of("args", Map.of("modelName", "")),
                Map.of("modelName", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_view/saveView", viewTag, true, true,
                "保存视图定义 (meta_view.saveView)", "保存列表或表单 XML/JSON 配置。", "rpc_meta_view_saveView",
                Map.of("args", Map.of("modelName", "mes_material", "viewType", "grid")),
                Map.of("modelName", new StringSchema(), "viewType", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_view/deleteView", viewTag, true, true,
                "删除视图布局 (meta_view.deleteView)", "按视图唯一标识 key 删除页面布局定义。", "rpc_meta_view_deleteView",
                Map.of("args", Map.of("key", "custom_entity_grid")),
                Map.of("key", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_view/generateDefaultViews", viewTag, true, true,
                "自动生成模型默认视图 (generateDefaultViews)", "根据模型生效快照一键生成列表/搜索/表单视图及菜单。", "rpc_meta_view_generateDefaultViews",
                Map.of("args", Map.of("modelName", "mes_material")),
                Map.of("modelName", new StringSchema()));

        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_menu/listMenus", viewTag, true, true,
                "查询菜单配置 (meta_menu.listMenus)", "列出所有动态路由菜单定义。", "rpc_meta_menu_listMenus", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_menu/deleteMenu", viewTag, true, true,
                "删除导航菜单 (meta_menu.deleteMenu)", "按菜单标识 name 删除导航菜单并清理授权引用。", "rpc_meta_menu_deleteMenu",
                Map.of("args", Map.of("name", "custom_entity_menu")),
                Map.of("name", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/ui_menu/loadMenu", viewTag, true, true,
                "加载当前用户可见运行态菜单 (ui_menu.loadMenu)", "结合 RBAC 权限返回当前用户菜单树。", "rpc_ui_menu_loadMenu", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/ui_view/loadView", viewTag, true, true,
                "加载模型运行态视图契约 (ui_view.loadView)", "返回合并模型元数据与字段权限后的 grid/form/search 定义。", "rpc_ui_view_loadView",
                Map.of("args", Map.of("model", "mes_material", "type", "grid")),
                Map.of("model", new StringSchema(), "type", new StringSchema()));

        // 04. 基础能力与组织权限
        String iamTag = "04. 基础能力与组织权限";
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_dict/listTypes", iamTag, true, true,
                "查询数据字典分类 (meta_dict.listTypes)", "获取平台数据字典分类列表。", "rpc_meta_dict_listTypes", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_dict/itemsByType", iamTag, true, true,
                "按分类查字典项 (meta_dict.itemsByType)", "根据 typeCode 返回枚举键值对。", "rpc_meta_dict_itemsByType",
                Map.of("args", Map.of("typeCode", "priority")),
                Map.of("typeCode", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_code_rule/listRules", iamTag, true, true,
                "查询编码规则列表 (meta_code_rule.listRules)", "列出流水号前缀、日期格式与步长配置。", "rpc_meta_code_rule_listRules", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_code_rule/generateCode", iamTag, true, true,
                "生成下一个业务编码 (meta_code_rule.generateCode)", "按编码规则原子生成新单据编号。", "rpc_meta_code_rule_generateCode",
                Map.of("args", Map.of("ruleCode", "WO_NO")),
                Map.of("ruleCode", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/meta_file/listFiles", iamTag, true, true,
                "查询附件元数据记录 (meta_file.listFiles)", "查询当前租户上传附件清单。", "rpc_meta_file_listFiles", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_tenant/search", iamTag, true, true,
                "查询租户列表 (rbac_tenant.search)", "查询平台多租户清单。", "rpc_rbac_tenant_search", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_tenant/deleteTenant", iamTag, true, true,
                "删除租户或分站 (rbac_tenant.deleteTenant)", "删除非根非自身租户（无下级分站时）并级联清理其部门、账号与自定义角色。", "rpc_rbac_tenant_deleteTenant",
                Map.of("args", Map.of("tenantId", "branch_demo")),
                Map.of("tenantId", new StringSchema()));
        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_org/listTree", iamTag, true, true,
                "查询组织架构树 (rbac_org.listTree)", "按租户返回部门与公司组织树。", "rpc_rbac_org_listTree", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_role/search", iamTag, true, true,
                "查询角色权限列表 (rbac_role.search)", "列出角色及其允许的服务、菜单及字段权限规则。", "rpc_rbac_role_search", Collections.emptyMap(), Collections.emptyMap());
        addServiceOperation(openApi, "/{tenant}/rpc/service/rbac_user/search", iamTag, true, true,
                "查询用户账号列表 (rbac_user.search)", "列出租户内用户账号信息。", "rpc_rbac_user_search", Collections.emptyMap(), Collections.emptyMap());
    }

    private void registerDynamicModelEndpoints(OpenAPI openApi,
                                               MetaContainerMemoryRegistry registry,
                                               Map<String, String> appDisplayNames,
                                               List<Tag> tags) {
        Map<String, ModelSnapshot> snapshots = new TreeMap<>(registry.getAllActiveSnapshots());
        for (Map.Entry<String, ModelSnapshot> entry : snapshots.entrySet()) {
            String modelName = entry.getKey();
            ModelSnapshot snap = entry.getValue();
            ModelAggregate agg = registry.getModelAggregate(modelName);
            if (agg == null || agg.isKernelModel() || snap.isAbstractModel() || snap.isExtensionPackage()) {
                continue;
            }
            if (agg.getLifecycleState() == ModelAggregate.LifecycleState.PURGED) {
                continue;
            }

            String displayName = snap.getDisplayName() != null ? snap.getDisplayName() : modelName;
            String appLabel = appDisplayNames.getOrDefault(snap.getAppName(), snap.getAppName() != null ? snap.getAppName() : "未分组");
            String stateSuffix = agg.getLifecycleState() == ModelAggregate.LifecycleState.OFFLINE ? " [已停用]" : "";
            String tagName = "模型 · " + displayName + " (" + modelName + ")";

            tags.add(new Tag().name(tagName)
                    .description("应用: " + appLabel + " | 版本: v" + snap.getVersionNo() + stateSuffix));

            // 注册模型数据结构到 components/schemas
            String schemaKey = toComponentSchemaName(modelName) + "Record";
            Schema<?> recordSchema = buildModelRecordSchema(snap);
            openApi.getComponents().addSchemas(schemaKey, recordSchema);

            Map<String, Object> sampleRecord = buildSampleRecord(snap);

            // 标准 CRUD + lookup 端点
            registerCrudOperationsForModel(openApi, modelName, displayName, stateSuffix, tagName, schemaKey, sampleRecord);

            // 自定义 Groovy / 状态机 / Java 扩展服务端点
            if (snap.getServices() != null) {
                Set<String> crudNames = Set.of("search", "find", "count", "exists", "addMissingDefaultValues", "create", "update", "delete", "lookup");
                for (ServiceDef svc : snap.getServices()) {
                    if (svc.getName() == null || svc.getName().isBlank() || crudNames.contains(svc.getName())) {
                        continue;
                    }
                    String svcName = svc.getName();
                    String path = "/{tenant}/rpc/service/" + modelName + "/" + svcName;
                    String summary = (svc.getDisplayName() != null ? svc.getDisplayName() : svcName) + " (" + modelName + "." + svcName + ")" + stateSuffix;
                    String desc = "自定义服务类型: " + svc.getType()
                            + (svc.getDescription() != null && !svc.getDescription().isBlank() ? "\n说明: " + svc.getDescription() : "");

                    Map<String, Object> exampleArgs = new LinkedHashMap<>();
                    Map<String, Schema<?>> argProps = new LinkedHashMap<>();
                    if (ServiceDef.TYPE_STATE_TRANSITION.equals(svc.getType())) {
                        exampleArgs.put("id", "<记录ID>");
                        argProps.put("id", new StringSchema().description("目标记录 ID"));
                    } else if (svc.getParamsSchema() != null && !svc.getParamsSchema().isEmpty()) {
                        for (PropertyDef p : svc.getParamsSchema()) {
                            exampleArgs.put(p.getName(), sampleValueForProperty(p));
                            argProps.put(p.getName(), toPropertySchema(p));
                        }
                    } else {
                        exampleArgs.put("id", "<可选记录ID>");
                        argProps.put("id", new StringSchema().description("可选，记录 ID 或业务参数"));
                    }

                    addServiceOperation(openApi, path, tagName, true, true,
                            summary, desc, safeOperationId("rpc_" + modelName + "_" + svcName),
                            Map.of("args", exampleArgs), argProps);
                }
            }
        }
    }

    private void registerCrudOperationsForModel(OpenAPI openApi,
                                                String modelName,
                                                String displayName,
                                                String stateSuffix,
                                                String tagName,
                                                String schemaKey,
                                                Map<String, Object> sampleRecord) {
        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/search", tagName, true, true,
                "分页查询" + displayName + " (search)" + stateSuffix,
                "支持 Filter DSL（如 [[\"status\",\"=\",\"DRAFT\"]]）、指定返回字段与分页参数。",
                safeOperationId("rpc_" + modelName + "_search"),
                Map.of("args", Map.of("fields", List.of(), "filters", List.of(), "page", 1, "pageSize", 20)),
                Map.of("fields", new ArraySchema().items(new StringSchema()).description("返回字段名列表，空数组返回全部显示字段"),
                        "filters", new ArraySchema().description("Filter DSL 二维条件数组"),
                        "order", new StringSchema().example("create_date desc"),
                        "page", new IntegerSchema()._default(1).example(1),
                        "pageSize", new IntegerSchema()._default(20).example(20)));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/find", tagName, true, true,
                "按 ID 获取" + displayName + "详情 (find)" + stateSuffix,
                "根据主键 id 读取单条记录（含关联字段详情）。",
                safeOperationId("rpc_" + modelName + "_find"),
                Map.of("args", Map.of("id", "<记录ID>")),
                Map.of("id", new StringSchema().example("<记录ID>")));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/count", tagName, true, true,
                "统计" + displayName + "数量 (count)" + stateSuffix,
                "按 Filter DSL 统计符合条件的记录数。",
                safeOperationId("rpc_" + modelName + "_count"),
                Map.of("args", Map.of("filters", List.of())),
                Map.of("filters", new ArraySchema().description("可选 Filter DSL 条件")));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/exists", tagName, true, true,
                "判断" + displayName + "是否存在 (exists)" + stateSuffix,
                "返回布尔值，校验记录或条件是否存在。",
                safeOperationId("rpc_" + modelName + "_exists"),
                Map.of("args", Map.of("filters", List.of())),
                Map.of("filters", new ArraySchema().description("Filter DSL 条件或 id")));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/addMissingDefaultValues", tagName, true, true,
                "初始化表单默认值 (addMissingDefaultValues)" + stateSuffix,
                "执行编码规则与默认表达式计算新增初始值。",
                safeOperationId("rpc_" + modelName + "_addMissingDefaultValues"),
                Map.of("args", Map.of("values", Map.of())),
                Map.of("values", new Schema<>().$ref("#/components/schemas/" + schemaKey)));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/create", tagName, true, true,
                "新增" + displayName + " (create)" + stateSuffix,
                "创建新记录，支持必填/唯一校验、自动编号与级联子表保存。values 字段结构见 Schema [" + schemaKey + "]。",
                safeOperationId("rpc_" + modelName + "_create"),
                Map.of("args", Map.of("values", sampleRecord)),
                Map.of("values", new Schema<>().$ref("#/components/schemas/" + schemaKey)));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/update", tagName, true, true,
                "更新" + displayName + " (update)" + stateSuffix,
                "按 id 修改字段值，自动校验乐观锁与约束规则。",
                safeOperationId("rpc_" + modelName + "_update"),
                Map.of("args", Map.of("id", "<记录ID>", "values", sampleRecord)),
                Map.of("id", new StringSchema().example("<记录ID>"),
                        "values", new Schema<>().$ref("#/components/schemas/" + schemaKey)));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/delete", tagName, true, true,
                "删除" + displayName + " (delete)" + stateSuffix,
                "支持传 ids 数组或单个 id 删除记录（自动校验 RESTRICT/CASCADE 引用约束）。",
                safeOperationId("rpc_" + modelName + "_delete"),
                Map.of("args", Map.of("ids", List.of("<记录ID>"))),
                Map.of("ids", new ArraySchema().items(new StringSchema()),
                        "id", new StringSchema().description("单个 ID 时也可传 id")));

        addServiceOperation(openApi, "/{tenant}/rpc/service/" + modelName + "/lookup", tagName, true, true,
                "下拉联想检索" + displayName + " (lookup)" + stateSuffix,
                "供 ManyToOne/ManyToMany 选择器快速模糊检索。",
                safeOperationId("rpc_" + modelName + "_lookup"),
                Map.of("args", Map.of("keyword", "", "limit", 20)),
                Map.of("keyword", new StringSchema().example(""),
                        "limit", new IntegerSchema()._default(20).example(20)));
    }

    private void addServiceOperation(OpenAPI openApi,
                                     String path,
                                     String tag,
                                     boolean hasTenantPath,
                                     boolean requiresBearer,
                                     String summary,
                                     String description,
                                     String operationId,
                                     Map<String, Object> exampleArgsWrapper,
                                     Map<String, Schema<?>> argProps) {
        ObjectSchema argsSchema = new ObjectSchema();
        argProps.forEach(argsSchema::addProperty);

        Map<String, Object> fullRpcExample = new LinkedHashMap<>();
        fullRpcExample.put("jsonrpc", "2.0");
        fullRpcExample.put("id", "1");
        fullRpcExample.put("method", "service");
        Map<String, Object> paramsMap = new LinkedHashMap<>();
        paramsMap.put("args", exampleArgsWrapper.getOrDefault("args", Collections.emptyMap()));
        fullRpcExample.put("params", paramsMap);

        ObjectSchema requestSchema = new ObjectSchema();
        requestSchema.addProperty("jsonrpc", new StringSchema()._default("2.0").example("2.0"));
        requestSchema.addProperty("id", new StringSchema()._default("1").example("1"));
        requestSchema.addProperty("method", new StringSchema()._default("service").example("service"));
        ObjectSchema paramsSchema = new ObjectSchema();
        paramsSchema.addProperty("args", argsSchema);
        paramsSchema.addProperty("context", new ObjectSchema().description("可选上下文，如 {\"lang\":\"zh-CN\"}"));
        requestSchema.addProperty("params", paramsSchema);
        requestSchema.setExample(fullRpcExample);

        Operation op = new Operation()
                .tags(List.of(tag))
                .summary(summary)
                .description(description)
                .operationId(operationId);

        if (hasTenantPath) {
            Parameter tenantParam = new PathParameter()
                    .name("tenant")
                    .required(true)
                    .description("租户编码，默认 rbac_tenant_root")
                    .schema(new StringSchema()._default("rbac_tenant_root").example("rbac_tenant_root"));
            op.addParametersItem(tenantParam);
        }

        if (requiresBearer) {
            op.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_BEARER));
        } else {
            op.setSecurity(Collections.emptyList());
        }

        op.requestBody(new RequestBody()
                .required(false)
                .description("标准 JSON-RPC 2.0 请求体（也兼容直接传入 {\"args\":{...}}）")
                .content(new Content().addMediaType("application/json", new MediaType().schema(requestSchema))));

        op.responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse()
                        .description("JSON-RPC 2.0 响应（成功返回 result，业务异常返回 error 对象）")
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/JsonRpcSuccessResponse"))))));

        PathItem pathItem = openApi.getPaths().computeIfAbsent(path, k -> new PathItem());
        pathItem.post(op);
    }

    private Schema<?> buildModelRecordSchema(ModelSnapshot snap) {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription(snap.getDisplayName() + " (" + snap.getName() + ")");
        List<String> requiredFields = new ArrayList<>();
        schema.addProperty("id", new StringSchema().description("主键 ID（新增时可不传，系统自动生成）"));
        if (snap.getProperties() != null) {
            for (PropertyDef p : snap.getProperties()) {
                if (p.getName() == null || "id".equals(p.getName())) continue;
                schema.addProperty(p.getName(), toPropertySchema(p));
                if (p.isRequired()) {
                    requiredFields.add(p.getName());
                }
            }
        }
        if (!requiredFields.isEmpty()) {
            schema.setRequired(requiredFields);
        }
        return schema;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Schema<?> toPropertySchema(PropertyDef p) {
        String dt = p.getDataType() != null ? p.getDataType() : "String";
        Schema<?> s;
        if (p.isMultiple()) {
            s = new ArraySchema().items(new StringSchema());
        } else {
            switch (dt.toLowerCase()) {
                case "integer", "long" -> s = new IntegerSchema();
                case "double", "decimal", "bigdecimal" -> s = new NumberSchema();
                case "boolean" -> s = new BooleanSchema();
                case "date" -> s = new DateSchema();
                case "datetime" -> s = new DateTimeSchema();
                case "onetomany", "manytomany" -> s = new ArraySchema().items(new ObjectSchema());
                case "json" -> s = new ObjectSchema();
                default -> s = new StringSchema();
            }
        }
        if (p.isSelection() && p.getValues() != null && !p.getValues().isEmpty() && !p.isMultiple()) {
            List<String> enums = p.getValues().stream()
                    .map(PropertyDef.SelectionOption::getValue)
                    .filter(Objects::nonNull)
                    .toList();
            if (!enums.isEmpty()) {
                ((Schema) s).setEnum(enums);
            }
        }
        StringBuilder desc = new StringBuilder();
        desc.append(p.getDisplayName() != null ? p.getDisplayName() : p.getName());
        desc.append(" [").append(dt).append("]");
        if (p.getRelateModel() != null && !p.getRelateModel().isBlank()) {
            desc.append(" -> 关联模型: ").append(p.getRelateModel());
        }
        if (p.getDictType() != null && !p.getDictType().isBlank()) {
            desc.append(" -> 字典: ").append(p.getDictType());
        }
        if (p.getCodeRule() != null && !p.getCodeRule().isBlank()) {
            desc.append(" (自动编号: ").append(p.getCodeRule()).append(")");
        }
        s.setDescription(desc.toString());
        Object sample = sampleValueForProperty(p);
        if (sample != null) {
            s.setExample(sample);
        }
        return s;
    }

    private Map<String, Object> buildSampleRecord(ModelSnapshot snap) {
        Map<String, Object> sample = new LinkedHashMap<>();
        if (snap.getProperties() != null) {
            for (PropertyDef p : snap.getProperties()) {
                if (p.getName() == null || "id".equals(p.getName()) || !p.isStoredColumn() || !p.isDisplay()) continue;
                if (sample.size() >= 6 && !p.isRequired()) continue;
                sample.put(p.getName(), sampleValueForProperty(p));
            }
        }
        return sample;
    }

    private Object sampleValueForProperty(PropertyDef p) {
        if (p.getDefaultValue() != null) return p.getDefaultValue();
        if (p.isSelection() && p.getValues() != null && !p.getValues().isEmpty()) {
            String first = p.getValues().get(0).getValue();
            return p.isMultiple() ? List.of(first) : first;
        }
        String dt = p.getDataType() != null ? p.getDataType().toLowerCase() : "string";
        if (p.isMultiple()) return List.of("ITEM_1");
        return switch (dt) {
            case "integer", "long" -> 1;
            case "double", "decimal", "bigdecimal" -> 10.5;
            case "boolean" -> true;
            case "date" -> "2026-01-01";
            case "datetime" -> "2026-01-01 09:00:00";
            case "onetomany", "manytomany" -> List.of();
            case "json" -> Map.of();
            default -> "示例" + (p.getDisplayName() != null ? p.getDisplayName() : p.getName());
        };
    }

    private String toComponentSchemaName(String modelName) {
        StringBuilder sb = new StringBuilder();
        for (String part : modelName.split("[^a-zA-Z0-9]+")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.isEmpty() ? "DynamicModel" : sb.toString();
    }

    private String safeOperationId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
