package com.sie.lowcode.application;

import com.sie.lowcode.domain.iam.RoleDef;
import com.sie.lowcode.domain.iam.TenantDef;
import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.metamodel.repository.FoundationRepository;
import com.sie.lowcode.domain.metamodel.repository.ModelRepository;
import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.domain.uischema.*;
import com.sie.lowcode.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * UI 契约编排应用服务（严格对标 sie-iidp-iam 的 UiMenu.loadMenu + UiView.loadView）。
 * 集成多租户应用授权过滤、RBAC 细粒度视图按钮权限计算、数据字典与动态下拉选项下发。
 */
@Service
public class UiSchemaAppService {
    private final UiSchemaRepository uiRepo;
    private final ModelRepository modelRepo;
    private final MetaContainerMemoryRegistry memoryRegistry;
    private final IamAppService iamAppService;
    private final FoundationRepository foundationRepo;

    public UiSchemaAppService(UiSchemaRepository uiRepo,
                              ModelRepository modelRepo,
                              MetaContainerMemoryRegistry memoryRegistry,
                              IamAppService iamAppService,
                              FoundationRepository foundationRepo) {
        this.uiRepo = uiRepo;
        this.modelRepo = modelRepo;
        this.memoryRegistry = memoryRegistry;
        this.iamAppService = iamAppService;
        this.foundationRepo = foundationRepo;
    }

    /**
     * 动态加载前端导航菜单树与动态路由信息。
     * 自动过滤：已下线/已清除模型、抽象模型、扩展模型及当前租户未授权应用菜单。
     */
    public List<Map<String, Object>> loadMenu() {
        List<MenuNode> allMenus = uiRepo.findAllMenus();
        Map<String, ModelAggregate> modelMap = new HashMap<>();
        for (ModelAggregate m : modelRepo.findAllModels()) {
            modelMap.put(m.getName(), m);
        }
        Set<String> allowedApps = resolveTenantAllowedApps();

        List<MenuNode> activeMenus = new ArrayList<>();
        for (MenuNode menu : allMenus) {
            if (!menu.isActive()) continue;
            if (allowedApps != null && menu.getAppName() != null
                    && !"platform_kernel".equals(menu.getAppName())
                    && !allowedApps.contains(menu.getAppName())) {
                continue;
            }
            if (menu.getModel() != null && !menu.getModel().isBlank()) {
                ModelAggregate agg = modelMap.get(menu.getModel());
                if (agg == null || !agg.isAvailableForRuntime()) {
                    continue;
                }
                ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(menu.getModel());
                if (snap != null && (snap.isAbstractModel() || snap.isExtensionPackage())) {
                    continue;
                }
            }
            activeMenus.add(menu);
        }

        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        for (MenuNode m : activeMenus) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("name", m.getName());
            item.put("displayName", m.getDisplayName());
            item.put("appName", m.getAppName());
            item.put("parentId", m.getParentId());
            item.put("sequence", m.getSequence());
            item.put("icon", m.getIcon());
            item.put("model", m.getModel());
            item.put("view", m.getView());
            item.put("filter", m.getFilter());
            item.put("routePath", (m.getModel() != null && !m.getModel().isBlank()) ? "/m/" + m.getModel() : null);
            item.put("children", new ArrayList<Map<String, Object>>());
            nodeMap.put(m.getName(), item);
            if (m.getId() != null) {
                nodeMap.putIfAbsent(m.getId(), item);
            }
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        Set<String> seenRootNames = new HashSet<>();
        for (MenuNode m : activeMenus) {
            Map<String, Object> cur = nodeMap.get(m.getName());
            String pid = m.getParentId();
            if (pid != null && !pid.isBlank() && nodeMap.containsKey(pid) && nodeMap.get(pid) != cur) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) nodeMap.get(pid).get("children");
                children.add(cur);
            } else if (seenRootNames.add(m.getName())) {
                roots.add(cur);
            }
        }
        if (allowedApps != null) {
            roots.removeIf(r -> r.get("model") == null && ((List<?>) r.get("children")).isEmpty());
        }
        return roots;
    }

    /**
     * 加载模型三件套视图契约 + 有效字段元数据 + 字典/动态枚举项 + 服务清单 + RBAC 细粒度权限上下文。
     */
    public Map<String, Object> loadView(String modelName, String viewKeysCsv) {
        if (modelName == null || modelName.isBlank()) {
            throw DomainException.invalidParams("loadView 必须传入 model 参数");
        }
        ModelAggregate agg = memoryRegistry.getModelAggregate(modelName);
        if (agg == null) {
            throw DomainException.methodNotFound("模型不存在: " + modelName);
        }
        if (!agg.isAvailableForRuntime()) {
            throw DomainException.stateGuardFailed("模型 [" + agg.getDisplayName() + "] 已下线或未激活，无法访问界面");
        }

        ModelSnapshot snap = memoryRegistry.getActiveSnapshotOrNull(modelName);
        if (snap == null) {
            throw DomainException.stateGuardFailed("模型 [" + modelName + "] 无生效版本快照");
        }
        if (snap.isAbstractModel() || snap.isExtensionPackage()) {
            throw DomainException.stateGuardFailed("抽象父模型或增量扩展包 [" + modelName + "] 无独立运行视图");
        }

        Set<String> allowedApps = resolveTenantAllowedApps();
        if (allowedApps != null && snap.getAppName() != null
                && !"platform_kernel".equals(snap.getAppName())
                && !allowedApps.contains(snap.getAppName())) {
            throw DomainException.permissionDenied("访问拒绝：当前租户未授权使用应用 [" + snap.getAppName() + "]");
        }

        Map<String, Object> viewsMap = new LinkedHashMap<>();
        List<UiViewDef> modelViews = uiRepo.findViewsByModel(modelName);
        Set<String> requestedKeys = new LinkedHashSet<>();
        if (viewKeysCsv != null && !viewKeysCsv.isBlank()) {
            for (String k : viewKeysCsv.split(",")) {
                if (!k.isBlank()) requestedKeys.add(k.trim());
            }
        }

        for (UiViewDef v : modelViews) {
            if (!v.isActive()) continue;
            if (requestedKeys.isEmpty() || requestedKeys.contains(v.getKey())) {
                viewsMap.put(v.getType(), v);
                viewsMap.put(v.getKey(), v);
            }
        }

        Map<String, PropertyDef> fieldsMap = new LinkedHashMap<>();
        Map<String, List<DictItemDef>> dictOptions = new LinkedHashMap<>();
        Map<String, Object> dynamicOptions = new LinkedHashMap<>();

        for (PropertyDef p : snap.getProperties()) {
            fieldsMap.put(p.getName(), p);
            if (p.isDict() && p.getDictType() != null && !p.getDictType().isBlank()) {
                List<DictItemDef> activeItems = new ArrayList<>();
                for (DictItemDef di : foundationRepo.findDictItemsByType(p.getDictType())) {
                    if ("ACTIVE".equalsIgnoreCase(di.getStatus())) activeItems.add(di);
                }
                dictOptions.put(p.getName(), activeItems);
                dictOptions.put(p.getDictType(), activeItems);
            }
            if (p.getSelectionMethod() != null && !p.getSelectionMethod().isBlank() && MetaContext.current() != null) {
                try {
                    Object opts = MetaContext.current().get(modelName).call("getSelectionOptions", Map.of("field", p.getName()));
                    dynamicOptions.put(p.getName(), opts);
                } catch (Exception ignored) {}
            }
        }

        Map<String, Object> modelMeta = new LinkedHashMap<>();
        modelMeta.put("name", snap.getName());
        modelMeta.put("displayName", snap.getDisplayName());
        modelMeta.put("tableName", snap.resolvedTableName());
        modelMeta.put("appName", snap.getAppName());
        modelMeta.put("versionNo", snap.getVersionNo());
        modelMeta.put("activeVersionId", agg.getActiveVersionId());
        modelMeta.put("lifecycleState", agg.getLifecycleState().name());
        modelMeta.put("autoLog", snap.isAutoLog());
        modelMeta.put("logicDelete", snap.isLogicDelete());
        modelMeta.put("parents", snap.getParents());

        Map<String, Object> auth = buildGranularAuth(modelName, snap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelMeta", modelMeta);
        result.put("views", viewsMap);
        result.put("fields", fieldsMap);
        result.put("dictOptions", dictOptions);
        result.put("dynamicOptions", dynamicOptions);
        result.put("services", snap.getServices());
        result.put("auth", auth);
        return result;
    }

    private Set<String> resolveTenantAllowedApps() {
        MetaContext ctx = MetaContext.current();
        if (ctx == null) return null;
        if (MetaContext.DEFAULT_TENANT.equals(ctx.getTenantId()) && ctx.isSuperUser()) {
            return null;
        }
        return iamAppService.resolveAllowedAppNames(ctx.getTenantId());
    }

    private Map<String, Object> buildGranularAuth(String modelName, ModelSnapshot snap) {
        MetaContext ctx = MetaContext.current();
        Map<String, Object> auth = new LinkedHashMap<>();
        Map<String, Boolean> svcPerms = new LinkedHashMap<>();
        if (ctx == null || ctx.isSuperUser()) {
            auth.put("read", true);
            auth.put("create", true);
            auth.put("update", true);
            auth.put("delete", true);
            for (ServiceDef s : snap.getServices()) {
                svcPerms.put(s.getAuth(), true);
                svcPerms.put(s.getName(), true);
            }
            auth.put("services", svcPerms);
            return auth;
        }

        List<RoleDef> roles = iamAppService.resolveRoles(ctx.getRoleCodes());
        auth.put("read", hasPerm(roles, modelName, "search") || hasPerm(roles, modelName, "find") || hasPerm(roles, modelName, "read"));
        auth.put("create", hasPerm(roles, modelName, "create"));
        auth.put("update", hasPerm(roles, modelName, "update"));
        auth.put("delete", hasPerm(roles, modelName, "delete"));
        for (ServiceDef s : snap.getServices()) {
            boolean ok = hasPerm(roles, modelName, s.getAuth()) || hasPerm(roles, modelName, s.getName());
            svcPerms.put(s.getAuth(), ok);
            svcPerms.put(s.getName(), ok);
        }
        auth.put("services", svcPerms);
        return auth;
    }

    private boolean hasPerm(List<RoleDef> roles, String model, String service) {
        if (service == null || service.isBlank()) return true;
        for (RoleDef r : roles) {
            for (String p : r.getAllowedServices()) {
                if ("*".equals(p) || "*:*".equals(p)) return true;
                if ((model + ":" + service).equals(p) || (model + ":*").equals(p) || ("*:" + service).equals(p)) return true;
                if (p.endsWith("*:*") && model.startsWith(p.substring(0, p.length() - 3))) return true;
            }
        }
        return false;
    }
}
