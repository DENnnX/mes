package com.sie.lowcode.domain.metamodel.service;

import com.sie.lowcode.domain.metamodel.model.*;
import java.util.*;

/**
 * 模型继承与扩展合并领域服务（对标 @Model(parent=...) 与 isAbstract=true）：
 * 1) 按 parents 声明顺序递归合并抽象/实体父模型的属性、服务、复合索引；
 * 2) 若存在针对该模型的非侵入扩展模型 (extendModel == targetModelName)，叠加扩展属性与服务覆盖链；
 * 3) 保留每一层被覆盖的 ServiceDef，使运行期 rs.callSuper(serviceName, args) 能逐层向上穿透。
 */
public class ModelInheritanceDomainService {

    public ModelSnapshot resolveEffectiveSnapshot(String targetName, Map<String, ModelSnapshot> allModels) {
        ModelSnapshot raw = allModels.get(targetName);
        if (raw == null) return null;

        Set<String> visited = new LinkedHashSet<>();
        ModelSnapshot merged = mergeRecursive(raw, allModels, visited, new LinkedHashMap<>());

        // 查找所有声明了 extendModel == targetName 的增量扩展模型并依次叠加
        List<ModelSnapshot> extensions = new ArrayList<>();
        for (ModelSnapshot cand : allModels.values()) {
            if (cand != null && targetName.equals(cand.getExtendModel()) && !targetName.equals(cand.getName())) {
                extensions.add(cand);
            }
        }
        extensions.sort(Comparator.comparing(ModelSnapshot::getName));
        for (ModelSnapshot ext : extensions) {
            overlaySnapshot(merged, ext, collectOverriddenMap(merged));
        }
        return merged;
    }

    private ModelSnapshot mergeRecursive(ModelSnapshot current,
                                         Map<String, ModelSnapshot> allModels,
                                         Set<String> visiting,
                                         Map<String, List<ServiceDef>> overriddenBySvc) {
        if (!visiting.add(current.getName())) {
            // 发现环路时截断防止栈溢出（前置校验已拦截）
            return cloneSnapshot(current);
        }

        ModelSnapshot base = new ModelSnapshot();
        base.setName(current.getName());
        base.setDisplayName(current.getDisplayName());
        base.setAppName(current.getAppName());
        base.setTableName(current.resolvedTableName());
        base.setDescription(current.getDescription());
        base.setVersionNo(current.getVersionNo());
        base.setExtendModel(current.getExtendModel());
        base.setAbstractModel(current.isAbstractModel());
        base.setAutoLog(current.isAutoLog());
        base.setLogicDelete(current.isLogicDelete());
        base.setOrderBy(current.getOrderBy());
        base.setDisplayFormat(current.getDisplayFormat());
        base.setDataScope(current.getDataScope());
        base.setValidateWritesWithActiveVersion(current.isValidateWritesWithActiveVersion());
        base.setUseActiveVersionForRecords(current.isUseActiveVersionForRecords());
        base.setParents(new ArrayList<>(current.getParents()));

        for (String parentName : current.getParents()) {
            if (parentName == null || parentName.isBlank() || Objects.equals(parentName, current.getName())) {
                continue;
            }
            ModelSnapshot parentRaw = allModels.get(parentName);
            if (parentRaw != null) {
                ModelSnapshot resolvedParent = mergeRecursive(parentRaw, allModels, new LinkedHashSet<>(visiting), overriddenBySvc);
                overlaySnapshot(base, resolvedParent, overriddenBySvc);
            }
        }
        overlaySnapshot(base, current, overriddenBySvc);
        visiting.remove(current.getName());
        return base;
    }

    private Map<String, List<ServiceDef>> collectOverriddenMap(ModelSnapshot base) {
        // 运行时使用 ModelSnapshot 已有顺序服务即可；如需多层 Groovy 父类可记录至 transient map
        return new LinkedHashMap<>();
    }

    private void overlaySnapshot(ModelSnapshot target, ModelSnapshot layer, Map<String, List<ServiceDef>> ignored) {
        Map<String, PropertyDef> propMap = new LinkedHashMap<>();
        for (PropertyDef p : target.getProperties()) propMap.put(p.getName(), p);
        for (PropertyDef p : layer.getProperties()) propMap.put(p.getName(), p);
        target.setProperties(new ArrayList<>(propMap.values()));

        Map<String, ServiceDef> svcMap = new LinkedHashMap<>();
        for (ServiceDef s : target.getServices()) svcMap.put(s.getName(), s);
        for (ServiceDef s : layer.getServices()) {
            ServiceDef prev = svcMap.get(s.getName());
            if (prev != null && !Objects.equals(prev, s)) {
                target.getParentServiceChains()
                        .computeIfAbsent(s.getName(), k -> new ArrayList<>())
                        .add(0, prev); // 最近被覆盖的父类实现排在最前
            }
            svcMap.put(s.getName(), s);
        }
        target.setServices(new ArrayList<>(svcMap.values()));

        Map<String, IndexDef> idxMap = new LinkedHashMap<>();
        for (IndexDef i : target.getIndexes()) {
            idxMap.put(i.getName() != null ? i.getName() : String.join("_", i.getColumnList()), i);
        }
        for (IndexDef i : layer.getIndexes()) {
            idxMap.put(i.getName() != null ? i.getName() : String.join("_", i.getColumnList()), i);
        }
        target.setIndexes(new ArrayList<>(idxMap.values()));
    }

    private ModelSnapshot cloneSnapshot(ModelSnapshot src) {
        ModelSnapshot c = new ModelSnapshot();
        c.setName(src.getName());
        c.setDisplayName(src.getDisplayName());
        c.setAppName(src.getAppName());
        c.setTableName(src.getTableName());
        c.setDescription(src.getDescription());
        c.setVersionNo(src.getVersionNo());
        c.setParents(new ArrayList<>(src.getParents()));
        c.setExtendModel(src.getExtendModel());
        c.setAbstractModel(src.isAbstractModel());
        c.setAutoLog(src.isAutoLog());
        c.setLogicDelete(src.isLogicDelete());
        c.setOrderBy(src.getOrderBy());
        c.setDisplayFormat(src.getDisplayFormat());
        c.setDataScope(src.getDataScope());
        c.setValidateWritesWithActiveVersion(src.isValidateWritesWithActiveVersion());
        c.setUseActiveVersionForRecords(src.isUseActiveVersionForRecords());
        c.setIndexes(new ArrayList<>(src.getIndexes()));
        c.setProperties(new ArrayList<>(src.getProperties()));
        c.setServices(new ArrayList<>(src.getServices()));
        return c;
    }
}
