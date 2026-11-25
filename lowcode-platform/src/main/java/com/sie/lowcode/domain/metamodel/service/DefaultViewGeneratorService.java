package com.sie.lowcode.domain.metamodel.service;

import com.sie.lowcode.domain.metamodel.model.*;
import com.sie.lowcode.domain.uischema.UiViewDef;
import java.util.*;

/**
 * 默认三件套视图自动生成领域服务（对标 iidp dev-center MetaView.buildDefaultViews）：
 * 根据模型的 PropertyDef、ServiceDef 与 ER 关系，自动构建 grid、search、form（含 OneToMany Tabs）视图。
 */
public class DefaultViewGeneratorService {

    public List<UiViewDef> generateDefaultTriadViews(ModelSnapshot snapshot, Map<String, ModelSnapshot> knownModels) {
        List<UiViewDef> result = new ArrayList<>();
        result.add(buildGridView(snapshot));
        result.add(buildSearchView(snapshot));
        result.add(buildFormView(snapshot, knownModels));
        return result;
    }

    public UiViewDef buildGridView(ModelSnapshot snapshot) {
        UiViewDef view = new UiViewDef();
        String key = snapshot.getName() + "_grid";
        view.setId(key);
        view.setKey(key);
        view.setName(snapshot.getDisplayName() + "-表格");
        view.setModel(snapshot.getName());
        view.setType("grid");
        view.setMode("primary");

        List<Map<String, Object>> columns = new ArrayList<>();
        for (PropertyDef p : snapshot.getProperties()) {
            if (!p.isDisplay() || p.isOneToMany() || p.isManyToMany() || "Json".equalsIgnoreCase(p.getDataType())) continue;
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", p.getName());
            col.put("displayName", p.getDisplayName());
            col.put("sortable", true);
            if (p.isSelection() || p.isDict() || "Boolean".equalsIgnoreCase(p.getDataType())) {
                col.put("width", "120px");
            }
            columns.add(col);
        }

        List<Map<String, Object>> tbar = new ArrayList<>();
        tbar.add(buttonMap("新增", "create", "create", "primary", null, null, null, null, null));
        tbar.add(buttonMap("批量删除", "delete", "delete", "danger", null, "确认删除选中记录？", null, Map.of("ids", "$selectedIds"), "refreshTable"));

        List<Map<String, Object>> buttons = new ArrayList<>();
        buttons.add(buttonMap("详情", "preview", "read", "default", null, null, null, null, null));
        buttons.add(buttonMap("编辑", "edit", "update", "primary", null, null, null, null, null));
        buttons.add(buttonMap("删除", "delete", "delete", "danger", null, "确认删除该记录？", null, Map.of("id", "$row.id"), "refreshTable"));
        for (ServiceDef svc : snapshot.getServices()) {
            if (ServiceDef.TYPE_BUILTIN_CRUD.equalsIgnoreCase(svc.getType())) continue;
            String enableCond = null;
            if (svc.getTransitionRule() != null && !svc.getTransitionRule().getFromStates().isEmpty()) {
                String sf = svc.getTransitionRule().getStatusField();
                String joined = String.join(",", svc.getTransitionRule().getFromStates().stream().map(s -> "'" + s + "'").toList());
                enableCond = "[" + joined + "].includes(row." + sf + ")";
            }
            Map<String, Object> btn = buttonMap(
                    svc.getDisplayName(), "custom", svc.getAuth(), "success",
                    svc.getName(), null, enableCond, Map.of("id", "$row.id"), "refreshTable"
            );
            if (!svc.getParamsSchema().isEmpty()) {
                btn.put("paramsForm", svc.getParamsSchema());
            }
            buttons.add(btn);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "grid");
        body.put("columns", columns);
        body.put("tbar", tbar);
        body.put("buttons", buttons);
        view.setBody(body);
        return view;
    }

    public UiViewDef buildSearchView(ModelSnapshot snapshot) {
        UiViewDef view = new UiViewDef();
        String key = snapshot.getName() + "_search";
        view.setId(key);
        view.setKey(key);
        view.setName(snapshot.getDisplayName() + "-搜索");
        view.setModel(snapshot.getName());
        view.setType("search");
        view.setMode("primary");

        List<Map<String, Object>> columns = new ArrayList<>();
        int count = 0;
        for (PropertyDef p : snapshot.getProperties()) {
            if (!p.isDisplay() || p.isOneToMany() || p.isManyToMany() || "Text".equalsIgnoreCase(p.getDataType()) || "Json".equalsIgnoreCase(p.getDataType()) || p.isFile()) {
                continue;
            }
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", p.getName());
            col.put("displayName", p.getDisplayName());
            columns.add(col);
            count++;
            if (count >= 4) break;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "search");
        body.put("columns", columns);
        view.setBody(body);
        return view;
    }

    public UiViewDef buildFormView(ModelSnapshot snapshot, Map<String, ModelSnapshot> knownModels) {
        UiViewDef view = new UiViewDef();
        String key = snapshot.getName() + "_form";
        view.setId(key);
        view.setKey(key);
        view.setName(snapshot.getDisplayName() + "-表单");
        view.setModel(snapshot.getName());
        view.setType("form");
        view.setMode("primary");

        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> tabs = new ArrayList<>();

        for (PropertyDef p : snapshot.getProperties()) {
            if (!p.isDisplay()) continue;
            if (p.isOneToMany()) {
                tabs.add(buildChildTab(p, knownModels));
            } else if (p.isManyToMany()) {
                tabs.add(buildManyToManyTab(p, knownModels));
            } else {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", p.getName());
                col.put("displayName", p.getDisplayName());
                columns.add(col);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "form");
        body.put("columns", columns);
        body.put("tabs", tabs);
        view.setBody(body);
        return view;
    }

    private Map<String, Object> buildManyToManyTab(PropertyDef m2mProp, Map<String, ModelSnapshot> knownModels) {
        List<Map<String, Object>> targetCols = new ArrayList<>();
        if (knownModels != null && knownModels.containsKey(m2mProp.getRelateModel())) {
            ModelSnapshot targetSnap = knownModels.get(m2mProp.getRelateModel());
            for (PropertyDef cp : targetSnap.getProperties()) {
                if (!cp.isDisplay() || cp.isOneToMany() || cp.isManyToMany()) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", cp.getName());
                c.put("displayName", cp.getDisplayName());
                targetCols.add(c);
                if (targetCols.size() >= 5) break;
            }
        }
        Map<String, Object> tabBody = new LinkedHashMap<>();
        tabBody.put("type", "grid,select");
        tabBody.put("relationType", "ManyToMany");
        tabBody.put("field", m2mProp.getName());
        tabBody.put("relateModel", m2mProp.getRelateModel());
        tabBody.put("columns", targetCols);

        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("header", m2mProp.getDisplayName());
        tab.put("body", tabBody);
        tab.put("tbar", List.of(
                buttonMap("添加关联", "addEr", "create", "primary", null, null, null, null, null),
                buttonMap("移除关联", "deleteEr", "delete", "danger", null, null, null, null, null)
        ));
        tab.put("buttons", Collections.emptyList());
        return tab;
    }

    private Map<String, Object> buildChildTab(PropertyDef o2mProp, Map<String, ModelSnapshot> knownModels) {
        List<Map<String, Object>> childCols = new ArrayList<>();
        if (knownModels != null && knownModels.containsKey(o2mProp.getRelateModel())) {
            ModelSnapshot childSnap = knownModels.get(o2mProp.getRelateModel());
            for (PropertyDef cp : childSnap.getProperties()) {
                if (!cp.isDisplay() || cp.isOneToMany() || cp.isManyToMany() || Objects.equals(cp.getName(), o2mProp.getInverseName())) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", cp.getName());
                c.put("displayName", cp.getDisplayName());
                childCols.add(c);
            }
        }
        Map<String, Object> tabBody = new LinkedHashMap<>();
        tabBody.put("type", "grid,form");
        tabBody.put("field", o2mProp.getName());
        tabBody.put("relateModel", o2mProp.getRelateModel());
        tabBody.put("inverseName", o2mProp.getInverseName());
        tabBody.put("columns", childCols);
        tabBody.put("edit", Map.of("type", "form", "columns", childCols));

        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("header", o2mProp.getDisplayName());
        tab.put("body", tabBody);
        tab.put("tbar", List.of(
                buttonMap("添加明细", "createEr", "create", "primary", null, null, null, null, null),
                buttonMap("删除明细", "deleteEr", "delete", "danger", null, null, null, null, null)
        ));
        tab.put("buttons", List.of(
                buttonMap("编辑", "updateEr", "update", "primary", null, null, null, null, null)
        ));
        return tab;
    }

    private Map<String, Object> buttonMap(String name, String action, String auth, String type,
                                          String service, String confirm, String enableCondition,
                                          Map<String, Object> args, String actionAfter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("action", action);
        m.put("auth", auth);
        m.put("type", type);
        if (service != null) m.put("service", service);
        if (confirm != null) m.put("confirm", confirm);
        if (enableCondition != null) m.put("enableCondition", enableCondition);
        if (args != null) m.put("args", args);
        if (actionAfter != null) m.put("actionAfter", actionAfter);
        return m;
    }
}
