package com.edu.scnu.domain.runtime;

import java.util.Map;

/**
 * 统一模型服务执行契约（对标 MethodMeta.invoke / @MethodService）。
 */
@FunctionalInterface
public interface ModelServiceHandler {
    Object execute(RecordSet rs, Map<String, Object> args);
}
