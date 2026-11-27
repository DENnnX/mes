package com.edu.scnu.application;

import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.metamodel.repository.FoundationRepository;
import com.edu.scnu.domain.metamodel.repository.ModelRepository;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 平台通用基础能力应用服务（数据字典、通用流水编码规则、附件台账、自动 Swagger 风格接口文档与权限目录）。
 */
@Service
public class FoundationAppService {
    private final FoundationRepository foundationRepo;
    private final ModelRepository modelRepo;
    private final MetaContainerMemoryRegistry memoryRegistry;

    public FoundationAppService(FoundationRepository foundationRepo,
                                ModelRepository modelRepo,
                                MetaContainerMemoryRegistry memoryRegistry) {
        this.foundationRepo = foundationRepo;
        this.modelRepo = modelRepo;
        this.memoryRegistry = memoryRegistry;
    }

    // ==================== 数据字典 ====================
    public List<DictTypeDef> listDictTypes() {
        return foundationRepo.findAllDictTypes(currentTenantOrNull());
    }

    public DictTypeDef saveDictType(DictTypeDef type) {
        if (type.getTypeCode() == null || type.getTypeCode().isBlank() || type.getName() == null || type.getName().isBlank()) {
            throw DomainException.invalidParams("字典编码 (typeCode) 与名称 (name) 不能为空");
        }
        if (type.getId() == null || type.getId().isBlank()) {
            type.setId("dt_" + type.getTypeCode().trim());
        }
        if (type.getTenantId() == null || type.getTenantId().isBlank()) {
            type.setTenantId(currentTenantOrDefault());
        }
        foundationRepo.saveDictType(type);
        return type;
    }

    public Map<String, Object> deleteDictType(String typeCode) {
        foundationRepo.deleteDictType(typeCode);
        return Map.of("deleted", true, "typeCode", typeCode);
    }

    public List<DictItemDef> listDictItems(String typeCode) {
        if (typeCode != null && !typeCode.isBlank()) {
            return foundationRepo.findDictItemsByType(typeCode);
        }
        return foundationRepo.findAllDictItems();
    }

    public List<DictItemDef> itemsByType(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            return Collections.emptyList();
        }
        List<DictItemDef> list = new ArrayList<>();
        for (DictItemDef item : foundationRepo.findDictItemsByType(typeCode)) {
            if ("ACTIVE".equalsIgnoreCase(item.getStatus())) {
                list.add(item);
            }
        }
        return list;
    }

    public DictItemDef saveDictItem(DictItemDef item) {
        if (item.getTypeCode() == null || item.getTypeCode().isBlank()
                || item.getItemValue() == null || item.getItemValue().isBlank()
                || item.getItemLabel() == null || item.getItemLabel().isBlank()) {
            throw DomainException.invalidParams("字典项必须包含 typeCode / itemValue / itemLabel");
        }
        if (item.getId() == null || item.getId().isBlank()) {
            item.setId("di_" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (item.getTenantId() == null || item.getTenantId().isBlank()) {
            item.setTenantId(currentTenantOrDefault());
        }
        foundationRepo.saveDictItem(item);
        return item;
    }

    public Map<String, Object> deleteDictItem(String id) {
        foundationRepo.deleteDictItem(id);
        return Map.of("deleted", true, "id", id);
    }

    // ==================== 通用编码规则 ====================
    public List<CodeRuleDef> listCodeRules() {
        return foundationRepo.findAllCodeRules(currentTenantOrNull());
    }

    public CodeRuleDef saveCodeRule(CodeRuleDef rule) {
        if (rule.getCode() == null || rule.getCode().isBlank() || rule.getName() == null || rule.getName().isBlank()) {
            throw DomainException.invalidParams("编码规则 code 与 name 不能为空");
        }
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId("cr_" + rule.getCode().trim());
        }
        if (rule.getSeqLength() <= 0 || rule.getSeqLength() > 12) {
            rule.setSeqLength(4);
        }
        if (rule.getTenantId() == null || rule.getTenantId().isBlank()) {
            rule.setTenantId(currentTenantOrDefault());
        }
        foundationRepo.saveCodeRule(rule);
        return rule;
    }

    public Map<String, Object> deleteCodeRule(String code) {
        foundationRepo.deleteCodeRule(code);
        return Map.of("deleted", true, "code", code);
    }

    public Map<String, Object> generateCode(String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw DomainException.invalidParams("生成编码必须传入 ruleCode 参数");
        }
        String code = foundationRepo.nextSequenceCode(ruleCode, currentTenantOrDefault());
        if (code == null) {
            throw DomainException.invalidParams("编码规则不存在或已停用: " + ruleCode);
        }
        return Map.of("ruleCode", ruleCode, "generatedCode", code);
    }

    // ==================== 附件列表 ====================
    public List<Map<String, Object>> listFiles() {
        return foundationRepo.findAllFileMeta(currentTenantOrNull());
    }

    // ==================== 角色权限目录 ====================
    public List<Map<String, Object>> buildPermissionCatalog() {
        return buildPermissionCatalog(true, null);
    }

    public List<Map<String, Object>> buildPermissionCatalog(boolean isRootAdmin, Set<String> allowedApps) {
        List<Map<String, Object>> groups = new ArrayList<>();

        if (isRootAdmin) {
            groups.add(permissionGroup("kernel", "平台基础与认证权限", List.of(
                    perm("*", "全平台超级权限 (*)", "*"),
                    perm("ui_*:*", "UI 动态菜单与视图加载 (ui_*:*)", "ui_*:*"),
                    perm("meta_dict:*", "数据字典管理 (meta_dict:*)", "meta_dict:*"),
                    perm("meta_code_rule:*", "编码规则管理 (meta_code_rule:*)", "meta_code_rule:*"),
                    perm("meta_file:*", "附件上传下载 (meta_file:*)", "meta_file:*")
            )));

            groups.add(permissionGroup("builtin_wildcard", "业务模型通用操作通配符", List.of(
                    perm("*:search", "所有模型查询列表 (*:search)", "*:search"),
                    perm("*:find", "所有模型单条详情 (*:find)", "*:find"),
                    perm("*:count", "所有模型数量统计 (*:count)", "*:count"),
                    perm("*:exists", "所有模型存在检查 (*:exists)", "*:exists"),
                    perm("*:lookup", "所有模型外键下拉 (*:lookup)", "*:lookup"),
                    perm("*:addMissingDefaultValues", "新增默认值初始化 (*:addMissingDefaultValues)", "*:addMissingDefaultValues"),
                    perm("*:getSelectionOptions", "动态下拉选项加载 (*:getSelectionOptions)", "*:getSelectionOptions"),
                    perm("*:create", "所有模型新建记录 (*:create)", "*:create"),
                    perm("*:update", "所有模型更新记录 (*:update)", "*:update"),
                    perm("*:delete", "所有模型删除记录 (*:delete)", "*:delete")
            )));
        } else {
            groups.add(permissionGroup("tenant_common", "分站业务通用运行权限", List.of(
                    perm("ui_*:*", "UI 页面与菜单加载 (ui_*:*)", "ui_*:*"),
                    perm("*:lookup", "跨菜单外键下拉参照 (*:lookup)", "*:lookup"),
                    perm("*:addMissingDefaultValues", "表单默认值初始化 (*:addMissingDefaultValues)", "*:addMissingDefaultValues"),
                    perm("*:getSelectionOptions", "动态选项读取 (*:getSelectionOptions)", "*:getSelectionOptions"),
                    perm("meta_dict:itemsByType", "数据字典选项读取 (meta_dict:itemsByType)", "meta_dict:itemsByType"),
                    perm("meta_dict:listTypes", "数据字典分类读取 (meta_dict:listTypes)", "meta_dict:listTypes"),
                    perm("meta_code_rule:generateCode", "自动流水编号生成 (meta_code_rule:generateCode)", "meta_code_rule:generateCode"),
                    perm("meta_file:*", "附件上传查看 (meta_file:*)", "meta_file:*"),
                    perm("rbac_org:listOrgs", "查看本企业组织部门 (rbac_org:listOrgs)", "rbac_org:listOrgs")
            )));
        }

        for (ModelAggregate agg : modelRepo.findAllModels()) {
            if (agg.isKernelModel() || agg.getLifecycleState() == ModelAggregate.LifecycleState.PURGED) {
                continue;
            }
            if (!isRootAdmin) {
                String appName = agg.getAppName();
                if (appName == null || "platform_kernel".equals(appName)) {
                    continue;
                }
                if (allowedApps != null && !allowedApps.contains(appName)) {
                    continue;
                }
            }
            ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(agg.getName());
            if (snap == null || snap.isAbstractModel() || snap.isExtensionPackage()) {
                continue;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(perm(agg.getName() + ":*", agg.getDisplayName() + " 全部服务", agg.getName() + ":*"));
            items.add(perm(agg.getName() + ":search", "查询列表 (search)", agg.getName() + ":search"));
            items.add(perm(agg.getName() + ":find", "单条详情 (find)", agg.getName() + ":find"));
            items.add(perm(agg.getName() + ":create", "新增记录 (create)", agg.getName() + ":create"));
            items.add(perm(agg.getName() + ":update", "修改记录 (update)", agg.getName() + ":update"));
            items.add(perm(agg.getName() + ":delete", "删除记录 (delete)", agg.getName() + ":delete"));
            items.add(perm(agg.getName() + ":lookup", "外键下拉 (lookup)", agg.getName() + ":lookup"));
            for (ServiceDef s : snap.getServices()) {
                String authKey = (s.getAuth() != null && !s.getAuth().isBlank()) ? s.getAuth() : s.getName();
                items.add(perm(agg.getName() + ":" + authKey,
                        (s.getDisplayName() != null ? s.getDisplayName() : s.getName()) + " (" + authKey + ")",
                        agg.getName() + ":" + authKey));
            }
            groups.add(permissionGroup("model_" + agg.getName(), "业务模型: " + agg.getDisplayName() + " (" + agg.getName() + ")", items));
        }
        return groups;
    }

    private Map<String, Object> permissionGroup(String key, String label, List<Map<String, Object>> items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("groupKey", key);
        g.put("groupLabel", label);
        g.put("items", items);
        return g;
    }

    private Map<String, Object> perm(String key, String label, String authExpr) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("label", label);
        p.put("authExpr", authExpr);
        return p;
    }

    // ==================== 自动 API 接口文档（对标 Swagger） ====================
    public List<Map<String, Object>> listApiCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();

        catalog.add(apiCategory("platform_iam", "平台认证、多租户、组织与 RBAC", List.of(
                apiItem("rbac_user.login", "用户登录获取 Token", Map.of("username", "admin", "password", "123456")),
                apiItem("rbac_tenant.listTenants", "查询租户列表", Map.of()),
                apiItem("rbac_tenant.authorizeApps", "租户授权应用集合", Map.of("tenantId", "rbac_tenant_root", "authorizedApps", List.of())),
                apiItem("rbac_org.listOrgs", "查询组织架构树", Map.of("tenantId", "rbac_tenant_root")),
                apiItem("rbac_role.permissionCatalog", "查询权限目录树", Map.of())
        )));

        catalog.add(apiCategory("platform_foundation", "基础数据中心 (字典 / 编码规则 / 附件)", List.of(
                apiItem("meta_dict.listTypes", "查询字典类型", Map.of()),
                apiItem("meta_dict.itemsByType", "按类型查询字典项", Map.of("typeCode", "DICT_STATUS")),
                apiItem("meta_code_rule.listRules", "查询编码规则列表", Map.of()),
                apiItem("meta_code_rule.generateCode", "按编码规则生成下一个流水码", Map.of("ruleCode", "ORDER_CODE")),
                apiItem("meta_file.listFiles", "查询附件元数据列表", Map.of())
        )));

        for (ModelAggregate agg : modelRepo.findAllModels()) {
            if (agg.getLifecycleState() == ModelAggregate.LifecycleState.PURGED) continue;
            ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(agg.getName());
            if (snap == null || snap.isAbstractModel() || snap.isExtensionPackage()) continue;

            List<Map<String, Object>> endpoints = buildModelEndpoints(snap);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", "model_" + snap.getName());
            entry.put("title", (agg.isKernelModel() ? "[内核] " : "[业务] ") + snap.getDisplayName() + " (" + snap.getName() + ")");
            entry.put("modelName", snap.getName());
            entry.put("appName", snap.getAppName());
            entry.put("lifecycleState", agg.getLifecycleState().name());
            entry.put("parents", snap.getParents());
            entry.put("endpoints", endpoints);
            catalog.add(entry);
        }
        return catalog;
    }

    public Map<String, Object> describeModelApi(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw DomainException.invalidParams("describeModel 必须传入 modelName 参数");
        }
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(modelName);
        if (snap == null) {
            List<ModelVersion> versions = modelRepo.findVersionsByModel(modelName);
            if (!versions.isEmpty()) snap = versions.get(0).getSnapshot();
        }
        if (snap == null) {
            throw DomainException.invalidParams("模型无版本快照: " + modelName);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", snap.getName());
        meta.put("displayName", snap.getDisplayName());
        meta.put("appName", snap.getAppName());
        meta.put("tableName", snap.resolvedTableName());
        meta.put("versionNo", snap.getVersionNo());
        meta.put("lifecycleState", agg.getLifecycleState().name());
        meta.put("parents", snap.getParents());
        meta.put("abstractModel", snap.isAbstractModel());
        meta.put("extendModel", snap.getExtendModel());
        meta.put("indexes", snap.getIndexes());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelMeta", meta);
        out.put("properties", snap.getProperties());
        out.put("endpoints", buildModelEndpoints(snap));
        return out;
    }

    private List<Map<String, Object>> buildModelEndpoints(ModelSnapshot snap) {
        String m = snap.getName();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(apiItem(m + ".search", "条件分页查询 (" + snap.getDisplayName() + ")", Map.of(
                "filter", List.of(),
                "fields", List.of(),
                "limit", 20,
                "offset", 0
        )));
        list.add(apiItem(m + ".count", "条件计数", Map.of("filter", List.of())));
        list.add(apiItem(m + ".exists", "存在性判断", Map.of("filter", List.of())));
        list.add(apiItem(m + ".find", "按主键查询单条明细", Map.of("id", "<record-id>")));
        list.add(apiItem(m + ".addMissingDefaultValues", "获取字段新增默认值", Map.of("values", Map.of())));

        Map<String, Object> sampleCreateValues = new LinkedHashMap<>();
        for (PropertyDef p : snap.getProperties()) {
            if (p.isAutoColumn() || !p.isStore()) continue;
            if (p.getDefaultValue() != null) {
                sampleCreateValues.put(p.getName(), p.getDefaultValue());
            } else if ("Integer".equalsIgnoreCase(p.getDataType()) || "Long".equalsIgnoreCase(p.getDataType())) {
                sampleCreateValues.put(p.getName(), 1);
            } else if ("Double".equalsIgnoreCase(p.getDataType()) || "Decimal".equalsIgnoreCase(p.getDataType())) {
                sampleCreateValues.put(p.getName(), 10.0);
            } else if ("Boolean".equalsIgnoreCase(p.getDataType())) {
                sampleCreateValues.put(p.getName(), false);
            } else if (!p.isOneToMany() && !p.isManyToMany()) {
                sampleCreateValues.put(p.getName(), p.getCodeRule() != null ? "" : "<" + p.getName() + ">");
            }
        }
        list.add(apiItem(m + ".create", "新增业务记录 (触发默认值/自动编码/@Validate/validate钩子)", Map.of("values", sampleCreateValues)));
        list.add(apiItem(m + ".update", "按 ID 局部更新记录", Map.of("id", "<record-id>", "values", sampleCreateValues)));
        list.add(apiItem(m + ".delete", "按 ID 集合删除记录", Map.of("ids", List.of("<record-id>"))));
        list.add(apiItem(m + ".lookup", "外键模糊搜索下拉选项", Map.of("keyword", "", "limit", 30)));

        for (ServiceDef s : snap.getServices()) {
            if (ServiceDef.TYPE_BUILTIN_CRUD.equalsIgnoreCase(s.getType())) continue;
            Map<String, Object> customArgs = new LinkedHashMap<>();
            customArgs.put("id", "<record-id>");
            if (s.getParamsSchema() != null) {
                for (PropertyDef param : s.getParamsSchema()) {
                    customArgs.put(param.getName(),
                            param.getDefaultValue() != null ? param.getDefaultValue() : "<" + param.getDataType() + ">");
                }
            }
            list.add(apiItem(m + "." + s.getName(),
                    s.getDisplayName() + " [" + s.getType() + "] auth=" + s.getAuth(),
                    customArgs));
        }
        return list;
    }

    private Map<String, Object> apiCategory(String category, String title, List<Map<String, Object>> endpoints) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("title", title);
        m.put("endpoints", endpoints);
        return m;
    }

    private Map<String, Object> apiItem(String rpcMethod, String summary, Map<String, Object> sampleArgs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", "doc-1");
        payload.put("method", rpcMethod);
        payload.put("params", Map.of("args", sampleArgs));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rpcMethod", rpcMethod);
        m.put("summary", summary);
        m.put("sampleArgs", sampleArgs);
        m.put("requestTemplate", payload);
        return m;
    }

    private String currentTenantOrNull() {
        MetaContext ctx = MetaContext.current();
        if (ctx == null || ctx.isSuperUser()) return null;
        return ctx.getTenantId();
    }

    private String currentTenantOrDefault() {
        MetaContext ctx = MetaContext.current();
        return (ctx != null && ctx.getTenantId() != null && !ctx.getTenantId().isBlank())
                ? ctx.getTenantId() : MetaContext.DEFAULT_TENANT;
    }
}
