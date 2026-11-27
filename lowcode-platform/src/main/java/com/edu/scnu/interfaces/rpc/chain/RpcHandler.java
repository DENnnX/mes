package com.edu.scnu.interfaces.rpc.chain;

import com.edu.scnu.domain.shared.DomainException;
import com.edu.scnu.interfaces.rpc.dto.JsonRpcRequest;

import java.util.*;

/**
 * JSON-RPC 2.0 统一责任链节点（对标 sie-snest-engine RpcHandlerChain）：
 * 1) 解析标准 iidp 信封 method="service", params:{app, tag, model, service, context, args}
 *    或简写格式 method="<model>.<service>", params:{...}
 * 2) 依次通过 Auth -> BuildMeta -> Permission -> Execute 责任链执行。
 */
public interface RpcHandler {
    @FunctionalInterface
    interface Chain {
        Object proceed(RpcRequestContext ctx);
    }

    Object handle(RpcRequestContext ctx, Chain next);

    @SuppressWarnings("unchecked")
    static void normalizeEnvelope(RpcRequestContext ctx) {
        JsonRpcRequest req = ctx.getRequest();
        if (req == null || !"2.0".equals(req.getJsonrpc()) || req.getMethod() == null || req.getMethod().isBlank()) {
            throw DomainException.invalidParams("非法 JSON-RPC 2.0 请求：缺少 jsonrpc='2.0' 或 method 字段");
        }
        Map<String, Object> params = req.getParams() != null ? req.getParams() : Collections.emptyMap();
        String method = req.getMethod().trim();

        if ("service".equalsIgnoreCase(method)) {
            ctx.setAppName(str(params.get("app")));
            ctx.setTag(params.get("tag") != null ? str(params.get("tag")) : "master");
            ctx.setModel(str(params.get("model")));
            ctx.setService(str(params.get("service")));
            if (params.get("context") instanceof Map) {
                ctx.setClientContext((Map<String, Object>) params.get("context"));
            }
            if (params.get("args") instanceof Map) {
                ctx.setArgs((Map<String, Object>) params.get("args"));
            }
        } else {
            int lastDot = method.lastIndexOf('.');
            if (lastDot <= 0 || lastDot == method.length() - 1) {
                throw DomainException.methodNotFound("无法识别的 RPC method: " + method);
            }
            ctx.setModel(method.substring(0, lastDot));
            ctx.setService(method.substring(lastDot + 1));
            if (params.get("context") instanceof Map) {
                ctx.setClientContext((Map<String, Object>) params.get("context"));
            }
            ctx.setArgs(params.get("args") instanceof Map ? (Map<String, Object>) params.get("args") : params);
        }

        if (ctx.getModel() == null || ctx.getModel().isBlank() || ctx.getService() == null || ctx.getService().isBlank()) {
            throw DomainException.invalidParams("RPC 请求必须解析出有效的 model 与 service");
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
