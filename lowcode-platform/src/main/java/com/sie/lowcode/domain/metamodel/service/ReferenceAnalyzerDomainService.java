package com.sie.lowcode.domain.metamodel.service;

import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.uischema.UiViewDef;
import java.util.*;

/**
 * 引用依赖分析与破坏性变更检查领域服务：
 * 扫描视图列、搜索区、表单控件、子表、状态转移与跨模型外键，生成 meta_model_ref 引用图，
 * 并在版本升级检查阶段拦截直接删除或破坏性变更仍被强引用的字段。
 */
public class ReferenceAnalyzerDomainService {

    public List<ReferenceRecord> extractReferences(ModelVersion version,
                                                   List<UiViewDef> views,
                                                   List<ModelSnapshot> allActiveSnapshots) {
        ModelSnapshot snapshot = version.getSnapshot();
        List<ReferenceRecord> refs = new ArrayList<>();
        if (snapshot == null) return refs;

        // 1. 扫描模型自身视图 (grid / search / form / tabs / buttons)
        if (views != null) {
            for (UiViewDef view : views) {
                Map<String, Object> body = view.getBody();
                if (body == null) continue;
                scanColumnList(refs, version, view.getKey(), "VIEW_" + view.getType().toUpperCase() + "_COL",
                        asListOfMaps(body.get("columns")));
                scanButtons(refs, version, view.getKey(), asListOfMaps(body.get("buttons")));
                scanButtons(refs, version, view.getKey(), asListOfMaps(body.get("tbar")));
                for (Map<String, Object> tab : asListOfMaps(body.get("tabs"))) {
                    Object tb = tab.get("body");
                    if (tb instanceof Map) {
                        Map<?, ?> tabBody = (Map<?, ?>) tb;
                        Object field = tabBody.get("field");
                        if (field != null) {
                            refs.add(new ReferenceRecord(UUID.randomUUID().toString(),
                                    snapshot.getName(), version.getId(),
                                    "VIEW_FORM_TAB", "views." + view.getKey() + ".tabs[field=" + field + "]",
                                    field.toString(), "表单子表页签引用 OneToMany 字段"));
                        }
                    }
                }
            }
        }

        // 2. 扫描模型服务状态转移规则引用的字段
        for (ServiceDef svc : snapshot.getServices()) {
            if (svc.getTransitionRule() != null) {
                String sf = svc.getTransitionRule().getStatusField();
                if (sf != null && !sf.isBlank()) {
                    refs.add(new ReferenceRecord(UUID.randomUUID().toString(),
                            snapshot.getName(), version.getId(),
                            "TRANSITION_RULE", "services." + svc.getName() + ".transitionRule.statusField",
                            sf, "声明式状态机服务依赖该状态字段"));
                }
            }
        }

        // 3. 扫描属性自身的 related 关联依赖（如 dept_id.code 引用了 dept_id）
        for (PropertyDef p : snapshot.getProperties()) {
            if (p.getRelated() != null && p.getRelated().contains(".")) {
                String fkField = p.getRelated().substring(0, p.getRelated().indexOf('.'));
                refs.add(new ReferenceRecord(UUID.randomUUID().toString(),
                        snapshot.getName(), version.getId(),
                        "RELATED_FIELD", "properties." + p.getName() + ".related",
                        fkField, "关联带出字段 [" + p.getRelated() + "] 依赖外键属性 " + fkField));
            }
        }

        // 4. 扫描外部模型通过 ManyToOne / ManyToMany 指向当前模型
        if (allActiveSnapshots != null) {
            for (ModelSnapshot other : allActiveSnapshots) {
                if (other.getName().equals(snapshot.getName())) continue;
                for (PropertyDef op : other.getProperties()) {
                    if ((op.isManyToOne() || op.isManyToMany()) && snapshot.getName().equals(op.getRelateModel())) {
                        refs.add(new ReferenceRecord(UUID.randomUUID().toString(),
                                snapshot.getName(), version.getId(),
                                op.isManyToMany() ? "EXTERNAL_M2M_RELATION" : "EXTERNAL_RELATION",
                                other.getName() + "." + op.getName(),
                                "id", "外部模型 [" + other.getDisplayName() + "] 关联本模型"));
                    }
                }
            }
        }
        return refs;
    }

    public ImpactReport analyzeImpact(ModelVersion activeVersion,
                                      ModelVersion candidateVersion,
                                      List<UiViewDef> existingViews,
                                      List<ModelSnapshot> otherActiveModels,
                                      long existingRecordCount,
                                      String validationHash) {
        ImpactReport report = new ImpactReport();
        report.setModelName(candidateVersion.getModelName());
        report.setCandidateVersionId(candidateVersion.getId());
        report.setValidationHash(validationHash);

        ModelSnapshot candidateSnap = candidateVersion.getSnapshot();
        if (!candidateSnap.isAbstractModel() && !candidateSnap.isExtensionPackage()) {
            boolean hasDisplayProp = candidateSnap.getProperties().stream().anyMatch(PropertyDef::isDisplayForModel);
            if (!hasDisplayProp) {
                report.getWarnings().add("建议至少在一个主字段上勾选 displayForModel=true，避免外部模型下拉框展示为 UUID（参考手册第 6.8 节）");
            }
        }
        List<ReferenceRecord> currentRefs = extractReferences(
                activeVersion != null ? activeVersion : candidateVersion, existingViews, otherActiveModels);
        report.setReferences(currentRefs);

        if (activeVersion != null && activeVersion.getSnapshot() != null) {
            ModelSnapshot prev = activeVersion.getSnapshot();

            // 识别新增字段与退役字段
            for (PropertyDef np : candidateSnap.getProperties()) {
                PropertyDef old = prev.getProperty(np.getName());
                if (old == null) {
                    report.getAddedFields().add(np.getName());
                    if (np.isRequired() && np.getDefaultValue() == null && existingRecordCount > 0) {
                        report.getWarnings().add("已有 " + existingRecordCount + " 条存量记录，建议为新增必填字段 ["
                                + np.getDisplayName() + "] 提供 defaultValue 以保证向后兼容");
                    }
                } else {
                    if (old.isDisplay() && !np.isDisplay()) {
                        report.getDeprecatedFields().add(np.getName());
                        report.getWarnings().add("字段 [" + np.getDisplayName() + " (" + np.getName()
                                + ")] 已安全标记为兼容退役 (display=false)，新版表单隐藏且历史记录保留读取契约");
                    }
                    if (!Objects.equals(old.toSqliteType(), np.toSqliteType())) {
                        report.getBlockingReasons().add("破坏性类型变更阻断：字段 [" + np.getName()
                                + "] 不允许将存储类型从 " + old.getDataType() + " 改为 " + np.getDataType());
                    }
                }
            }

            // 检测是否直接物理删除了被引用或有存量数据的字段
            for (PropertyDef oldProp : prev.getProperties()) {
                PropertyDef existsInNew = candidateSnap.getProperty(oldProp.getName());
                if (existsInNew == null) {
                    List<ReferenceRecord> hits = new ArrayList<>();
                    for (ReferenceRecord rr : currentRefs) {
                        if (oldProp.getName().equals(rr.getTargetField())) {
                            hits.add(rr);
                        }
                    }
                    if (!hits.isEmpty()) {
                        for (ReferenceRecord hit : hits) {
                            report.getBlockingReasons().add("破坏性删除阻断：字段 [" + oldProp.getDisplayName()
                                    + " (" + oldProp.getName() + ")] 仍被 [" + hit.getSourceLocation()
                                    + "] 引用，禁止直接删除！请改设 display=false 进行兼容退役。");
                        }
                    } else if (existingRecordCount > 0) {
                        report.getBlockingReasons().add("数据安全阻断：模型已有 " + existingRecordCount
                                + " 条历史记录，禁止直接删除字段 [" + oldProp.getName() + "]，请标记 display=false 兼容退役。");
                    }
                }
            }
        }

        report.setPassed(report.getBlockingReasons().isEmpty());
        return report;
    }

    private void scanColumnList(List<ReferenceRecord> refs, ModelVersion ver, String viewKey, String sourceType, List<Map<String, Object>> cols) {
        for (int i = 0; i < cols.size(); i++) {
            Map<String, Object> col = cols.get(i);
            Object name = col.get("name");
            if (name != null) {
                refs.add(new ReferenceRecord(UUID.randomUUID().toString(), ver.getModelName(), ver.getId(),
                        sourceType, "views." + viewKey + ".columns[" + i + "]", name.toString(),
                        "视图字段绑定 (" + col.getOrDefault("displayName", name) + ")"));
            }
        }
    }

    private void scanButtons(List<ReferenceRecord> refs, ModelVersion ver, String viewKey, List<Map<String, Object>> btns) {
        ModelSnapshot snap = ver.getSnapshot();
        for (Map<String, Object> btn : btns) {
            Object condObj = btn.get("enableCondition");
            if (condObj != null) {
                String cond = condObj.toString();
                for (PropertyDef p : snap.getProperties()) {
                    if (cond.contains("row." + p.getName())) {
                        refs.add(new ReferenceRecord(UUID.randomUUID().toString(), ver.getModelName(), ver.getId(),
                                "VIEW_BUTTON_COND", "views." + viewKey + ".button[" + btn.get("name") + "].enableCondition",
                                p.getName(), "按钮条件表达式引用: " + cond));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object raw) {
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> res = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item instanceof Map) {
                res.add((Map<String, Object>) item);
            } else if (item instanceof String) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", item.toString());
                res.add(m);
            }
        }
        return res;
    }
}
