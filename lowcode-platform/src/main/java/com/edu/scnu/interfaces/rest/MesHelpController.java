package com.edu.scnu.interfaces.rest;

import com.edu.scnu.application.IamAppService;
import com.edu.scnu.application.MesHelpService;
import com.edu.scnu.application.UiSchemaAppService;
import com.edu.scnu.domain.iam.UserAccount;
import com.edu.scnu.domain.shared.MetaContext;
import com.edu.scnu.infrastructure.assistant.DeepSeekHelpClient;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class MesHelpController {
    private final IamAppService iam;
    private final UiSchemaAppService ui;
    private final MesHelpService help;

    public MesHelpController(IamAppService iam, UiSchemaAppService ui, MesHelpService help) {
        this.iam = iam;
        this.ui = ui;
        this.help = help;
    }

    @PostMapping(path = "/api/assistant/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ask(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @RequestBody AskRequest request) {
        UserAccount user = iam.authenticate(authorization).orElse(null);
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录后再使用助手。");
        }
        String question = request.question() != null ? request.question().strip() : "";
        if (question.isEmpty() || question.length() > 500) {
            return error(HttpStatus.BAD_REQUEST, "请输入 1 至 500 字的问题。");
        }
        List<String> previous = request.previousTopics() != null ? request.previousTopics() : List.of();
        String current = request.currentPath() != null ? request.currentPath() : "";
        if (previous.size() > 3 || current.length() > 240) {
            return error(HttpStatus.BAD_REQUEST, "页面上下文无效，请刷新后重试。");
        }
        if (!help.isConfigured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "MES 助手尚未配置 DeepSeek，请联系系统管理员。");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(help.ask(question, current, previous, authorizedPages(user)));
        } catch (DeepSeekHelpClient.Unavailable e) {
            return error(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    private Map<String, String> authorizedPages(UserAccount user) {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("/runtime", "全部授权应用");
        Map<String, Object> profile = iam.buildProfileData(user);
        if (Boolean.TRUE.equals(profile.get("canAccessStudio"))) {
            pages.put("/studio/overview", "工作台概览");
            pages.put("/studio/apps", "应用管理");
            pages.put("/studio/models", "表单与数据表");
            pages.put("/studio/views_menus", "页面与菜单编排");
            pages.put("/studio/iam", "租户与应用授权");
            pages.put("/studio/dict", "常用选项字典");
            pages.put("/studio/code_rule", "自动编号规则");
        } else if (Boolean.TRUE.equals(profile.get("canManageTenants"))) {
            pages.put("/tenant-admin", "分站与应用授权");
        }
        try (MetaContext context = new MetaContext(user.getTenantId(), user.getId(), null)) {
            context.setRoleCodes(user.getRoleCodes());
            addRuntimePages(ui.loadMenu(), pages);
        }
        return pages;
    }

    private void addRuntimePages(List<?> nodes, Map<String, String> pages) {
        for (Object entry : nodes) {
            if (!(entry instanceof Map<?, ?> node)) continue;
            if ("mes_manufacturing".equals(node.get("appName"))
                    && node.get("routePath") instanceof String path
                    && node.get("displayName") instanceof String label) {
                pages.put(path, label);
            }
            if (node.get("children") instanceof List<?> children) {
                addRuntimePages(children, pages);
            }
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", message));
    }

    public record AskRequest(String question, String currentPath, List<String> previousTopics) {}
}
