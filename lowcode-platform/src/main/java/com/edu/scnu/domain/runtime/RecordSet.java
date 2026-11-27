package com.edu.scnu.domain.runtime;

import com.edu.scnu.domain.metamodel.model.*;
import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.domain.shared.MetaContext;
import java.util.*;

/**
 * 统一模型记录集句柄（对标 Odoo RecordSet & sie-snest-engine RecordSet）：
 * 所有模型数据的读写与自定义方法调用均经由 RecordSet.call() 走方法重写链派发，
 * 使内置标准 CRUD、Groovy 脚本、声明式状态机与 Java 扩展拥有完全一致的调用语义。
 */
public class RecordSet {
    private final MetaContext meta;
    private final ModelAggregate modelAggregate;
    private final ModelSnapshot activeSnapshot;
    private final List<String> ids;
    private final DynamicDataAccessor dataAccessor;
    private final ExtensionRegistry extensionRegistry;
    private final VersionResolver versionResolver;
    private final ScriptServiceExecutor scriptExecutor;

    @FunctionalInterface
    public interface VersionResolver {
        ModelSnapshot resolveByVersionId(String versionId);
    }

    @FunctionalInterface
    public interface ScriptServiceExecutor {
        Object execute(RecordSet rs, ServiceDef serviceDef, Map<String, Object> args);
    }

    public RecordSet(MetaContext meta,
                     ModelAggregate modelAggregate,
                     ModelSnapshot activeSnapshot,
                     List<String> ids,
                     DynamicDataAccessor dataAccessor,
                     ExtensionRegistry extensionRegistry,
                     VersionResolver versionResolver,
                     ScriptServiceExecutor scriptExecutor) {
        this.meta = meta;
        this.modelAggregate = modelAggregate;
        this.activeSnapshot = activeSnapshot;
        this.ids = ids != null ? new ArrayList<>(ids) : new ArrayList<>();
        this.dataAccessor = dataAccessor;
        this.extensionRegistry = extensionRegistry;
        this.versionResolver = versionResolver;
        this.scriptExecutor = scriptExecutor;
    }

    public MetaContext getMeta() { return meta; }
    public String getModelName() { return modelAggregate.getName(); }
    public ModelAggregate getModelAggregate() { return modelAggregate; }
    public ModelSnapshot getSnapshot() { return activeSnapshot; }
    public List<String> getIds() { return ids; }
    public String getId() { return ids.isEmpty() ? null : ids.get(0); }

    public RecordSet browse(String... targetIds) {
        List<String> nextIds = targetIds == null ? Collections.emptyList() : Arrays.asList(targetIds);
        return new RecordSet(meta, modelAggregate, activeSnapshot, nextIds,
                dataAccessor, extensionRegistry, versionResolver, scriptExecutor);
    }

    /**
     * 统一模型服务派发入口（等价于 rs.call("method", args)）。
     */
    public Object call(String serviceName, Map<String, Object> args) {
        return dispatchFromIndex(0, serviceName, args != null ? args : Collections.emptyMap());
    }

    private static final ThreadLocal<Map<String, Integer>> SUPER_DEPTH = ThreadLocal.withInitial(HashMap::new);
    private boolean serviceWrite;

    /** Trusted model services can create or update workflow records while still running write validation. */
    public Object createFromService(Map<String, Object> args) {
        boolean previous = serviceWrite;
        serviceWrite = true;
        try {
            return call("create", args);
        } finally {
            serviceWrite = previous;
        }
    }

    /** Trusted model services can update workflow fields while still running write validation. */
    public Object updateFromService(Map<String, Object> args) {
        boolean previous = serviceWrite;
        serviceWrite = true;
        try {
            return call("update", args);
        } finally {
            serviceWrite = previous;
        }
    }

    public Object callSuper(String serviceName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Collections.emptyMap();
        ModelSnapshot effectiveSnapshot = resolveEffectiveSnapshot(safeArgs);
        String stackKey = getModelName() + "::" + serviceName;
        List<ServiceDef> parents = effectiveSnapshot.getParentServiceChains().getOrDefault(serviceName, Collections.emptyList());
        int nextDepth = SUPER_DEPTH.get().getOrDefault(stackKey, 0);
        if (nextDepth < parents.size()) {
            ServiceDef parentSvc = parents.get(nextDepth);
            SUPER_DEPTH.get().put(stackKey, nextDepth + 1);
            try {
                return scriptExecutor.execute(this, parentSvc, safeArgs);
            } finally {
                if (nextDepth == 0) SUPER_DEPTH.get().remove(stackKey);
                else SUPER_DEPTH.get().put(stackKey, nextDepth);
            }
        }
        return dispatchBuiltinOnly(effectiveSnapshot, serviceName, safeArgs);
    }

    /**
     * 沿重写链调用父级实现（等价于 Odoo super().method() / rs.callSuper(cls, "method", args)）。
     */
    public Object callSuper(String currentOwnerKey, String serviceName, Map<String, Object> args) {
        List<ExtensionRegistry.HandlerEntry> chain = extensionRegistry.getChain(getModelName(), serviceName);
        int nextIdx = 0;
        if (currentOwnerKey != null) {
            for (int i = 0; i < chain.size(); i++) {
                if (Objects.equals(chain.get(i).getOwnerKey(), currentOwnerKey)) {
                    nextIdx = i + 1;
                    break;
                }
            }
        }
        return dispatchFromIndex(nextIdx, serviceName, args != null ? args : Collections.emptyMap());
    }

    public ServiceActionResult message(String msg) {
        return ServiceActionResult.message(msg);
    }

    public ServiceActionResult reloadWindow(String msg) {
        return ServiceActionResult.reloadWindow(msg);
    }

    private Object dispatchFromIndex(int startIndex, String serviceName, Map<String, Object> args) {
        List<ExtensionRegistry.HandlerEntry> chain = extensionRegistry.getChain(getModelName(), serviceName);
        if (startIndex < chain.size()) {
            return chain.get(startIndex).getHandler().execute(this, args);
        }

        // 若单据绑定了历史版本（如 V1 在途单据），自动按其 bound_version_id 解析服务与字段契约
        ModelSnapshot effectiveSnapshot = resolveEffectiveSnapshot(args);
        ServiceDef serviceDef = effectiveSnapshot.getService(serviceName);
        if (serviceDef != null && !ServiceDef.TYPE_BUILTIN_CRUD.equalsIgnoreCase(serviceDef.getType())) {
            return scriptExecutor.execute(this, serviceDef, args);
        }

        return dispatchBuiltinOnly(effectiveSnapshot, serviceName, args);
    }

    private Object dispatchBuiltinOnly(ModelSnapshot effectiveSnapshot, String serviceName, Map<String, Object> args) {
        return executeBuiltinService(effectiveSnapshot, serviceName, args);
    }

    private ModelSnapshot resolveEffectiveSnapshot(Map<String, Object> args) {
        if (activeSnapshot.isUseActiveVersionForRecords()) return activeSnapshot;
        String recordId = extractSingleId(args);
        if (recordId != null) {
            RecordData existing = dataAccessor.findById(activeSnapshot, recordId, false);
            if (existing != null) {
                String boundVer = existing.getStr("bound_version_id");
                if (boundVer != null && versionResolver != null) {
                    ModelSnapshot boundSnap = versionResolver.resolveByVersionId(boundVer);
                    if (boundSnap != null) {
                        return boundSnap;
                    }
                }
            }
        }
        return activeSnapshot;
    }

    @SuppressWarnings("unchecked")
    private Object executeBuiltinService(ModelSnapshot snap, String serviceName, Map<String, Object> args) {
        switch (serviceName) {
            case "search": {
                FilterDsl filter = FilterDsl.parse(args.get("filter") != null ? args.get("filter") : args.get("query"));
                List<String> props = args.get("properties") instanceof List ? (List<String>) args.get("properties") : Collections.singletonList("*");
                Integer limit = args.get("limit") instanceof Number ? ((Number) args.get("limit")).intValue() : 20;
                Integer offset = args.get("offset") instanceof Number ? ((Number) args.get("offset")).intValue() : 0;
                String order = args.get("order") != null ? args.get("order").toString() : null;
                boolean useDisplay = args.get("useDisplayForModel") == null || Boolean.TRUE.equals(args.get("useDisplayForModel"));
                List<RecordData> records = dataAccessor.search(snap, filter, props, limit, offset, order, useDisplay);
                long total = dataAccessor.count(snap, filter);
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("records", records);
                res.put("total", total);
                return res;
            }
            case "count": {
                FilterDsl filter = FilterDsl.parse(args.get("filter"));
                return dataAccessor.count(snap, filter);
            }
            case "exists": {
                FilterDsl filter = FilterDsl.parse(args.get("filter"));
                return dataAccessor.exists(snap, filter);
            }
            case "addMissingDefaultValues": {
                return dataAccessor.computeDefaultValues(snap, args);
            }
            case "getSelectionOptions": {
                String field = args.get("field") != null ? args.get("field").toString() : "";
                PropertyDef prop = snap.getProperty(field);
                if (prop == null) return Collections.emptyList();
                if (prop.getSelectionMethod() != null && !prop.getSelectionMethod().isBlank()) {
                    return call(prop.getSelectionMethod(), args);
                }
                return prop.getValues();
            }
            case "find": {
                if (args.get("ids") instanceof List) {
                    List<String> targetIds = new ArrayList<>();
                    for (Object o : (List<?>) args.get("ids")) targetIds.add(String.valueOf(o));
                    return dataAccessor.findByIds(snap, targetIds, true);
                }
                String id = extractSingleId(args);
                if (id == null) {
                    throw DomainException.invalidParams("find 服务必须提供 id 参数");
                }
                RecordData row = dataAccessor.findById(snap, id, true);
                if (row == null) {
                    throw DomainException.invalidParams("未找到记录: " + id);
                }
                return row;
            }
            case "create": {
                List<Map<String, Object>> valuesList = extractValuesList(args);
                if (!Boolean.TRUE.equals(args.get("_skipValidate"))) {
                    for (Map<String, Object> vals : valuesList) {
                        validateWrite(snap, null, vals);
                    }
                }
                List<RecordData> created = dataAccessor.create(snap, valuesList);
                return created.size() == 1 && !args.containsKey("valuesList") ? created.get(0) : created;
            }
            case "update": {
                String id = extractSingleId(args);
                if (id == null) {
                    throw DomainException.invalidParams("update 服务必须提供 id 参数");
                }
                Map<String, Object> values = extractSingleValues(args);
                if (!Boolean.TRUE.equals(args.get("_skipValidate"))) {
                    validateWrite(snap, id, values);
                }
                Integer expectedVersion = null;
                if (args.get("version") instanceof Number) {
                    expectedVersion = ((Number) args.get("version")).intValue();
                } else if (values.get("version") instanceof Number) {
                    expectedVersion = ((Number) values.get("version")).intValue();
                }
                int rows = dataAccessor.update(snap, id, values, expectedVersion);
                if (rows == 0) {
                    throw DomainException.concurrentConflict("并发版本冲突或记录不存在，请刷新最新数据后重试");
                }
                return dataAccessor.findById(snap, id, true);
            }
            case "delete": {
                List<String> targetIds = extractIdList(args);
                if (targetIds.isEmpty()) {
                    throw DomainException.invalidParams("delete 服务必须提供 id 或 ids 参数");
                }
                for (String id : targetIds) {
                    ModelSnapshot boundSnapshot = resolveEffectiveSnapshot(Map.of("id", id));
                    validateOperation(boundSnapshot, "validateDelete", Map.of("id", id));
                }
                int deleted = dataAccessor.delete(snap, targetIds);
                return Map.of("deletedCount", deleted, "ids", targetIds);
            }
            case "lookup": {
                String field = args.get("field") != null ? args.get("field").toString() : null;
                String keyword = args.get("keyword") != null ? args.get("keyword").toString() : "";
                int limit = args.get("limit") instanceof Number ? ((Number) args.get("limit")).intValue() : 20;
                FilterDsl filter = FilterDsl.parse(args.get("filter"));
                // 如果传了 ManyToOne 或 ManyToMany field，则转由目标模型查询；否则直接查当前模型
                if (field != null && !field.isBlank()) {
                    PropertyDef prop = snap.getProperty(field);
                    if (prop != null && (prop.isManyToOne() || prop.isManyToMany()) && prop.getRelateModel() != null) {
                        return meta.get(prop.getRelateModel()).call("lookup",
                                Map.of("keyword", keyword, "filter", filter.getNodes(), "limit", limit));
                    }
                }
                return dataAccessor.lookup(snap, keyword, filter, limit);
            }
            default:
                throw DomainException.methodNotFound("模型 [" + getModelName() + "] 未注册服务: " + serviceName);
        }
    }

    private void validateWrite(ModelSnapshot boundSnapshot, String id, Map<String, Object> values) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (id != null) args.put("id", id);
        args.put("values", values);
        args.put("create", id == null);
        args.put("serviceWrite", serviceWrite);
        validateOperation(boundSnapshot, "validate", args);
    }

    private void validateOperation(ModelSnapshot boundSnapshot, String name, Map<String, Object> args) {
        ModelSnapshot validationSnapshot = activeSnapshot.isValidateWritesWithActiveVersion() ? activeSnapshot : boundSnapshot;
        ServiceDef validator = validationSnapshot.getService(name);
        if (validator == null) return;
        if (validationSnapshot == boundSnapshot) {
            call(name, args);
        } else {
            scriptExecutor.execute(this, validator, args);
        }
    }

    private String extractSingleId(Map<String, Object> args) {
        if (args.get("id") != null && !args.get("id").toString().isBlank()) {
            return args.get("id").toString();
        }
        if (args.get("ids") instanceof List && !((List<?>) args.get("ids")).isEmpty()) {
            return String.valueOf(((List<?>) args.get("ids")).get(0));
        }
        if (!this.ids.isEmpty()) {
            return this.ids.get(0);
        }
        if (args.get("values") instanceof Map) {
            Object vid = ((Map<?, ?>) args.get("values")).get("id");
            if (vid != null) return vid.toString();
        }
        return null;
    }

    private List<String> extractIdList(Map<String, Object> args) {
        List<String> res = new ArrayList<>();
        if (args.get("ids") instanceof List) {
            for (Object o : (List<?>) args.get("ids")) {
                if (o != null) res.add(o.toString());
            }
        } else if (args.get("id") != null) {
            res.add(args.get("id").toString());
        } else if (!this.ids.isEmpty()) {
            res.addAll(this.ids);
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractValuesList(Map<String, Object> args) {
        if (args.get("valuesList") instanceof List) {
            return (List<Map<String, Object>>) args.get("valuesList");
        }
        return Collections.singletonList(extractSingleValues(args));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSingleValues(Map<String, Object> args) {
        if (args.get("values") instanceof Map) {
            return (Map<String, Object>) args.get("values");
        }
        if (args.get("data") instanceof Map) {
            return (Map<String, Object>) args.get("data");
        }
        Map<String, Object> fallback = new LinkedHashMap<>(args);
        fallback.remove("id");
        fallback.remove("ids");
        return fallback;
    }
}
