package com.sie.lowcode.infrastructure.container;

import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.metamodel.repository.FoundationRepository;
import com.sie.lowcode.domain.metamodel.repository.ModelRepository;
import com.sie.lowcode.domain.metamodel.service.ModelInheritanceDomainService;
import com.sie.lowcode.domain.runtime.*;
import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.domain.shared.MetaContext;
import com.sie.lowcode.infrastructure.persistence.sqlite.DynamicDataAccessorImpl;
import com.sie.lowcode.infrastructure.script.GroovyScriptExecutorImpl;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存元模型运行时注册容器（对标 engine IMetaContainer + EdgeIMetaManager）：
 * 维护所有模型聚合、解析继承与扩展叠加后的当前有效快照（ModelInheritanceDomainService）与历史版本快照。
 */
@Component
@DependsOn("sqliteSchemaInitializer")
public class MetaContainerMemoryRegistry {
    private final ModelRepository modelRepository;
    private final FoundationRepository foundationRepository;
    private final DynamicDataAccessorImpl dataAccessor;
    private final GroovyScriptExecutorImpl scriptExecutor;
    private final ExtensionRegistry extensionRegistry = new ExtensionRegistry();
    private final ModelInheritanceDomainService inheritanceService = new ModelInheritanceDomainService();

    private final Map<String, ModelAggregate> modelsByName = new ConcurrentHashMap<>();
    private final Map<String, ModelSnapshot> activeSnapshotsByName = new ConcurrentHashMap<>();
    private final Map<String, ModelSnapshot> snapshotsByVersionId = new ConcurrentHashMap<>();

    public MetaContainerMemoryRegistry(ModelRepository modelRepository,
                                       FoundationRepository foundationRepository,
                                       DynamicDataAccessorImpl dataAccessor,
                                       GroovyScriptExecutorImpl scriptExecutor) {
        this.modelRepository = modelRepository;
        this.foundationRepository = foundationRepository;
        this.dataAccessor = dataAccessor;
        this.scriptExecutor = scriptExecutor;
    }

    @PostConstruct
    public void init() {
        dataAccessor.setModelLookup(this::getActiveSnapshotOrNull);
        dataAccessor.setDictLookup(foundationRepository::findDictItemsByType);
        dataAccessor.setCodeGenerator(foundationRepository::nextSequenceCode);
        dataAccessor.setExprEvaluator(scriptExecutor::evaluateInlineExpression);
        dataAccessor.setServiceInvoker((meta, modelName, svcName, args) -> createRecordSet(meta, modelName).call(svcName, args));
        reloadAll();
    }

    public synchronized void reloadAll() {
        modelsByName.clear();
        activeSnapshotsByName.clear();
        snapshotsByVersionId.clear();

        Map<String, ModelSnapshot> rawActiveSnapshots = new LinkedHashMap<>();
        List<ModelAggregate> allModels = modelRepository.findAllModels();
        Map<String, List<ModelVersion>> versionsByModel = new LinkedHashMap<>();
        for (ModelAggregate model : allModels) {
            if (model.getLifecycleState() == ModelAggregate.LifecycleState.PURGED) {
                continue;
            }
            modelsByName.put(model.getName(), model);
            List<ModelVersion> vers = modelRepository.findVersionsByModel(model.getName());
            versionsByModel.put(model.getName(), vers);
            if (model.getActiveVersionId() != null) {
                modelRepository.findVersionById(model.getActiveVersionId()).ifPresent(v -> {
                    if (v.getSnapshot() != null) {
                        rawActiveSnapshots.put(model.getName(), v.getSnapshot());
                    }
                });
            }
        }

        // 解析模型抽象继承 (parents) 与同/异名扩展包叠加 (extendModel)，放入活动运行态与版本解析映射
        for (String name : rawActiveSnapshots.keySet()) {
            ModelSnapshot effective = inheritanceService.resolveEffectiveSnapshot(name, rawActiveSnapshots);
            if (effective != null) {
                activeSnapshotsByName.put(name, effective);
            }
        }
        for (Map.Entry<String, List<ModelVersion>> entry : versionsByModel.entrySet()) {
            String modelName = entry.getKey();
            ModelAggregate agg = modelsByName.get(modelName);
            for (ModelVersion v : entry.getValue()) {
                if (v.getSnapshot() == null) continue;
                ModelSnapshot resolved = null;
                if (agg != null && Objects.equals(v.getId(), agg.getActiveVersionId())) {
                    resolved = activeSnapshotsByName.get(modelName);
                }
                if (resolved == null) {
                    Map<String, ModelSnapshot> temp = new LinkedHashMap<>(rawActiveSnapshots);
                    temp.put(modelName, v.getSnapshot());
                    resolved = inheritanceService.resolveEffectiveSnapshot(modelName, temp);
                }
                ModelSnapshot putSnap = resolved != null ? resolved : v.getSnapshot();
                snapshotsByVersionId.put(v.getId(), putSnap);
                snapshotsByVersionId.put(modelName + "@" + v.getVersionNo(), putSnap);
            }
        }
    }

    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    public ModelSnapshot getActiveSnapshotOrNull(String modelName) {
        return activeSnapshotsByName.get(modelName);
    }

    public ModelAggregate getModelAggregate(String modelName) {
        return modelsByName.get(modelName);
    }

    public Map<String, ModelSnapshot> getAllActiveSnapshots() {
        return Collections.unmodifiableMap(activeSnapshotsByName);
    }

    public MetaContext newContext(String tenantId, String userId) {
        return new MetaContext(tenantId, userId, this::createRecordSet);
    }

    public RecordSet createRecordSet(MetaContext meta, String modelName, String... ids) {
        ModelAggregate agg = modelsByName.get(modelName);
        if (agg == null) {
            throw DomainException.methodNotFound("模型不存在: " + modelName);
        }
        if (!agg.isAvailableForRuntime()) {
            throw DomainException.stateGuardFailed("模型 [" + agg.getDisplayName() + "] 当前状态为 [" + agg.getLifecycleState() + "]，普通业务入口已关闭");
        }
        ModelSnapshot snap = activeSnapshotsByName.get(modelName);
        if (snap == null) {
            throw DomainException.stateGuardFailed("模型 [" + modelName + "] 尚未激活生效版本");
        }
        List<String> idList = ids == null ? Collections.emptyList() : Arrays.asList(ids);
        return new RecordSet(meta, agg, snap, idList, dataAccessor, extensionRegistry,
                snapshotsByVersionId::get, scriptExecutor);
    }
}
