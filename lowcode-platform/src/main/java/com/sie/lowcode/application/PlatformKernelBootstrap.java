package com.sie.lowcode.application;

import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.metamodel.repository.FoundationRepository;
import com.sie.lowcode.domain.metamodel.repository.ModelRepository;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.infrastructure.container.MetaContainerMemoryRegistry;
import com.sie.lowcode.domain.uischema.UiSchemaRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * 平台内核自举引导器：
 * 1) 注册内置 RBAC 角色与默认账号（仅保留默认系统管理员 admin 与示例业务用户 user）；
 * 2) 注册纯平台自举元数据（仅平台自身治理所需模型，不包含任何硬编码业务示例）；
 * 3) 在 ExtensionRegistry 中挂载引擎自举服务重写链（ui_menu.loadMenu / ui_view.loadView / rbac_user.login 等）。
 */
@Service
public class PlatformKernelBootstrap {
    private static final JsonMapper MODEL_INPUT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final IamAppService iamAppService;
    private final MetaGovernanceAppService govService;
    private final UiSchemaAppService uiService;
    private final MetaContainerMemoryRegistry registry;
    private final ModelRepository modelRepo;
    private final UiSchemaRepository uiRepo;
    private final FoundationRepository foundationRepo;

    public PlatformKernelBootstrap(IamAppService iamAppService,
                                   MetaGovernanceAppService govService,
                                   UiSchemaAppService uiService,
                                   MetaContainerMemoryRegistry registry,
                                   ModelRepository modelRepo,
                                   UiSchemaRepository uiRepo,
                                   FoundationRepository foundationRepo) {
        this.iamAppService = iamAppService;
        this.govService = govService;
        this.uiService = uiService;
        this.registry = registry;
        this.modelRepo = modelRepo;
        this.uiRepo = uiRepo;
        this.foundationRepo = foundationRepo;
    }

    @PostConstruct
    public void bootstrap() {
        registry.reloadAll();
        seedIamRolesAndUsers();
        seedDefaultDictionaries();
        seedKernelSelfHostingModels();
        registerPlatformExtensionHandlers();
        registry.reloadAll();
    }

    private void seedIamRolesAndUsers() {
        if (iamAppService.listTenants().isEmpty()) {
            com.sie.lowcode.domain.iam.TenantDef rootTenant = new com.sie.lowcode.domain.iam.TenantDef();
            rootTenant.setId("rbac_tenant_root");
            rootTenant.setCode("rbac_tenant_root");
            rootTenant.setName("默认平台根租户");
            rootTenant.setContactName("系统管理员");
            rootTenant.setAuthorizedApps(java.util.List.of("*"));
            rootTenant.setStatus("ACTIVE");
            iamAppService.saveTenant(rootTenant);
        }
        if (iamAppService.listOrgs("rbac_tenant_root").isEmpty()) {
            com.sie.lowcode.domain.iam.OrgNode rootOrg = new com.sie.lowcode.domain.iam.OrgNode();
            rootOrg.setId("org_root");
            rootOrg.setTenantId("rbac_tenant_root");
            rootOrg.setName("平台根组织");
            rootOrg.setOrgType("ROOT");
            rootOrg.setSequence(1);
            iamAppService.saveOrg(rootOrg);
        }

        if (iamAppService.listRoles().isEmpty()) {
            com.sie.lowcode.domain.iam.RoleDef adminRole = new com.sie.lowcode.domain.iam.RoleDef();
            adminRole.setId("role_admin");
            adminRole.setCode("ROLE_ADMIN");
            adminRole.setName("系统管理员 (全量管理/建模发布/安全清理权限)");
            adminRole.setDataScope("ALL");
            adminRole.setAllowedServices(Set.of("*"));
            iamAppService.saveRole(adminRole);

            com.sie.lowcode.domain.iam.RoleDef devRole = new com.sie.lowcode.domain.iam.RoleDef();
            devRole.setId("role_developer");
            devRole.setCode("ROLE_DEVELOPER");
            devRole.setName("模型开发者 (JSON建模/影响检查)");
            devRole.setDataScope("ALL");
            devRole.setAllowedServices(Set.of("*"));
            iamAppService.saveRole(devRole);

            com.sie.lowcode.domain.iam.RoleDef pubRole = new com.sie.lowcode.domain.iam.RoleDef();
            pubRole.setId("role_publisher");
            pubRole.setCode("ROLE_PUBLISHER");
            pubRole.setName("发布管理员 (发布/回滚/下线/申请清除)");
            pubRole.setDataScope("ALL");
            pubRole.setAllowedServices(Set.of("*"));
            iamAppService.saveRole(pubRole);

            com.sie.lowcode.domain.iam.RoleDef userRole = new com.sie.lowcode.domain.iam.RoleDef();
            userRole.setId("role_app_user");
            userRole.setCode("ROLE_APP_USER");
            userRole.setName("应用使用人员 (动态页面操作)");
            userRole.setDataScope("ALL");
            userRole.setAllowedServices(Set.of(
                    "*:search", "*:count", "*:exists", "*:find", "*:create", "*:update", "*:delete",
                    "*:lookup", "*:action", "*:addMissingDefaultValues", "*:getSelectionOptions",
                    "ui_*:*", "meta_dict:itemsByType", "meta_dict:listTypes", "meta_code_rule:generateCode",
                    "meta_file:*", "api_doc:*", "rbac_org:listOrgs"));
            iamAppService.saveRole(userRole);
        }

        Set<String> tenantAdminRequiredServices = Set.of(
                "*:search", "*:count", "*:exists", "*:find", "*:create", "*:update", "*:delete",
                "*:lookup", "*:action", "*:addMissingDefaultValues", "*:getSelectionOptions",
                "ui_*:*", "ui_menu:listAssignableMenus", "meta_dict:itemsByType", "meta_dict:listTypes",
                "meta_code_rule:generateCode", "meta_file:*",
                "rbac_tenant:listTenants", "rbac_tenant:search", "rbac_tenant:saveTenant",
                "rbac_tenant:authorizeApps", "rbac_tenant:authorizeMenus",
                "rbac_user:listUsers", "rbac_user:search", "rbac_user:saveUser", "rbac_user:resetPassword", "rbac_user:deleteUser",
                "rbac_org:*",
                "rbac_role:listRoles", "rbac_role:search", "rbac_role:saveRole", "rbac_role:deleteRole", "rbac_role:permissionCatalog");
        Optional<com.sie.lowcode.domain.iam.RoleDef> existingTenantAdmin = iamAppService.listRoles().stream()
                .filter(r -> "ROLE_TENANT_ADMIN".equals(r.getCode()))
                .findFirst();
        if (existingTenantAdmin.isEmpty()) {
            com.sie.lowcode.domain.iam.RoleDef tAdminRole = new com.sie.lowcode.domain.iam.RoleDef();
            tAdminRole.setId("role_tenant_admin");
            tAdminRole.setCode("ROLE_TENANT_ADMIN");
            tAdminRole.setName("企业/分站主管 (本租户及下属分站授权与账号管理)");
            tAdminRole.setDataScope("ALL");
            tAdminRole.setAllowedServices(new LinkedHashSet<>(tenantAdminRequiredServices));
            iamAppService.saveRole(tAdminRole);
        } else {
            com.sie.lowcode.domain.iam.RoleDef tAdminRole = existingTenantAdmin.get();
            Set<String> merged = new LinkedHashSet<>(tAdminRole.getAllowedServices());
            if (merged.addAll(tenantAdminRequiredServices)) {
                tAdminRole.setAllowedServices(merged);
                iamAppService.saveRole(tAdminRole);
            }
        }

        if (iamAppService.listUsers().isEmpty()) {
            createUser("usr_admin", "admin", "系统管理员 (Admin)", "123456", Set.of("ROLE_ADMIN", "ROLE_DEVELOPER", "ROLE_PUBLISHER"));
            createUser("usr_app", "user", "示例业务用户 (User)", "123456", Set.of("ROLE_APP_USER"));
        }
    }

    /** 初始化若干高频通用的示例选项字典，开箱即可在任意数据表字段中引用 */
    private void seedDefaultDictionaries() {
        if (!foundationRepo.findAllDictTypes(null).isEmpty()) {
            return;
        }
        seedDict("SYS_URGENCY", "紧急程度", "适用于工单、任务与报修单优先级", List.of(
                new String[]{"LOW", "普通", "info", "10"},
                new String[]{"NORMAL", "加急", "warning", "20"},
                new String[]{"URGENT", "特急", "danger", "30"}
        ));
        seedDict("SYS_APPROVAL_STATUS", "审批状态", "适用于单据通用审核流转状态展示", List.of(
                new String[]{"DRAFT", "草稿", "info", "10"},
                new String[]{"PENDING", "审批中", "warning", "20"},
                new String[]{"APPROVED", "已通过", "success", "30"},
                new String[]{"REJECTED", "已驳回", "danger", "40"}
        ));
        seedDict("SYS_QUALITY_RESULT", "检验结论", "适用于巡检、质检与验收判定结果", List.of(
                new String[]{"PASS", "合格", "success", "10"},
                new String[]{"RECHECK", "待复检", "warning", "20"},
                new String[]{"FAIL", "不合格", "danger", "30"}
        ));
        seedDict("SYS_ENABLE_STATUS", "启用状态", "适用于基础档案启停标记", List.of(
                new String[]{"ENABLED", "正常启用", "success", "10"},
                new String[]{"DISABLED", "已停用", "info", "20"}
        ));
    }

    private void seedDict(String code, String name, String remark, List<String[]> items) {
        DictTypeDef type = new DictTypeDef();
        type.setId("dt_" + code);
        type.setTypeCode(code);
        type.setName(name);
        type.setRemark(remark);
        type.setStatus("ACTIVE");
        type.setTenantId(MetaContext.DEFAULT_TENANT);
        foundationRepo.saveDictType(type);

        for (String[] row : items) {
            DictItemDef item = new DictItemDef();
            item.setId("di_" + code.toLowerCase() + "_" + row[0].toLowerCase());
            item.setTypeCode(code);
            item.setItemValue(row[0]);
            item.setItemLabel(row[1]);
            item.setColor(row[2]);
            item.setSequence(Integer.parseInt(row[3]));
            item.setStatus("ACTIVE");
            item.setTenantId(MetaContext.DEFAULT_TENANT);
            foundationRepo.saveDictItem(item);
        }
    }

    private void createUser(String id, String username, String displayName, String pwd, Set<String> roles) {
        com.sie.lowcode.domain.iam.UserAccount u = new com.sie.lowcode.domain.iam.UserAccount();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPassword(pwd);
        u.setRoleCodes(roles);
        iamAppService.saveUser(u);
    }

    private void seedKernelSelfHostingModels() {
        AppAggregate kernelApp = new AppAggregate();
        kernelApp.setId("platform_kernel");
        kernelApp.setName("platform_kernel");
        kernelApp.setDisplayName("平台开发与治理内核");
        kernelApp.setCategory("system");
        kernelApp.setVersion("3.0.0");
        kernelApp.setDescription("对标 sie-iidp-dev-center 与 iam 的低代码自举内核应用，不含任何预设业务示例。");
        govService.saveApp(kernelApp);

        // 注册自举内核模型（只读/治理，允许动态检索）
        ensureKernelModel("meta_app", "应用包管理", "meta_app", "name ASC", List.of(
                prop("name", "应用标识", "String", true, true),
                prop("display_name", "应用名称", "String", true, false),
                prop("category", "分类", "String", false, false),
                prop("version", "版本号", "String", false, false),
                prop("state", "安装状态", "String", false, false)
        ));
        ensureKernelModel("meta_model", "元模型台账", "meta_model", "name ASC", List.of(
                prop("name", "模型标识", "String", true, true),
                prop("display_name", "模型名称", "String", true, false),
                prop("app_name", "所属应用", "String", false, false),
                prop("table_name", "物理数据表", "String", true, false),
                prop("lifecycle_state", "生命周期", "String", false, false)
        ));
        ensureKernelModel("meta_publish_record", "发布回滚记录", "meta_publish_record", "operated_at DESC", List.of(
                prop("model_name", "模型标识", "String", true, false),
                prop("to_version_no", "目标版本", "String", false, false),
                prop("action_type", "动作类型", "String", false, false),
                prop("operator_id", "操作者", "String", false, false),
                prop("operated_at", "操作时间", "DateTime", false, false)
        ));
        ensureKernelModel("meta_purge_audit", "清除追溯审计", "meta_purge_audit", "purged_at DESC", List.of(
                prop("audit_id", "审计编号", "String", true, true),
                prop("model_name", "原模型标识", "String", false, false),
                prop("table_name", "原物理表", "String", false, false),
                prop("apply_by", "发起人", "String", false, false),
                prop("confirm_by", "独立确认人", "String", false, false),
                prop("purged_at", "清除时间", "DateTime", false, false)
        ));
        uiRepo.deleteMenusByApp("platform_kernel");
    }

    private void ensureKernelModel(String name, String displayName, String tableName, String orderBy, List<PropertyDef> props) {
        Optional<ModelAggregate> existing = modelRepo.findModelByName(name);
        if (existing.isPresent()) {
            ModelAggregate agg = existing.get();
            if (!agg.isKernelModel()) {
                agg.setKernelModel(true);
                modelRepo.saveModel(agg);
            }
            uiRepo.deleteMenusByModel(name);
            uiRepo.deleteViewsByModel(name);
            return;
        }
        ModelSnapshot snap = new ModelSnapshot();
        snap.setName(name);
        snap.setDisplayName(displayName);
        snap.setAppName("platform_kernel");
        snap.setTableName(tableName);
        snap.setAutoLog(false);
        snap.setLogicDelete(false);
        snap.setOrderBy(orderBy);
        snap.setProperties(props);

        ModelVersion ver = govService.saveModelDraft(snap);
        ImpactReport report = govService.checkImpact(name, ver.getId());
        govService.publishModel(name, ver.getId(), report.getValidationHash());
        modelRepo.findModelByName(name).ifPresent(agg -> {
            agg.setKernelModel(true);
            modelRepo.saveModel(agg);
        });
        uiRepo.deleteMenusByModel(name);
        uiRepo.deleteViewsByModel(name);
    }

    private PropertyDef prop(String name, String display, String type, boolean displayForModel, boolean required) {
        PropertyDef p = new PropertyDef();
        p.setName(name);
        p.setDisplayName(display);
        p.setDataType(type);
        p.setDisplayForModel(displayForModel);
        p.setRequired(required);
        return p;
    }

    private void registerPlatformExtensionHandlers() {
        var ext = registry.getExtensionRegistry();

        // UI & Menu 服务
        ext.registerOverride("ui_menu", "loadMenu", "kernel", (rs, args) -> uiService.loadMenu());
        ext.registerOverride("ui_menu", "listAssignableMenus", "kernel", (rs, args) -> uiService.listAssignableMenus());
        ext.registerOverride("ui_view", "loadView", "kernel", (rs, args) ->
                uiService.loadView(str(args.get("model")), str(args.get("type")), str(args.get("menu"))));

        // 应用包治理服务
        ext.registerOverride("meta_app", "list", "kernel", (rs, args) -> govService.listApps());
        ext.registerOverride("meta_app", "save", "kernel", (rs, args) -> {
            Object raw = args.get("app") != null ? args.get("app") : (args.get("values") != null ? args.get("values") : args);
            AppAggregate appAgg = MODEL_INPUT_MAPPER.convertValue(raw, AppAggregate.class);
            return govService.saveApp(appAgg);
        });
        ext.registerOverride("meta_app", "importPackage", "kernel", (rs, args) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> pkg = args.get("package") instanceof Map ? (Map<String, Object>) args.get("package") : args;
            boolean autoPublish = args.get("autoPublish") == null || Boolean.TRUE.equals(args.get("autoPublish"));
            return govService.importAppPackage(pkg, autoPublish);
        });
        ext.registerOverride("meta_app", "exportPackage", "kernel", (rs, args) ->
                govService.exportAppPackage(str(args.get("appName"))));

        // 模型与版本治理服务
        ext.registerOverride("meta_model", "listModels", "kernel", (rs, args) -> govService.listModelsSummary());
        ext.registerOverride("meta_model", "detail", "kernel", (rs, args) -> govService.getModelDetail(str(args.get("modelName"))));
        ext.registerOverride("meta_model", "saveDraft", "kernel", (rs, args) -> {
            ModelSnapshot snap = MODEL_INPUT_MAPPER.convertValue(args.get("snapshot") != null ? args.get("snapshot") : args, ModelSnapshot.class);
            return govService.saveModelDraft(snap);
        });
        ext.registerOverride("meta_model", "buildDefaultViews", "kernel", (rs, args) ->
                govService.buildDefaultViews(str(args.get("modelName"))));
        ext.registerOverride("meta_model", "checkImpact", "kernel", (rs, args) ->
                govService.checkImpact(str(args.get("modelName")), str(args.get("versionId"))));
        ext.registerOverride("meta_model", "publish", "kernel", (rs, args) ->
                govService.publishModel(str(args.get("modelName")), str(args.get("versionId")), str(args.get("validationHash"))));
        ext.registerOverride("meta_model", "rollback", "kernel", (rs, args) ->
                govService.rollbackModel(str(args.get("modelName")), str(args.get("targetVersionId"))));
        ext.registerOverride("meta_model", "offline", "kernel", (rs, args) ->
                govService.offlineModel(str(args.get("modelName"))));
        ext.registerOverride("meta_model", "online", "kernel", (rs, args) ->
                govService.onlineModel(str(args.get("modelName"))));
        ext.registerOverride("meta_model", "exportData", "kernel", (rs, args) ->
                govService.exportModelData(str(args.get("modelName"))));
        ext.registerOverride("meta_model", "applyPurge", "kernel", (rs, args) ->
                govService.applyPurge(str(args.get("modelName")), str(args.get("exportTicket"))));
        ext.registerOverride("meta_model", "confirmPurge", "kernel", (rs, args) ->
                govService.confirmPurge(str(args.get("applyId"))));
        ext.registerOverride("meta_model", "rejectPurge", "kernel", (rs, args) ->
                govService.rejectPurge(str(args.get("applyId")), str(args.get("reason"))));
        ext.registerOverride("meta_model", "listPublishRecords", "kernel", (rs, args) ->
                govService.listPublishRecords(str(args.get("modelName"))));
        ext.registerOverride("meta_model", "listPurgeApplies", "kernel", (rs, args) -> govService.listPurgeApplies());
        ext.registerOverride("meta_model", "listPurgeAudits", "kernel", (rs, args) -> govService.listPurgeAudits());
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
