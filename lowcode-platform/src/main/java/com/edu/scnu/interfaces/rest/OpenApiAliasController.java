package com.edu.scnu.interfaces.rest;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 兼容常用 OpenAPI 规范路径，重定向/转发至标准 Springdoc OpenAPI 3 端点。
 */
@Hidden
@Controller
public class OpenApiAliasController {

    @GetMapping("/api/openapi.json")
    public String openApiAlias() {
        return "forward:/v3/api-docs";
    }
}
