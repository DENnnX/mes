package com.edu.scnu.application;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.edu.scnu.domain.iam.IamRepository;
import com.edu.scnu.domain.iam.RoleDef;
import com.edu.scnu.domain.iam.TenantDef;
import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.metamodel.repository.*;
import com.edu.scnu.domain.metamodel.service.*;
import com.edu.scnu.domain.runtime.DynamicDataAccessor;
import com.edu.scnu.domain.runtime.RecordData;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.domain.uischema.*;
import com.edu.scnu.infrastructure.container.MetaContainerMemoryRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 元模型全生命周期治理应用服务（对应 Dev Center + App Store + Governance）：
 * - 应用包 JSON 导入 / 导出
 * - 在线模型草稿维护、自动生成三件套默认视图 (buildDefaultViews)
 * - 变更影响分析 (checkImpact)、显式发布 (publish) 与 DDL 同步、兼容回滚 (rollback)
 * - 模型受控下线 (offline)、数据导出凭据签发、管理员确认永久清除 (applyPurge -> confirmPurge)
 */
@Service
public class MetaGovernanceAppService {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppRepository appRepo;
    private final ModelRepository modelRepo;
    private final UiSchemaRepository uiRepo;
    private final GovernanceRepository govRepo;
    private final FoundationRepository foundationRepo;
    private final IamRepository iamRepo;
    private final DynamicDataAccessor dataAccessor;
    private final MetaContainerMemoryRegistry memoryRegistry;
    private final ObjectMapper objectMapper;

    private final MetaValidationDomainService validationService = new MetaValidationDomainService();
    private final ReferenceAnalyzerDomainService refAnalyzer = new ReferenceAnalyzerDomainService();
    private final DefaultViewGeneratorService viewGenerator = new DefaultViewGeneratorService();
    private final ModelInheritanceDomainService inheritanceService = new ModelInheritanceDomainService();

    public MetaGovernanceAppService(AppRepository appRepo,
                                    ModelRepository modelRepo,
                                    UiSchemaRepository uiRepo,
                                    GovernanceRepository govRepo,
                                    FoundationRepository foundationRepo,
                                    IamRepository iamRepo,
                                    DynamicDataAccessor dataAccessor,
                                    MetaContainerMemoryRegistry memoryRegistry,
                                    ObjectMapper objectMapper) {
        this.appRepo = appRepo;
        this.modelRepo = modelRepo;
        this.uiRepo = uiRepo;
        this.govRepo = govRepo;
        this.foundationRepo = foundationRepo;
        this.iamRepo = iamRepo;
        this.dataAccessor = dataAccessor;
        this.memoryRegistry = memoryRegistry;
        this.objectMapper = objectMapper;
    }

    public List<AppAggregate> listApps() {
        return appRepo.findAll();
    }

    public AppAggregate saveApp(AppAggregate app) {
        if (app.getName() == null || app.getName().isBlank()) {
            throw DomainException.invalidParams("应用标识 (name) 不能为空");
        }
        String now = nowStr();
        if (app.getId() == null || app.getId().isBlank()) app.setId(app.getName());
        if (app.getCreateDate() == null) app.setCreateDate(now);
        app.setUpdateDate(now);
        if (app.getCreateUser() == null && MetaContext.current() != null) {
            app.setCreateUser(MetaContext.current().getUserId());
        }
        appRepo.save(app);
        return app;
    }

    /**
     * 彻底删除指定业务应用及其名下的所有数据表/表单、视图、导航菜单、版本治理记录与租户授权引用。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteApp(String appName) {
        if (appName == null || appName.isBlank()) {
            throw DomainException.invalidParams("应用标识 (appName) 不能为空");
        }
        String targetApp = appName.trim();
        if ("platform_kernel".equals(targetApp)) {
            throw DomainException.stateGuardFailed("平台开发与治理内核 (platform_kernel) 不可删除");
        }
        AppAggregate app = appRepo.findByName(targetApp)
                .orElseThrow(() -> DomainException.invalidParams("应用不存在: " + targetApp));
        registerRollbackReloadGuard();

        List<ModelAggregate> allModels = modelRepo.findAllModels();
        List<ModelAggregate> appModels = new ArrayList<>();
        Set<String> deletingModelNames = new LinkedHashSet<>();
        Set<String> protectedTables = new LinkedHashSet<>();
        for (ModelAggregate m : allModels) {
            if (targetApp.equals(m.getAppName())) {
                if (m.isKernelModel()) {
                    throw DomainException.stateGuardFailed("系统基础表 [" + m.getName() + "] 不可删除");
                }
                appModels.add(m);
                deletingModelNames.add(m.getName());
            } else if (m.getLifecycleState() != ModelAggregate.LifecycleState.PURGED
                    && m.getTableName() != null && !m.getTableName().isBlank()) {
                protectedTables.add(m.getTableName().toLowerCase(Locale.ROOT));
            }
        }

        validateNoExternalReferences(deletingModelNames, "应用", true);
        ModelDeletionSummary summary = purgeModelsAndUi(appModels, deletingModelNames, protectedTables, targetApp);
        cleanupIamReferences(targetApp, deletingModelNames, summary.deletedMenuKeys());

        appRepo.deleteByName(targetApp);
        for (String tbl : summary.tablesToDrop()) {
            dataAccessor.dropTable(tbl);
        }
        memoryRegistry.reloadAll();

        return Map.of(
                "appName", targetApp,
                "displayName", app.getDisplayName(),
                "deletedModels", new ArrayList<>(deletingModelNames),
                "deletedModelCount", deletingModelNames.size(),
                "deletedViewCount", summary.deletedViewCount(),
                "deletedMenuCount", summary.deletedMenuCount(),
                "droppedTableCount", summary.tablesToDrop().size()
        );
    }

    /**
     * 彻底删除单个表单/数据表及其所有版本、关联视图、导航菜单、治理记录、权限引用与物理表。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw DomainException.invalidParams("模型标识 (modelName) 不能为空");
        }
        String targetModel = modelName.trim();
        ModelAggregate agg = modelRepo.findModelByName(targetModel)
                .orElseThrow(() -> DomainException.invalidParams("表单/数据表不存在: " + targetModel));
        if (agg.isKernelModel()) {
            throw DomainException.stateGuardFailed("系统基础表 [" + targetModel + "] 不可删除");
        }
        registerRollbackReloadGuard();

        Set<String> deletingModelNames = Set.of(targetModel);
        Set<String> protectedTables = new LinkedHashSet<>();
        for (ModelAggregate m : modelRepo.findAllModels()) {
            if (!targetModel.equals(m.getName())
                    && m.getLifecycleState() != ModelAggregate.LifecycleState.PURGED
                    && m.getTableName() != null && !m.getTableName().isBlank()) {
                protectedTables.add(m.getTableName().toLowerCase(Locale.ROOT));
            }
        }

        validateNoExternalReferences(deletingModelNames, "表单/数据表", false);
        ModelDeletionSummary summary = purgeModelsAndUi(List.of(agg), deletingModelNames, protectedTables, null);
        cleanupIamReferences(null, deletingModelNames, summary.deletedMenuKeys());

        for (String tbl : summary.tablesToDrop()) {
            dataAccessor.dropTable(tbl);
        }
        memoryRegistry.reloadAll();

        return Map.of(
                "modelName", targetModel,
                "displayName", agg.getDisplayName(),
                "appName", agg.getAppName() != null ? agg.getAppName() : "",
                "deletedViewCount", summary.deletedViewCount(),
                "deletedMenuCount", summary.deletedMenuCount(),
                "droppedTableCount", summary.tablesToDrop().size()
        );
    }

    private record ModelDeletionSummary(Set<String> tablesToDrop, Set<String> deletedMenuKeys,
                                        int deletedViewCount, int deletedMenuCount) {}

    private void validateNoExternalReferences(Set<String> deletingModelNames, String targetKind, boolean onlyExternalApps) {
        if (deletingModelNames.isEmpty()) return;
        Map<String, ModelSnapshot> activeSnapshots = collectRawActiveSnapshots();
        for (Map.Entry<String, ModelSnapshot> entry : activeSnapshots.entrySet()) {
            String otherName = entry.getKey();
            if (deletingModelNames.contains(otherName)) continue;
            ModelSnapshot otherSnap = entry.getValue();
            String scopeLabel = onlyExternalApps ? "外部应用 [" + otherSnap.getAppName() + "] 的" : "";
            if (otherSnap.getExtendModel() != null && deletingModelNames.contains(otherSnap.getExtendModel())) {
                throw DomainException.stateGuardFailed("无法删除" + targetKind + "：模型 [" + otherSnap.getExtendModel()
                        + "] 正被" + scopeLabel + "扩展模型 [" + otherName + "] 扩展");
            }
            for (String parent : otherSnap.getParents()) {
                if (deletingModelNames.contains(parent)) {
                    throw DomainException.stateGuardFailed("无法删除" + targetKind + "：模型 [" + parent
                            + "] 正被" + scopeLabel + "模型 [" + otherName + "] 继承");
                }
            }
            for (PropertyDef p : otherSnap.getProperties()) {
                if (p.getRelateModel() != null && deletingModelNames.contains(p.getRelateModel())) {
                    throw DomainException.stateGuardFailed("无法删除" + targetKind + "：模型 [" + p.getRelateModel()
                            + "] 正被" + scopeLabel + "字段 [" + otherName + "." + p.getName() + "] 关联");
                }
            }
        }
    }

    private ModelDeletionSummary purgeModelsAndUi(List<ModelAggregate> deletingModels,
                                                  Set<String> deletingModelNames,
                                                  Set<String> protectedTables,
                                                  String appToDeleteMenus) {
        Set<String> tablesToDrop = new LinkedHashSet<>();
        for (ModelAggregate m : deletingModels) {
            List<ModelVersion> versions = modelRepo.findVersionsByModel(m.getName());
            boolean hasConcreteVersion = false;
            for (ModelVersion v : versions) {
                ModelSnapshot snap = v.getSnapshot();
                if (snap == null) continue;
                if (!snap.isAbstractModel() && !snap.isExtensionPackage()) {
                    hasConcreteVersion = true;
                    String tbl = snap.resolvedTableName();
                    if (tbl != null && !tbl.isBlank() && !protectedTables.contains(tbl.toLowerCase(Locale.ROOT))) {
                        tablesToDrop.add(tbl);
                    }
                    for (PropertyDef p : snap.getProperties()) {
                        if (p.isManyToMany()) {
                            String jt = p.resolvedJoinTable(m.getName());
                            if (jt != null && !jt.isBlank() && !protectedTables.contains(jt.toLowerCase(Locale.ROOT))) {
                                tablesToDrop.add(jt);
                            }
                        }
                    }
                }
            }
            if (!hasConcreteVersion && versions.isEmpty() && m.getTableName() != null && !m.getTableName().isBlank()
                    && !protectedTables.contains(m.getTableName().toLowerCase(Locale.ROOT))) {
                tablesToDrop.add(m.getTableName());
            }
        }

        Set<String> deletedMenuKeys = new LinkedHashSet<>();
        int deletedMenuCount = 0;
        for (MenuNode mn : uiRepo.findAllMenus()) {
            boolean matchesApp = appToDeleteMenus != null && appToDeleteMenus.equals(mn.getAppName());
            boolean matchesModel = mn.getModel() != null && deletingModelNames.contains(mn.getModel());
            if (matchesApp || matchesModel) {
                if (mn.getName() != null && !mn.getName().isBlank()) deletedMenuKeys.add(mn.getName());
                if (mn.getId() != null && !mn.getId().isBlank()) deletedMenuKeys.add(mn.getId());
                deletedMenuCount++;
            }
        }
        if (appToDeleteMenus != null) {
            uiRepo.deleteMenusByApp(appToDeleteMenus);
        }
        int deletedViewCount = 0;
        for (String modelName : deletingModelNames) {
            uiRepo.deleteMenusByModel(modelName);
            deletedViewCount += uiRepo.findViewsByModel(modelName).size();
            uiRepo.deleteViewsByModel(modelName);
            modelRepo.deleteReferencesByModel(modelName);
            modelRepo.deleteVersionsByModel(modelName);
            govRepo.deleteGovernanceByModel(modelName);
            modelRepo.deleteModelMeta(modelName);
        }
        return new ModelDeletionSummary(tablesToDrop, deletedMenuKeys, deletedViewCount, deletedMenuCount);
    }

    private void cleanupIamReferences(String appName, Set<String> deletedModels, Set<String> deletedMenuKeys) {
        if (iamRepo == null) return;
        for (TenantDef tenant : iamRepo.findAllTenants()) {
            boolean changed = false;
            if (appName != null) {
                List<String> apps = new ArrayList<>(tenant.getAuthorizedApps());
                if (!apps.contains("*") && apps.removeIf(a -> Objects.equals(a, appName))) {
                    tenant.setAuthorizedApps(apps);
                    changed = true;
                }
            }
            List<String> menus = new ArrayList<>(tenant.getAuthorizedMenus());
            if (!deletedMenuKeys.isEmpty() && !menus.isEmpty() && !menus.contains("*") && !menus.contains("__NONE__")) {
                if (menus.removeIf(deletedMenuKeys::contains)) {
                    tenant.setAuthorizedMenus(menus.isEmpty() ? List.of("__NONE__") : menus);
                    changed = true;
                }
            }
            if (changed) {
                iamRepo.saveTenant(tenant);
            }
        }
        for (RoleDef role : iamRepo.findAllRoles()) {
            boolean changed = false;
            List<String> menus = new ArrayList<>(role.getAllowedMenus());
            if (!deletedMenuKeys.isEmpty() && !menus.isEmpty() && !menus.contains("*") && !menus.contains("__NONE__")) {
                if (menus.removeIf(deletedMenuKeys::contains)) {
                    role.setAllowedMenus(menus.isEmpty() ? List.of("__NONE__") : menus);
                    changed = true;
                }
            }
            Set<String> services = new LinkedHashSet<>(role.getAllowedServices());
            if (!deletedModels.isEmpty() && !services.isEmpty()) {
                boolean svcRemoved = services.removeIf(expr -> {
                    if (expr == null) return false;
                    int colon = expr.indexOf(':');
                    if (colon <= 0) return false;
                    return deletedModels.contains(expr.substring(0, colon));
                });
                if (svcRemoved) {
                    role.setAllowedServices(services);
                    changed = true;
                }
            }
            if (changed) {
                iamRepo.saveRole(role);
            }
        }
    }

    /**
     * 批量导入标准 App JSON 包（包含 App、Models、Views、Menus）。
     */
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> importAppPackage(Map<String, Object> packageJson, boolean autoPublish) {
        if (packageJson == null || !packageJson.containsKey("app") || !packageJson.containsKey("models")) {
            throw DomainException.invalidParams("导入包格式非法：必须包含 app 与 models 节点");
        }
        registerRollbackReloadGuard();

        AppAggregate app = objectMapper.convertValue(packageJson.get("app"), AppAggregate.class);
        if (app == null || app.getName() == null || app.getName().isBlank()) {
            throw DomainException.invalidParams("应用标识 (name) 不能为空");
        }

        List<Map<String, Object>> modelMaps = (List<Map<String, Object>>) packageJson.get("models");
        List<ModelSnapshot> batchSnapshots = new ArrayList<>();
        Set<String> availableNames = new LinkedHashSet<>(memoryRegistry.getAllActiveSnapshots().keySet());
        modelRepo.findAllModels().forEach(m -> {
            if (m.getLifecycleState() != ModelAggregate.LifecycleState.PURGED) availableNames.add(m.getName());
        });
        for (Map<String, Object> mm : modelMaps) {
            ModelSnapshot snap = objectMapper.convertValue(mm, ModelSnapshot.class);
            if (snap.getAppName() == null || snap.getAppName().isBlank()) {
                snap.setAppName(app.getName());
            }
            availableNames.add(snap.getName());
            batchSnapshots.add(snap);
        }

        // 1. 批量语义与脚本校验
        for (ModelSnapshot snap : batchSnapshots) {
            validationService.validateOrThrow(snap, availableNames);
        }

        List<UiViewDef> views = readPackageViews(packageJson.get("views"));
        List<MenuNode> menus = readPackageMenus(packageJson.get("menus"), app.getName());
        if (autoPublish) {
            saveApp(app);
            importFoundationMetadata(packageJson);
            views.forEach(uiRepo::saveView);
        } else if (batchSnapshots.isEmpty()) {
            throw DomainException.invalidParams("草稿导入至少需要一个模型版本承载应用配置");
        } else if (appRepo.findByName(app.getName()).isEmpty()) {
            AppAggregate draftApp = objectMapper.convertValue(app, AppAggregate.class);
            draftApp.setState("DRAFT");
            saveApp(draftApp);
        }

        // 3. 分阶段导入：先持久化全部草稿并构建默认三件套视图，再统一影响检查与发布，避免同批模型互指失败
        Map<String, ModelSnapshot> knownMap = new HashMap<>(collectRawActiveSnapshots());
        batchSnapshots.forEach(s -> knownMap.put(s.getName(), s));

        Map<String, ModelVersion> draftsByModel = new LinkedHashMap<>();
        for (ModelSnapshot snap : batchSnapshots) {
            ModelVersion draft = saveModelDraftInternal(snap, availableNames);
            draftsByModel.put(snap.getName(), draft);
            if (autoPublish && !snap.isAbstractModel() && !snap.isExtensionPackage()
                    && uiRepo.findViewsByModel(snap.getName()).isEmpty()) {
                ModelSnapshot effective = inheritanceService.resolveEffectiveSnapshot(snap.getName(), knownMap);
                for (UiViewDef v : viewGenerator.generateDefaultTriadViews(effective != null ? effective : snap, knownMap)) {
                    uiRepo.saveView(v);
                }
            }
        }

        if (!autoPublish) {
            stagePackageUi(app, views, menus, draftsByModel);
        }
        List<Map<String, Object>> importedModels = new ArrayList<>();
        for (ModelSnapshot snap : batchSnapshots) {
            ModelVersion draft = draftsByModel.get(snap.getName());
            if (autoPublish) {
                if (draft.getPendingUiJson() != null) {
                    draft.setPendingUiJson(null);
                    modelRepo.saveVersion(draft);
                }
                ImpactReport report = checkImpact(snap.getName(), draft.getId());
                if (!report.isPassed()) {
                    throw DomainException.impactBlocked(
                            "模型 [" + snap.getName() + "] 自动发布受阻", report.getBlockingReasons());
                }
                publishModel(snap.getName(), draft.getId(), report.getValidationHash());
            }
            importedModels.add(Map.of("modelName", snap.getName(), "versionId", draft.getId(), "versionNo", draft.getVersionNo()));
        }

        if (autoPublish) {
            menus.forEach(uiRepo::saveMenu);
        }
        memoryRegistry.reloadAll();
        return Map.of(
                "app", app,
                "importedModels", importedModels,
                "autoPublished", autoPublish
        );
    }

    @SuppressWarnings("unchecked")
    private void importFoundationMetadata(Map<String, Object> packageJson) {
        if (foundationRepo == null || packageJson == null) return;
        if (packageJson.get("dicts") instanceof List<?> dicts) {
            for (Object rawDict : dicts) {
                if (!(rawDict instanceof Map<?, ?> dm)) continue;
                DictTypeDef dt = objectMapper.convertValue(dm, DictTypeDef.class);
                if (dt.getTypeCode() != null && !dt.getTypeCode().isBlank()) {
                    if (dt.getId() == null) dt.setId("dt_" + dt.getTypeCode());
                    foundationRepo.saveDictType(dt);
                    if (dm.get("items") instanceof List<?> items) {
                        for (Object rawItem : items) {
                            if (!(rawItem instanceof Map<?, ?> im)) continue;
                            DictItemDef item = objectMapper.convertValue(im, DictItemDef.class);
                            item.setTypeCode(dt.getTypeCode());
                            if (item.getItemValue() != null && !item.getItemValue().isBlank()) {
                                if (item.getId() == null) {
                                    item.setId("di_" + dt.getTypeCode().toLowerCase() + "_" + item.getItemValue().toLowerCase());
                                }
                                foundationRepo.saveDictItem(item);
                            }
                        }
                    }
                }
            }
        }
        if (packageJson.get("codeRules") instanceof List<?> rules) {
            for (Object rawRule : rules) {
                if (!(rawRule instanceof Map<?, ?> rm)) continue;
                CodeRuleDef rule = objectMapper.convertValue(rm, CodeRuleDef.class);
                if (rule.getCode() != null && !rule.getCode().isBlank()) {
                    if (rule.getId() == null) rule.setId("cr_" + rule.getCode());
                    foundationRepo.saveCodeRule(rule);
                }
            }
        }
    }

    private List<UiViewDef> readPackageViews(Object rawViews) {
        List<UiViewDef> views = new ArrayList<>();
        if (rawViews instanceof Map<?, ?> viewMap) {
            for (Map.Entry<?, ?> entry : viewMap.entrySet()) {
                UiViewDef view = objectMapper.convertValue(entry.getValue(), UiViewDef.class);
                if (view.getKey() == null || view.getKey().isBlank()) view.setKey(entry.getKey().toString());
                if (view.getId() == null) view.setId(view.getKey());
                views.add(view);
            }
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    private List<MenuNode> readPackageMenus(Object rawMenus, String appName) {
        List<MenuNode> menus = new ArrayList<>();
        if (rawMenus instanceof Map<?, ?> menuMap) {
            for (Map.Entry<?, ?> entry : menuMap.entrySet()) {
                Map<String, Object> mMap = (Map<String, Object>) entry.getValue();
                String key = entry.getKey().toString();
                MenuNode node = new MenuNode();
                node.setId(key);
                node.setName(String.valueOf(mMap.getOrDefault("name", key)));
                node.setDisplayName(String.valueOf(mMap.getOrDefault("display_name", mMap.getOrDefault("displayName", node.getName()))));
                node.setAppName(appName);
                node.setIcon(mMap.get("icon") != null ? mMap.get("icon").toString() : "Grid");
                node.setModel(mMap.get("model") != null ? mMap.get("model").toString() : null);
                node.setView(mMap.get("view") != null ? mMap.get("view").toString() : null);
                node.setFilter(mMap.get("filter") != null ? mMap.get("filter").toString() : null);
                node.setSequence(mMap.get("sequence") instanceof Number ? ((Number) mMap.get("sequence")).intValue() : 10);
                node.setActive(mMap.get("active") == null || Boolean.TRUE.equals(mMap.get("active")));
                Object pRef = mMap.get("parent_ids");
                if (pRef instanceof Map && ((Map<?, ?>) pRef).containsKey("@ref")) {
                    node.setParentId(String.valueOf(((Map<?, ?>) pRef).get("@ref")));
                } else if (mMap.get("parentId") != null) {
                    node.setParentId(mMap.get("parentId").toString());
                }
                menus.add(node);
            }
        }
        return menus;
    }

    private record PendingUi(AppAggregate app, List<UiViewDef> views, List<MenuNode> menus) {}

    private void stagePackageUi(AppAggregate app, List<UiViewDef> views, List<MenuNode> menus,
                                Map<String, ModelVersion> draftsByModel) {
        String firstModel = draftsByModel.keySet().iterator().next();
        for (Map.Entry<String, ModelVersion> entry : draftsByModel.entrySet()) {
            String model = entry.getKey();
            boolean first = firstModel.equals(model);
            List<UiViewDef> modelViews = views.stream().filter(v -> model.equals(v.getModel())
                    || (first && !draftsByModel.containsKey(v.getModel()))).toList();
            List<MenuNode> modelMenus = menus.stream().filter(m -> model.equals(m.getModel())
                    || m.getModel() == null || m.getModel().isBlank()
                    || (first && !draftsByModel.containsKey(m.getModel()))).toList();
            ModelVersion draft = entry.getValue();
            try {
                draft.setPendingUiJson(objectMapper.writeValueAsString(new PendingUi(app, modelViews, modelMenus)));
            } catch (Exception e) {
                throw DomainException.invalidParams("无法保存应用配置草稿");
            }
            modelRepo.saveVersion(draft);
        }
    }

    private void publishPendingUi(ModelVersion version) {
        if (version.getPendingUiJson() == null) return;
        PendingUi pending;
        try {
            pending = objectMapper.readValue(version.getPendingUiJson(), PendingUi.class);
        } catch (Exception e) {
            throw DomainException.invalidParams("无法读取应用配置草稿");
        }
        saveApp(pending.app());
        pending.views().forEach(uiRepo::saveView);
        pending.menus().forEach(uiRepo::saveMenu);
    }

    private void registerRollbackReloadGuard() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) {
                                memoryRegistry.reloadAll();
                            }
                        }
                    });
        }
    }

    public Map<String, Object> exportAppPackage(String appName) {
        AppAggregate app = appRepo.findByName(appName)
                .orElseThrow(() -> DomainException.invalidParams("应用不存在: " + appName));
        List<ModelAggregate> models = modelRepo.findModelsByApp(appName);
        List<ModelSnapshot> snaps = new ArrayList<>();
        Map<String, Object> views = new LinkedHashMap<>();

        for (ModelAggregate m : models) {
            ModelVersion ver = null;
            if (m.getActiveVersionId() != null) {
                ver = modelRepo.findVersionById(m.getActiveVersionId()).orElse(null);
            }
            if (ver == null) {
                List<ModelVersion> allV = modelRepo.findVersionsByModel(m.getName());
                if (!allV.isEmpty()) ver = allV.get(0);
            }
            if (ver != null && ver.getSnapshot() != null) {
                snaps.add(ver.getSnapshot());
            }
            for (UiViewDef v : uiRepo.findViewsByModel(m.getName())) {
                views.put(v.getKey(), v);
            }
        }

        Map<String, Object> menus = new LinkedHashMap<>();
        for (MenuNode mn : uiRepo.findAllMenus()) {
            if (appName.equals(mn.getAppName())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", mn.getName());
                item.put("display_name", mn.getDisplayName());
                item.put("icon", mn.getIcon());
                item.put("model", mn.getModel());
                item.put("view", mn.getView());
                item.put("sequence", mn.getSequence());
                item.put("active", mn.isActive());
                if (mn.getParentId() != null) {
                    item.put("parent_ids", Map.of("@ref", mn.getParentId()));
                }
                menus.put(mn.getName(), item);
            }
        }

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("app", app);
        pkg.put("models", snaps);
        pkg.put("views", views);
        pkg.put("menus", menus);
        return pkg;
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelVersion saveModelDraft(ModelSnapshot snapshot) {
        return saveModelDraftInternal(snapshot, Collections.emptySet());
    }

    private ModelVersion saveModelDraftInternal(ModelSnapshot snapshot, Set<String> extraAvailableModels) {
        if (snapshot == null || snapshot.getName() == null || snapshot.getName().isBlank()) {
            throw DomainException.invalidParams("模型定义必须指定合法 name");
        }
        if (snapshot.getAppName() == null || snapshot.getAppName().isBlank()) {
            snapshot.setAppName("default_app");
        }
        ensureAppExists(snapshot.getAppName());

        Set<String> availableNames = new LinkedHashSet<>(memoryRegistry.getAllActiveSnapshots().keySet());
        modelRepo.findAllModels().forEach(m -> {
            if (m.getLifecycleState() != ModelAggregate.LifecycleState.PURGED) availableNames.add(m.getName());
        });
        if (extraAvailableModels != null) {
            availableNames.addAll(extraAvailableModels);
        }
        availableNames.add(snapshot.getName());
        validationService.validateOrThrow(snapshot, availableNames);

        String now = nowStr();
        String uid = MetaContext.current() != null ? MetaContext.current().getUserId() : MetaContext.SUPERUSER;
        ModelAggregate agg = modelRepo.findModelByName(snapshot.getName()).orElseGet(() -> {
            ModelAggregate m = new ModelAggregate();
            m.setId("mdl_" + snapshot.getName());
            m.setName(snapshot.getName());
            m.setCreateUser(uid);
            m.setCreateDate(now);
            return m;
        });
        if (agg.getActiveVersionId() == null) applyModelMetadata(agg, snapshot);
        agg.setUpdateDate(now);
        modelRepo.saveModel(agg);

        String versionNo = (snapshot.getVersionNo() != null && !snapshot.getVersionNo().isBlank())
                ? snapshot.getVersionNo() : "1.0.0";
        Optional<ModelVersion> existingVerOpt = modelRepo.findVersion(snapshot.getName(), versionNo);
        if (existingVerOpt.isPresent() && existingVerOpt.get().isImmutable()) {
            throw DomainException.stateGuardFailed(
                    "版本 [" + versionNo + "] 已发布且不可变，请递增新版本号（例如新版本草稿）再保存修改！");
        }

        ModelVersion ver = existingVerOpt.orElseGet(() -> {
            ModelVersion nv = new ModelVersion();
            nv.setId("ver_" + snapshot.getName() + "_" + UUID.randomUUID().toString().substring(0, 8));
            nv.setModelName(snapshot.getName());
            nv.setVersionNo(versionNo);
            nv.setCreatedBy(uid);
            nv.setCreatedAt(now);
            return nv;
        });
        ver.setStatus(ModelVersion.Status.DRAFT);
        ver.setSnapshot(snapshot);
        ver.setValidationHash(null);
        ver.setValidatedAt(null);
        try {
            ver.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw DomainException.invalidParams("模型快照 JSON 序列化失败");
        }
        modelRepo.saveVersion(ver);
        return ver;
    }

    /**
     * 对标 dev-center MetaView.buildDefaultViews：一键为模型生成标准三件套视图并挂载菜单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> buildDefaultViews(String modelName) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        List<ModelVersion> versions = modelRepo.findVersionsByModel(modelName);
        if (versions.isEmpty()) {
            throw DomainException.stateGuardFailed("模型尚无任何版本定义");
        }
        ModelVersion target = agg.getActiveVersionId() != null
                ? modelRepo.findVersionById(agg.getActiveVersionId()).orElse(versions.get(0))
                : versions.get(0);

        if (target.getSnapshot().isAbstractModel() || target.getSnapshot().isExtensionPackage()) {
            throw DomainException.invalidParams("抽象父模型或增量扩展包不生成独立默认视图");
        }
        Map<String, ModelSnapshot> rawAll = collectRawActiveSnapshots();
        rawAll.put(modelName, target.getSnapshot());
        ModelSnapshot effective = inheritanceService.resolveEffectiveSnapshot(modelName, rawAll);

        List<UiViewDef> views = viewGenerator.generateDefaultTriadViews(
                effective != null ? effective : target.getSnapshot(), rawAll);
        for (UiViewDef v : views) {
            uiRepo.saveView(v);
        }

        // 自动补充业务菜单挂载到应用下
        AppAggregate app = appRepo.findByName(agg.getAppName()).orElse(null);
        if (app != null) {
            ensureDefaultMenusForApp(app, Collections.singletonList(target.getSnapshot()));
        }

        return Map.of(
                "modelName", modelName,
                "generatedViews", views.stream().map(UiViewDef::getKey).toList()
        );
    }

    /**
     * 触发版本静态检验与引用影响分析 (DRAFT -> VALIDATED)。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImpactReport checkImpact(String modelName, String versionId) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        ModelVersion candidate = (versionId != null && !versionId.isBlank())
                ? modelRepo.findVersionById(versionId).orElseThrow(() -> DomainException.invalidParams("版本不存在: " + versionId))
                : modelRepo.findVersionsByModel(modelName).stream().findFirst()
                    .orElseThrow(() -> DomainException.invalidParams("模型无候选版本"));

        ModelVersion activeVer = agg.getActiveVersionId() != null
                ? modelRepo.findVersionById(agg.getActiveVersionId()).orElse(null) : null;

        Set<String> availableNames = new LinkedHashSet<>(memoryRegistry.getAllActiveSnapshots().keySet());
        modelRepo.findAllModels().forEach(m -> {
            if (m.getLifecycleState() != ModelAggregate.LifecycleState.PURGED) availableNames.add(m.getName());
        });
        availableNames.add(modelName);
        List<String> syntaxErrors = validationService.validateSnapshot(candidate.getSnapshot(), availableNames);

        List<UiViewDef> existingViews = uiRepo.findViewsByModel(modelName);
        List<ModelSnapshot> otherModels = new ArrayList<>(memoryRegistry.getAllActiveSnapshots().values());
        long existingCount = (activeVer != null && activeVer.getSnapshot() != null)
                ? safeCountRecords(activeVer.getSnapshot()) : 0L;

        String hash = sha256(candidate.getId() + "|" + candidate.getSnapshotJson() + "|" + candidate.getPendingUiJson());
        ImpactReport report = refAnalyzer.analyzeImpact(activeVer, candidate, existingViews, otherModels, existingCount, hash);
        if (!syntaxErrors.isEmpty()) {
            report.getBlockingReasons().addAll(0, syntaxErrors);
            report.setPassed(false);
        }

        // 记录影响与引用图快照
        List<ReferenceRecord> nextRefs = refAnalyzer.extractReferences(candidate, existingViews, otherModels);
        modelRepo.replaceReferences(candidate.getId(), nextRefs);

        if (report.isPassed()) {
            candidate.setStatus(ModelVersion.Status.VALIDATED);
            candidate.setValidationHash(hash);
            candidate.setValidatedAt(nowStr());
        } else {
            candidate.setStatus(ModelVersion.Status.DRAFT);
        }
        modelRepo.saveVersion(candidate);
        return report;
    }

    /**
     * 显式发布候选版本：同步 DDL -> 原子切换活动指针 -> 写发布记录 -> 热重载内存引擎。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publishModel(String modelName, String versionId, String expectedHash) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        ModelVersion target = modelRepo.findVersionById(versionId)
                .orElseThrow(() -> DomainException.invalidParams("目标版本不存在: " + versionId));

        String previousActiveId = agg.getActiveVersionId();
        Map<String, ModelSnapshot> rawAll = collectRawActiveSnapshots();
        ModelSnapshot rawTarget = target.getSnapshot();
        ModelSnapshot previous = rawAll.get(modelName);
        if (previous != null && refAnalyzer.changesPhysicalTable(previous, rawTarget)) {
            throw DomainException.impactBlocked("已发布模型的物理表名称不能直接修改",
                    List.of("须先迁移存量数据"));
        }
        agg.activateVersion(target, expectedHash);
        rawAll.put(modelName, rawTarget);

        // 1. 同步物理表 DDL（跳过抽象父模型与扩展包本身；对具体模型或被扩展的宿主模型同步完整继承合并表结构）
        if (rawTarget.isAbstractModel()) {
            for (String candName : rawAll.keySet()) {
                if (Objects.equals(candName, modelName)) continue;
                ModelSnapshot effChild = inheritanceService.resolveEffectiveSnapshot(candName, rawAll);
                if (effChild != null && !effChild.isAbstractModel() && !effChild.isExtensionPackage()
                        && effChild.getParents().contains(modelName)) {
                    dataAccessor.syncSchema(effChild);
                }
            }
        } else if (rawTarget.isExtensionPackage()) {
            String hostModel = rawTarget.getExtendModel();
            ModelSnapshot effHost = inheritanceService.resolveEffectiveSnapshot(hostModel, rawAll);
            if (effHost != null && !effHost.isAbstractModel()) {
                dataAccessor.syncSchema(effHost);
            }
        } else {
            ModelSnapshot effective = inheritanceService.resolveEffectiveSnapshot(modelName, rawAll);
            dataAccessor.syncSchema(effective != null ? effective : rawTarget);
        }

        // 2. 退役原活动版本，持久化新指针
        if (previousActiveId != null && !previousActiveId.equals(target.getId())) {
            modelRepo.findVersionById(previousActiveId).ifPresent(prev -> {
                prev.setStatus(ModelVersion.Status.RETIRED);
                modelRepo.saveVersion(prev);
            });
        }
        modelRepo.saveVersion(target);
        applyModelMetadata(agg, rawTarget);
        agg.setUpdateDate(nowStr());
        modelRepo.saveModel(agg);

        // 3. 记录发布审计
        PublishRecord pr = new PublishRecord();
        pr.setId("pub_" + UUID.randomUUID().toString().substring(0, 8));
        pr.setModelName(modelName);
        pr.setFromVersionId(previousActiveId);
        pr.setToVersionId(target.getId());
        pr.setToVersionNo(target.getVersionNo());
        pr.setActionType("PUBLISH");
        pr.setOperatorId(currentUserId());
        pr.setOperatedAt(nowStr());
        govRepo.savePublishRecord(pr);

        publishPendingUi(target);
        // 4. 自动确保默认三件套视图与菜单就绪（抽象模型及扩展包除外）
        if (!rawTarget.isAbstractModel() && !rawTarget.isExtensionPackage()) {
            if (uiRepo.findViewsByModel(modelName).isEmpty()) {
                ModelSnapshot effective = inheritanceService.resolveEffectiveSnapshot(modelName, rawAll);
                for (UiViewDef v : viewGenerator.generateDefaultTriadViews(effective != null ? effective : rawTarget, rawAll)) {
                    uiRepo.saveView(v);
                }
            }
            appRepo.findByName(agg.getAppName()).ifPresent(app ->
                    ensureDefaultMenusForApp(app, Collections.singletonList(rawTarget)));
        }

        memoryRegistry.reloadAll();
        return Map.of(
                "modelName", modelName,
                "activeVersionId", target.getId(),
                "versionNo", target.getVersionNo(),
                "publishId", pr.getId()
        );
    }

    /**
     * 安全兼容回滚至指定历史版本，保留全部业务数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollbackModel(String modelName, String targetVersionId) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        ModelVersion target = modelRepo.findVersionById(targetVersionId)
                .orElseThrow(() -> DomainException.invalidParams("目标版本不存在: " + targetVersionId));
        if (target.getStatus() != ModelVersion.Status.RETIRED) {
            throw DomainException.stateGuardFailed("仅可回滚至历史退役版本 (RETIRED)");
        }

        String previousActiveId = agg.getActiveVersionId();
        if (previousActiveId != null) {
            ModelSnapshot previous = modelRepo.findVersionById(previousActiveId).map(ModelVersion::getSnapshot).orElse(null);
            if (previous != null && refAnalyzer.changesPhysicalTable(previous, target.getSnapshot())) {
                throw DomainException.stateGuardFailed("回滚版本对应不同的物理表，须先核对并迁移存量数据");
            }
        }
        agg.activateVersion(target, null);
        if (previousActiveId != null && !previousActiveId.equals(target.getId())) {
            modelRepo.findVersionById(previousActiveId).ifPresent(prev -> {
                prev.setStatus(ModelVersion.Status.RETIRED);
                modelRepo.saveVersion(prev);
            });
        }
        modelRepo.saveVersion(target);
        applyModelMetadata(agg, target.getSnapshot());
        agg.setUpdateDate(nowStr());
        modelRepo.saveModel(agg);

        PublishRecord pr = new PublishRecord();
        pr.setId("pub_" + UUID.randomUUID().toString().substring(0, 8));
        pr.setModelName(modelName);
        pr.setFromVersionId(previousActiveId);
        pr.setToVersionId(target.getId());
        pr.setToVersionNo(target.getVersionNo());
        pr.setActionType("ROLLBACK");
        pr.setOperatorId(currentUserId());
        pr.setOperatedAt(nowStr());
        govRepo.savePublishRecord(pr);

        memoryRegistry.reloadAll();
        return Map.of("modelName", modelName, "rolledBackToVersion", target.getVersionNo(), "dataPreserved", true);
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelAggregate offlineModel(String modelName) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        agg.markOffline(nowStr());
        agg.setUpdateDate(nowStr());
        modelRepo.saveModel(agg);
        memoryRegistry.reloadAll();
        return agg;
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelAggregate onlineModel(String modelName) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        agg.markOnline();
        agg.setUpdateDate(nowStr());
        modelRepo.saveModel(agg);
        memoryRegistry.reloadAll();
        return agg;
    }

    public Map<String, Object> exportModelData(String modelName) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        ModelVersion active = agg.getActiveVersionId() != null
                ? modelRepo.findVersionById(agg.getActiveVersionId()).orElse(null) : null;
        if (active == null || active.getSnapshot() == null) {
            throw DomainException.stateGuardFailed("模型无版本结构，无法导出数据");
        }
        ModelSnapshot effective = memoryRegistry.getActiveSnapshotOrNull(modelName);
        List<RecordData> records = dataAccessor.exportAll(effective != null ? effective : active.getSnapshot());
        String ticketId = "exp_" + UUID.randomUUID().toString().substring(0, 12);
        String json;
        try { json = objectMapper.writeValueAsString(records); } catch (Exception e) { json = "[]"; }
        govRepo.saveExportTicket(ticketId, modelName, records.size(), json, currentUserId(), nowStr());
        return Map.of("exportTicket", ticketId, "modelName", modelName, "recordCount", records.size(), "records", records);
    }

    public Map<String, Object> getModelDetail(String modelName) {
        ModelAggregate agg = modelRepo.findModelByName(modelName)
                .orElseThrow(() -> DomainException.invalidParams("模型不存在: " + modelName));
        List<ModelVersion> versions = modelRepo.findVersionsByModel(modelName);
        List<UiViewDef> views = uiRepo.findViewsByModel(modelName);
        List<ReferenceRecord> refs = agg.getActiveVersionId() != null
                ? modelRepo.findReferencesByVersion(agg.getActiveVersionId()) : Collections.emptyList();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("model", agg);
        res.put("versions", versions);
        res.put("views", views);
        res.put("references", refs);
        res.put("publishRecords", govRepo.findPublishRecords(modelName));
        return res;
    }

    public List<Map<String, Object>> listModelsSummary() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModelAggregate m : modelRepo.findAllModels()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("name", m.getName());
            row.put("displayName", m.getDisplayName());
            row.put("appName", m.getAppName());
            row.put("tableName", m.getTableName());
            row.put("kernelModel", m.isKernelModel());
            row.put("activeVersionId", m.getActiveVersionId());
            row.put("lifecycleState", m.getLifecycleState().name());
            row.put("offlineTime", m.getOfflineTime());
            ModelVersion active = m.getActiveVersionId() != null
                    ? modelRepo.findVersionById(m.getActiveVersionId()).orElse(null) : null;
            List<ModelVersion> versions = modelRepo.findVersionsByModel(m.getName());
            ModelVersion latest = active != null ? active : (versions.isEmpty() ? null : versions.get(0));
            row.put("versionNo", latest != null ? latest.getVersionNo() : "-");
            row.put("versionCount", versions.size());
            row.put("fieldCount", (latest != null && latest.getSnapshot() != null) ? latest.getSnapshot().getProperties().size() : 0);
            row.put("serviceCount", (latest != null && latest.getSnapshot() != null) ? latest.getSnapshot().getServices().size() : 0);
            row.put("abstractModel", latest != null && latest.getSnapshot() != null && latest.getSnapshot().isAbstractModel());
            row.put("extendModel", (latest != null && latest.getSnapshot() != null) ? latest.getSnapshot().getExtendModel() : null);
            row.put("parents", (latest != null && latest.getSnapshot() != null) ? latest.getSnapshot().getParents() : Collections.emptyList());
            list.add(row);
        }
        return list;
    }

    public List<PublishRecord> listPublishRecords(String modelName) { return govRepo.findPublishRecords(modelName); }

    // ==================== Private Guards & Helpers ====================

    private void applyModelMetadata(ModelAggregate model, ModelSnapshot snapshot) {
        model.setDisplayName(snapshot.getDisplayName() != null ? snapshot.getDisplayName() : snapshot.getName());
        model.setAppName(snapshot.getAppName());
        model.setTableName(snapshot.resolvedTableName());
    }

    private void ensureAppExists(String appName) {
        if (appRepo.findByName(appName).isEmpty()) {
            AppAggregate app = new AppAggregate();
            app.setId(appName);
            app.setName(appName);
            app.setDisplayName(appName);
            saveApp(app);
        }
    }

    private void ensureDefaultMenusForApp(AppAggregate app, List<ModelSnapshot> models) {
        List<ModelSnapshot> concreteModels = new ArrayList<>();
        for (ModelSnapshot s : models) {
            if (s != null && !s.isAbstractModel() && !s.isExtensionPackage()) {
                concreteModels.add(s);
            }
        }
        if (concreteModels.isEmpty()) return;

        String rootMenuName = app.getName() + "_root_menu";
        if (uiRepo.findMenuByName(rootMenuName).isEmpty()) {
            MenuNode root = new MenuNode();
            root.setId(rootMenuName);
            root.setName(rootMenuName);
            root.setDisplayName(app.getDisplayName());
            root.setAppName(app.getName());
            root.setIcon("FolderOpened");
            root.setSequence(20);
            root.setActive(true);
            uiRepo.saveMenu(root);
        }
        int seq = 10;
        for (ModelSnapshot snap : concreteModels) {
            String mMenuName = snap.getName() + "_menu";
            if (uiRepo.findMenuByName(mMenuName).isEmpty()) {
                MenuNode mn = new MenuNode();
                mn.setId(mMenuName);
                mn.setName(mMenuName);
                mn.setDisplayName(snap.getDisplayName());
                mn.setAppName(app.getName());
                mn.setParentId(rootMenuName);
                mn.setIcon("Document");
                mn.setModel(snap.getName());
                mn.setView(snap.getName() + "_grid," + snap.getName() + "_search," + snap.getName() + "_form");
                mn.setSequence(seq);
                mn.setActive(true);
                uiRepo.saveMenu(mn);
                seq += 10;
            }
        }
    }

    private Map<String, ModelSnapshot> collectRawActiveSnapshots() {
        Map<String, ModelSnapshot> map = new LinkedHashMap<>();
        for (ModelAggregate m : modelRepo.findAllModels()) {
            if (m.getLifecycleState() == ModelAggregate.LifecycleState.PURGED || m.getActiveVersionId() == null) continue;
            modelRepo.findVersionById(m.getActiveVersionId()).ifPresent(v -> {
                if (v.getSnapshot() != null) map.put(m.getName(), v.getSnapshot());
            });
        }
        return map;
    }

    private long safeCountRecords(ModelSnapshot snap) {
        try {
            return dataAccessor.countAllActiveRecords(snap);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String currentUserId() {
        return MetaContext.current() != null ? MetaContext.current().getUserId() : MetaContext.SUPERUSER;
    }

    private static String nowStr() {
        return TS_FMT.format(LocalDateTime.now());
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
