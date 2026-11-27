package com.edu.scnu.domain.shared;

import java.util.Collections;
import java.util.List;

/**
 * 领域异常体系，携带标准 JSON-RPC 2.0 业务错误码与结构化明细，
 * 用于在接口网关层映射为确定性 JsonRpcError 响应。
 */
public class DomainException extends RuntimeException {
    private final int rpcCode;
    private final String errorKey;
    private final List<String> details;

    public DomainException(int rpcCode, String errorKey, String message) {
        this(rpcCode, errorKey, message, Collections.emptyList());
    }

    public DomainException(int rpcCode, String errorKey, String message, List<String> details) {
        super(message);
        this.rpcCode = rpcCode;
        this.errorKey = errorKey;
        this.details = details == null ? Collections.emptyList() : details;
    }

    public static DomainException methodNotFound(String message) {
        return new DomainException(-32601, "ERR_METHOD_NOT_FOUND", message);
    }

    public static DomainException invalidParams(String message) {
        return new DomainException(-32602, "ERR_INVALID_PARAMS", message);
    }

    public static DomainException invalidParams(String message, List<String> details) {
        return new DomainException(-32602, "ERR_VALIDATION_FAILED", message, details);
    }

    public static DomainException validationFailed(String message) {
        return new DomainException(-32602, "ERR_VALIDATION_FAILED", message, Collections.singletonList(message));
    }

    public static DomainException permissionDenied(String message) {
        return new DomainException(-32001, "ERR_PERMISSION_DENIED", message);
    }

    public static DomainException impactBlocked(String message, List<String> details) {
        return new DomainException(-32002, "ERR_IMPACT_BREAKING_CHANGE", message, details);
    }

    public static DomainException stateGuardFailed(String message) {
        return new DomainException(-32003, "ERR_STATE_GUARD_FAILED", message);
    }

    public static DomainException dualReviewForbidden(String message) {
        return new DomainException(-32004, "ERR_DUAL_REVIEW_FORBIDDEN", message);
    }

    public static DomainException concurrentConflict(String message) {
        return new DomainException(-32005, "ERR_CONCURRENT_CONFLICT", message);
    }

    public int getRpcCode() {
        return rpcCode;
    }

    public String getErrorKey() {
        return errorKey;
    }

    public List<String> getDetails() {
        return details;
    }
}
