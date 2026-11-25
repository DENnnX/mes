package com.sie.lowcode.interfaces.rpc.controller;

import com.sie.lowcode.domain.shared.DomainException;
import com.sie.lowcode.interfaces.rpc.chain.RpcHandler;
import com.sie.lowcode.interfaces.rpc.chain.RpcRequestContext;
import com.sie.lowcode.interfaces.rpc.dto.JsonRpcError;
import com.sie.lowcode.interfaces.rpc.dto.JsonRpcRequest;
import com.sie.lowcode.interfaces.rpc.dto.JsonRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 统一 JSON-RPC 2.0 协议接入网关（对标 sie-snest-engine RpcController）：
 * POST /{tenant}/rpc/service | POST /rpc/service | POST /rpc/open/service
 */
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class JsonRpcController {
    private static final Logger log = LoggerFactory.getLogger(JsonRpcController.class);
    private final List<RpcHandler> handlerChain;

    public JsonRpcController(List<RpcHandler> handlerChain) {
        this.handlerChain = handlerChain;
    }

    @PostMapping("/{tenant}/rpc/service")
    public JsonRpcResponse tenantService(@PathVariable("tenant") String tenant,
                                         @RequestHeader(value = "Authorization", required = false) String authHeader,
                                         @RequestBody JsonRpcRequest request) {
        return executeChain(tenant, authHeader, false, request);
    }

    @PostMapping("/rpc/service")
    public JsonRpcResponse rootService(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @RequestBody JsonRpcRequest request) {
        return executeChain("rbac_tenant_root", authHeader, false, request);
    }

    @PostMapping("/rpc/open/service")
    public JsonRpcResponse openService(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @RequestBody JsonRpcRequest request) {
        return executeChain("rbac_tenant_root", authHeader, true, request);
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "engine", "sie-lowcode-ddd", "protocol", "JSON-RPC 2.0");
    }

    private JsonRpcResponse executeChain(String tenant, String authHeader, boolean openEndpoint, JsonRpcRequest request) {
        Object reqId = request != null ? request.getId() : null;
        try {
            RpcRequestContext ctx = new RpcRequestContext(tenant, authHeader, openEndpoint, request);
            RpcHandler.normalizeEnvelope(ctx);
            Object result = invokeAt(0, ctx);
            return JsonRpcResponse.ok(result, reqId);
        } catch (DomainException de) {
            log.warn("RPC DomainException [{}]: {}", de.getErrorKey(), de.getMessage());
            return JsonRpcResponse.fail(
                    new JsonRpcError(de.getRpcCode(), de.getMessage(), de.getErrorKey(), de.getDetails()),
                    reqId);
        } catch (org.springframework.dao.DataAccessException dae) {
            String msg = dae.getMostSpecificCause() != null ? dae.getMostSpecificCause().getMessage() : dae.getMessage();
            if (msg != null && (msg.contains("UNIQUE") || msg.contains("CONSTRAINT") || msg.contains("NOT NULL"))) {
                log.warn("RPC Constraint Violation: {}", msg);
                return JsonRpcResponse.fail(
                        new JsonRpcError(-32602, "唯一性或数据完整性约束冲突: " + msg, "ERR_VALIDATION_FAILED", null),
                        reqId);
            }
            log.error("RPC DataAccess Error: ", dae);
            return JsonRpcResponse.fail(
                    new JsonRpcError(-32603, "数据库访问异常: " + msg, "ERR_INTERNAL", null),
                    reqId);
        } catch (Exception ex) {
            log.error("RPC Internal Error: ", ex);
            return JsonRpcResponse.fail(
                    new JsonRpcError(-32603, "系统内部异常: " + ex.getMessage(), "ERR_INTERNAL", null),
                    reqId);
        }
    }

    private Object invokeAt(int index, RpcRequestContext ctx) {
        if (index >= handlerChain.size()) {
            throw DomainException.methodNotFound("责任链终点未处理请求");
        }
        return handlerChain.get(index).handle(ctx, nextCtx -> invokeAt(index + 1, nextCtx));
    }
}
