package com.sie.lowcode.domain.runtime;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型服务扩展重写链注册中心（对标 ModelMeta.methods LinkedList + callSuper）：
 * 支持为任意 (modelName, serviceName) 挂接多个有序覆盖层，调用 rs.call() 从链顶触发，
 * 覆盖层内部可调用 rs.callSuper(ownerKey, method, args) 沿链向下回落至引擎底层实现。
 */
public class ExtensionRegistry {

    public static class HandlerEntry {
        private final String ownerKey;
        private final ModelServiceHandler handler;

        public HandlerEntry(String ownerKey, ModelServiceHandler handler) {
            this.ownerKey = ownerKey;
            this.handler = handler;
        }

        public String getOwnerKey() { return ownerKey; }
        public ModelServiceHandler getHandler() { return handler; }
    }

    private final Map<String, LinkedList<HandlerEntry>> chains = new ConcurrentHashMap<>();

    private String key(String modelName, String serviceName) {
        return modelName + "::" + serviceName;
    }

    /**
     * 向链表头部注册最高优先级扩展（后注册的扩展包装先注册的实现）。
     */
    public void registerOverride(String modelName, String serviceName, String ownerKey, ModelServiceHandler handler) {
        chains.compute(key(modelName, serviceName), (k, list) -> {
            LinkedList<HandlerEntry> next = list != null ? new LinkedList<>(list) : new LinkedList<>();
            next.removeIf(e -> Objects.equals(e.getOwnerKey(), ownerKey));
            next.addFirst(new HandlerEntry(ownerKey, handler));
            return next;
        });
    }

    public void unregisterOwner(String modelName, String ownerKey) {
        String prefix = modelName + "::";
        chains.forEach((k, list) -> {
            if (k.startsWith(prefix)) {
                list.removeIf(e -> Objects.equals(e.getOwnerKey(), ownerKey));
            }
        });
    }

    public List<HandlerEntry> getChain(String modelName, String serviceName) {
        List<HandlerEntry> list = chains.get(key(modelName, serviceName));
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }
}
