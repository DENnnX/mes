package com.edu.scnu;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 低代码平台全链路集成测试：
 * 验证通用元模型引擎、JSON-RPC 2.0 协议、Filter DSL、ManyToOne/OneToMany 级联、
 * Groovy 脚本扩展与事务回滚、声明式状态机、版本影响检查/兼容退役/回滚、RBAC 与受控安全清理治理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LowCodePlatformIntegrationTests {

    private static Path tempDbFile;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws IOException {
        Path tempDir = Files.createTempDirectory("lowcode-test-");
        tempDbFile = tempDir.resolve("lowcode-test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDbFile.toAbsolutePath());
    }

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private ObjectMapper objectMapper;

    private static String devToken;
    private static String adminToken;
    private static String userToken;
    private static String deptId;
    private static String taskV1Id;
    private static String taskV1VerId;
    private static String taskV2VerId;
    private static Integer taskCurrentVersion;
    private static String uploadedFileUrl;

    private Map<String, Object> rpcCall(String token, String model, String service, Map<String, Object> args) {
        return rpcCall("/rbac_tenant_root/rpc/service", token, model, service, args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcCall(String endpoint, String token, String model, String service, Map<String, Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("app", "test_suite");
        params.put("tag", "master");
        params.put("model", model);
        params.put("service", service);
        params.put("context", Map.of("lang", "zh-CN"));
        params.put("args", args != null ? args : Collections.emptyMap());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", UUID.randomUUID().toString());
        payload.put("method", "service");
        payload.put("params", params);

        return (Map<String, Object>) restClient.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .body(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private <T> T requireResult(Map<String, Object> rpcResp) {
        assertThat(rpcResp.get("error"))
                .withFailMessage("期望 RPC 成功，但收到错误: " + rpcResp.get("error"))
                .isNull();
        return (T) rpcResp.get("result");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireError(Map<String, Object> rpcResp, int expectedCode) {
        Map<String, Object> err = (Map<String, Object>) rpcResp.get("error");
        assertThat(err)
                .withFailMessage("期望 RPC 失败错误码 " + expectedCode + "，实际返回: " + rpcResp)
                .isNotNull();
        assertThat(((Number) err.get("code")).intValue()).isEqualTo(expectedCode);
        return err;
    }

    @Test
    void testFrontendEntryIsNeverCached() {
        for (String entryPath : List.of("/", "/index.html")) {
            restClient.get().uri(entryPath)
                    .headers(headers -> headers.setIfModifiedSince(System.currentTimeMillis() + 60_000))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                    .expectBody(String.class).value(html -> assertThat(html).contains("id=\"app\""));
        }
    }

    @Test
    @Order(1)
    void testHealthAuthAndPureBootstrapMenus() {
        restClient.get().uri("/api/health").exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).value(body -> assertThat(body).containsEntry("status", "UP"));

        // 未携带 Token 访问受保护端点 -> -32001
        Map<String, Object> unauth = rpcCall(null, "ui_menu", "loadMenu", null);
        requireError(unauth, -32001);
        List<?> publicApps = requireResult(rpcCall("/rpc/open/service", null, "sys_portal", "publicApps", null));
        assertThat(publicApps).isEmpty();
        requireError(rpcCall("/rpc/open/service", null, "rbac_user", "profile", null), -32001);

        // 验证初始默认账号仅保留 admin 与 user，不初始化 developer / auditor
        requireError(rpcCall(null, "rbac_user", "login", Map.of("username", "developer", "password", "123456")), -32001);
        requireError(rpcCall(null, "rbac_user", "login", Map.of("username", "auditor", "password", "123456")), -32001);

        // 登录统一系统管理员 admin（含全部管理、建模、发布与安全清理权限）与示例 user 账号
        Map<String, Object> adminLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "admin", "password", "123456")));
        adminToken = (String) adminLogin.get("token");
        devToken = adminToken;
        assertThat(adminToken).isNotBlank();

        Map<String, Object> userLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "user", "password", "123456")));
        userToken = (String) userLogin.get("token");
        requireError(rpcCall("/rpc/open/service", userToken, "rbac_user", "profile", null), -32001);

        Map<String, Object> temporaryLogin = requireResult(rpcCall("/rpc/open/service", null, "rbac_user", "login",
                Map.of("username", "user", "password", "123456")));
        String temporaryToken = (String) temporaryLogin.get("token");
        requireResult(rpcCall(temporaryToken, "rbac_user", "logout", null));
        requireError(rpcCall(temporaryToken, "rbac_user", "profile", null), -32001);

        // 确认纯平台初始化完成后无任何硬编码业务示例菜单
        List<Map<String, Object>> menus = requireResult(rpcCall(devToken, "ui_menu", "loadMenu", null));
        assertThat(menus).isEmpty();

        // 确认自举内核模型已存在且标记为 kernelModel=true
        List<Map<String, Object>> models = requireResult(rpcCall(devToken, "meta_model", "listModels", null));
        assertThat(models).extracting(m -> m.get("name"))
                .contains("meta_app", "meta_model", "meta_publish_record", "meta_purge_audit");

        // 确认内置示例数据字典已开箱初始化
        List<Map<String, Object>> defaultDicts = requireResult(rpcCall(adminToken, "meta_dict", "listTypes", null));
        assertThat(defaultDicts).extracting(d -> d.get("typeCode"))
                .contains("SYS_URGENCY", "SYS_APPROVAL_STATUS", "SYS_QUALITY_RESULT", "SYS_ENABLE_STATUS");
        List<Map<String, Object>> urgencyItems = requireResult(rpcCall(userToken, "meta_dict", "itemsByType",
                Map.of("typeCode", "SYS_URGENCY")));
        assertThat(urgencyItems).extracting(i -> i.get("itemLabel")).containsExactly("普通", "加急", "特急");
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void testImportAppPackageAndDynamicUiMetadata() throws Exception {
        // 构造纯通用领域测试应用包（部门 test_dept + 任务 test_task + 明细项 test_task_item）
        String pkgJson = """
        {
          "app": {
            "name": "generic_qa_app",
            "displayName": "通用引擎测试应用",
            "category": "qa",
            "version": "1.0.0",
            "description": "自动化集成测试动态定义模型包"
          },
          "models": [
            {
              "name": "test_dept",
              "displayName": "测试部门",
              "appName": "generic_qa_app",
              "versionNo": "1.0.0",
              "properties": [
                { "name": "code", "displayName": "部门编码", "dataType": "String", "required": true, "unique": true },
                { "name": "title", "displayName": "部门名称", "dataType": "String", "required": true, "displayForModel": true }
              ]
            },
            {
              "name": "test_task_item",
              "displayName": "任务明细行",
              "appName": "generic_qa_app",
              "versionNo": "1.0.0",
              "properties": [
                { "name": "task_id", "displayName": "所属任务", "dataType": "ManyToOne", "relateModel": "test_task", "onDelete": "CASCADE" },
                { "name": "item_name", "displayName": "检查项", "dataType": "String", "required": true, "displayForModel": true },
                { "name": "score", "displayName": "分值", "dataType": "Integer", "defaultValue": 10, "minValue": 0, "maxValue": 100 }
              ]
            },
            {
              "name": "test_task",
              "displayName": "通用动态任务",
              "appName": "generic_qa_app",
              "versionNo": "1.0.0",
              "properties": [
                { "name": "code", "displayName": "任务编码", "dataType": "String", "required": true, "unique": true },
                { "name": "title", "displayName": "任务标题", "dataType": "String", "required": true, "displayForModel": true },
                { "name": "dept_id", "displayName": "责任部门", "dataType": "ManyToOne", "relateModel": "test_dept" },
                { "name": "priority", "displayName": "优先级", "dataType": "Integer", "defaultValue": 1, "minValue": 1, "maxValue": 5 },
                {
                  "name": "status", "displayName": "状态", "dataType": "Selection", "defaultValue": "DRAFT",
                  "values": [
                    { "value": "DRAFT", "label": "草稿", "color": "info" },
                    { "value": "RUNNING", "label": "执行中", "color": "warning" },
                    { "value": "DONE", "label": "已完成", "color": "success" }
                  ]
                },
                { "name": "items", "displayName": "子明细", "dataType": "OneToMany", "relateModel": "test_task_item", "inverseName": "task_id" }
              ],
              "services": [
                {
                  "name": "startTask",
                  "displayName": "启动任务",
                  "type": "STATE_TRANSITION",
                  "auth": "action",
                  "transitionRule": {
                    "statusField": "status",
                    "fromStates": ["DRAFT"],
                    "toState": "RUNNING",
                    "errorMessage": "仅草稿状态任务可启动"
                  }
                },
                {
                  "name": "bonusCalc",
                  "displayName": "脚本计算与更新",
                  "type": "GROOVY_SCRIPT",
                  "auth": "action",
                  "scriptContent": "def row = rs.call('find', [id: args.id]);\\nint nextP = ((row.priority ?: 1) as int) + (args.delta ?: 1);\\nif (args.failAfterUpdate) { rs.call('update', [id: args.id, values: [priority: 4]]); throw com.edu.scnu.domain.shared.DomainException.invalidParams('模拟脚本异常触发回滚'); };\\nreturn rs.call('update', [id: args.id, values: [priority: nextP, status: 'DONE']]);"
                }
              ]
            }
          ]
        }
        """;

        Map<String, Object> pkg = objectMapper.readValue(pkgJson, Map.class);
        Map<String, Object> imported = requireResult(rpcCall(devToken, "meta_app", "importPackage",
                Map.of("package", pkg, "autoPublish", true)));
        assertThat((List<?>) imported.get("importedModels")).hasSize(3);

        // 动态加载菜单，验证按 app 生成了根菜单和 3 个子菜单与动态路由
        List<Map<String, Object>> menus = requireResult(rpcCall(userToken, "ui_menu", "loadMenu", null));
        assertThat(menus).hasSize(1);
        List<Map<String, Object>> children = (List<Map<String, Object>>) menus.get(0).get("children");
        assertThat(children).extracting(m -> m.get("routePath"))
                .contains("/menu/test_dept_menu", "/menu/test_task_menu", "/menu/test_task_item_menu");

        // 动态加载 test_task 视图三件套契约，验证包含 grid/search/form、OneToMany tab 与状态机按钮
        Map<String, Object> viewRes = requireResult(rpcCall(userToken, "ui_view", "loadView",
                Map.of("model", "test_task")));
        Map<String, Object> views = (Map<String, Object>) viewRes.get("views");
        assertThat(views).containsKeys("grid", "search", "form");
        Map<String, Object> formBody = (Map<String, Object>) ((Map<String, Object>) views.get("form")).get("body");
        assertThat((List<?>) formBody.get("tabs")).hasSize(1);

        // 记录当前活动版本 ID
        Map<String, Object> detail = requireResult(rpcCall(devToken, "meta_model", "detail",
                Map.of("modelName", "test_task")));
        taskV1VerId = (String) ((Map<String, Object>) detail.get("model")).get("activeVersionId");
        assertThat(taskV1VerId).isNotBlank();
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void testDynamicCrudFilterDslOneToManyAndScripts() {
        // 1. 创建部门
        Map<String, Object> dept = requireResult(rpcCall(userToken, "test_dept", "create",
                Map.of("values", Map.of("code", "D01", "title", "基础研发部"))));
        deptId = (String) dept.get("id");

        // 2. 输入校验拦截（minValue=1，传入 9 应抛 -32602）
        Map<String, Object> badCreate = rpcCall(userToken, "test_task", "create",
                Map.of("values", Map.of("code", "T-BAD", "title", "越界任务", "priority", 9)));
        requireError(badCreate, -32602);

        // 3. 连同 OneToMany 子明细一起创建任务
        Map<String, Object> createdTask = requireResult(rpcCall(userToken, "test_task", "create",
                Map.of("values", Map.of(
                        "code", "T-001",
                        "title", "元数据内核回归",
                        "dept_id", deptId,
                        "priority", 2,
                        "items", List.of(
                                List.of(0, 0, Map.of("item_name", "检查项A", "score", 80)),
                                List.of(0, 0, Map.of("item_name", "检查项B", "score", 95))
                        )
                ))));
        taskV1Id = (String) createdTask.get("id");
        taskCurrentVersion = ((Number) createdTask.get("version")).intValue();
        assertThat((String) createdTask.get("status")).isEqualTo("DRAFT");
        assertThat((List<?>) createdTask.get("items")).hasSize(2);
        // 验证 ManyToOne 自动展开 displayName
        Map<String, Object> m2oDept = (Map<String, Object>) createdTask.get("dept_id");
        assertThat(m2oDept).containsEntry("id", deptId).containsEntry("displayName", "基础研发部");

        // 4. Filter DSL + lookup 查询
        Map<String, Object> searchRes = requireResult(rpcCall(userToken, "test_task", "search",
                Map.of("filter", List.of("AND", List.of("title", "like", "内核"), List.of("status", "=", "DRAFT")),
                       "useDisplayForModel", true)));
        assertThat(((Number) searchRes.get("total")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> lookupOpts = requireResult(rpcCall(userToken, "test_task", "lookup",
                Map.of("field", "dept_id", "keyword", "研发")));
        assertThat(lookupOpts).hasSize(1);
        assertThat(lookupOpts.get(0)).containsEntry("label", "基础研发部");

        // 5. 乐观锁冲突检测 (-32005)
        Map<String, Object> conflictRes = rpcCall(userToken, "test_task", "update",
                Map.of("id", taskV1Id, "version", 999, "values", Map.of("title", "并发覆盖")));
        requireError(conflictRes, -32005);

        // 6. 声明式状态机迁移：DRAFT -> RUNNING
        Map<String, Object> started = requireResult(rpcCall(userToken, "test_task", "startTask",
                Map.of("id", taskV1Id)));
        assertThat(started.get("status")).isEqualTo("RUNNING");
        taskCurrentVersion = ((Number) started.get("version")).intValue();

        // 再次执行 startTask 应被状态守卫拦截 (-32003)
        Map<String, Object> badTransition = rpcCall(userToken, "test_task", "startTask",
                Map.of("id", taskV1Id));
        requireError(badTransition, -32003);

        // 7. Groovy 脚本抛出异常时数据库事务回滚（priority 不应变成 4）
        Map<String, Object> txFail = rpcCall(userToken, "test_task", "bonusCalc",
                Map.of("id", taskV1Id, "failAfterUpdate", true));
        requireError(txFail, -32602);
        Map<String, Object> afterFail = requireResult(rpcCall(userToken, "test_task", "find",
                Map.of("id", taskV1Id)));
        assertThat(((Number) afterFail.get("priority")).intValue()).isEqualTo(2);

        // 正常执行 Groovy 脚本并完成任务：priority 2 + 1 = 3, status = DONE
        Map<String, Object> calcOk = requireResult(rpcCall(userToken, "test_task", "bonusCalc",
                Map.of("id", taskV1Id, "delta", 1)));
        assertThat(((Number) calcOk.get("priority")).intValue()).isEqualTo(3);
        assertThat(calcOk.get("status")).isEqualTo("DONE");
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void testImpactAnalysisSafeEvolutionAndVersionRollback() {
        // 加载当前模型快照准备演进到 2.0.0
        Map<String, Object> detail = requireResult(rpcCall(devToken, "meta_model", "detail",
                Map.of("modelName", "test_task")));
        List<Map<String, Object>> versions = (List<Map<String, Object>>) detail.get("versions");
        Map<String, Object> v1Snap = new LinkedHashMap<>((Map<String, Object>) versions.get(0).get("snapshot"));

        // RBAC 检查：普通用户不允许调用 saveDraft / publish (-32001)
        Map<String, Object> userForbidden = rpcCall(userToken, "meta_model", "saveDraft",
                Map.of("snapshot", v1Snap));
        requireError(userForbidden, -32001);

        // 场景 A：尝试直接物理删除视图正在引用的 title 字段 -> checkImpact 必须阻断
        Map<String, Object> breakingSnap = new LinkedHashMap<>(v1Snap);
        breakingSnap.put("versionNo", "2.0.0");
        List<Map<String, Object>> propsBad = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) v1Snap.get("properties")) {
            if (!"title".equals(p.get("name"))) {
                propsBad.add(new LinkedHashMap<>(p));
            }
        }
        breakingSnap.put("properties", propsBad);

        Map<String, Object> badDraft = requireResult(rpcCall(devToken, "meta_model", "saveDraft",
                Map.of("snapshot", breakingSnap)));
        Map<String, Object> blockedReport = requireResult(rpcCall(devToken, "meta_model", "checkImpact",
                Map.of("modelName", "test_task", "versionId", badDraft.get("id"))));
        assertThat(blockedReport.get("passed")).isEqualTo(false);
        assertThat((List<String>) blockedReport.get("blockingReasons")).isNotEmpty();

        // 验证未通过 checkImpact 的草稿禁止直接 publish (-32003)
        requireError(rpcCall(devToken, "meta_model", "publish",
                Map.of("modelName", "test_task", "versionId", badDraft.get("id"))), -32003);

        // 场景 B：改用兼容演进：保留 title、将 priority 设为兼容退役(display=false)、并安全新增 remark 字段
        List<Map<String, Object>> propsSafe = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) v1Snap.get("properties")) {
            Map<String, Object> copy = new LinkedHashMap<>(p);
            if ("priority".equals(copy.get("name"))) {
                copy.put("display", false);
            }
            propsSafe.add(copy);
        }
        propsSafe.add(Map.of("name", "remark", "displayName", "备注说明", "dataType", "String"));
        breakingSnap.put("properties", propsSafe);

        Map<String, Object> safeDraft = requireResult(rpcCall(devToken, "meta_model", "saveDraft",
                Map.of("snapshot", breakingSnap)));
        taskV2VerId = (String) safeDraft.get("id");
        Map<String, Object> passReport = requireResult(rpcCall(devToken, "meta_model", "checkImpact",
                Map.of("modelName", "test_task", "versionId", taskV2VerId)));
        assertThat(passReport.get("passed")).isEqualTo(true);
        assertThat((List<String>) passReport.get("addedFields")).contains("remark");
        assertThat((List<String>) passReport.get("deprecatedFields")).contains("priority");

        // 使用 validationHash 显式发布 V2.0.0（增量 ALTER TABLE ADD COLUMN remark）
        Map<String, Object> pubRes = requireResult(rpcCall(devToken, "meta_model", "publish",
                Map.of("modelName", "test_task", "versionId", taskV2VerId,
                       "validationHash", passReport.get("validationHash"))));
        assertThat(pubRes.get("versionNo")).isEqualTo("2.0.0");

        // 在 V2 下新建记录应包含 remark 且 bound_version_id = test_task@2.0.0
        Map<String, Object> t2 = requireResult(rpcCall(userToken, "test_task", "create",
                Map.of("values", Map.of("code", "T-002", "title", "V2 新版任务", "remark", "安全扩展字段"))));
        assertThat(t2.get("bound_version_id")).isEqualTo("test_task@2.0.0");
        assertThat(t2.get("remark")).isEqualTo("安全扩展字段");

        // 历史 V1 记录仍保留原 bound_version_id = test_task@1.0.0
        Map<String, Object> t1 = requireResult(rpcCall(userToken, "test_task", "find", Map.of("id", taskV1Id)));
        assertThat(t1.get("bound_version_id")).isEqualTo("test_task@1.0.0");

        // 回滚至 V1.0.0 并验证所有历史记录无损保留
        Map<String, Object> rbRes = requireResult(rpcCall(devToken, "meta_model", "rollback",
                Map.of("modelName", "test_task", "targetVersionId", taskV1VerId)));
        assertThat(rbRes.get("rolledBackToVersion")).isEqualTo("1.0.0");

        Map<String, Object> afterRbSearch = requireResult(rpcCall(userToken, "test_task", "search",
                Map.of("filter", Collections.emptyList())));
        assertThat(((Number) afterRbSearch.get("total")).longValue()).isEqualTo(2L);
    }

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void testOfflineExportAndRestoreGovernance() {
        // 1. 下线 test_task 模型 -> 动态菜单自动隐藏 + 普通运行时 RPC 被阻断 (-32003)
        requireResult(rpcCall(adminToken, "meta_model", "offline", Map.of("modelName", "test_task")));
        requireError(rpcCall(userToken, "test_task", "search", Map.of("filter", Collections.emptyList())), -32003);

        List<Map<String, Object>> menusWhileOffline = requireResult(rpcCall(userToken, "ui_menu", "loadMenu", null));
        List<Map<String, Object>> children = (List<Map<String, Object>>) menusWhileOffline.get(0).get("children");
        assertThat(children).extracting(m -> m.get("model")).doesNotContain("test_task");

        // 2. 普通用户无权恢复模型 (-32001)，管理员可在数据表管理中导出备份数据
        requireError(rpcCall(userToken, "meta_model", "online", Map.of("modelName", "test_task")), -32001);
        Map<String, Object> exp = requireResult(rpcCall(adminToken, "meta_model", "exportData",
                Map.of("modelName", "test_task")));
        assertThat((String) exp.get("exportTicket")).isNotBlank();
        assertThat(((Number) exp.get("recordCount")).intValue()).isEqualTo(2);
        assertThat((List<Map<String, Object>>) exp.get("records")).hasSize(2);

        // 3. 清理审批流程已删除，applyPurge / confirmPurge 不再暴露
        requireError(rpcCall(adminToken, "meta_model", "applyPurge",
                Map.of("modelName", "test_task", "exportTicket", exp.get("exportTicket"))), -32601);

        // 4. 从停用状态恢复上线 -> 菜单重新出现，业务数据完好可读
        requireResult(rpcCall(adminToken, "meta_model", "online", Map.of("modelName", "test_task")));
        Map<String, Object> restoredRows = requireResult(rpcCall(userToken, "test_task", "search", Map.of("filter", Collections.emptyList())));
        assertThat(((Number) restoredRows.get("total")).intValue()).isEqualTo(2);
        List<Map<String, Object>> menusRestored = requireResult(rpcCall(userToken, "ui_menu", "loadMenu", null));
        List<Map<String, Object>> childrenRestored = (List<Map<String, Object>>) menusRestored.get(0).get("children");
        assertThat(childrenRestored).extracting(m -> m.get("model")).contains("test_task");
    }

    @Test
    @Order(6)
    @SuppressWarnings("unchecked")
    void testDictCodeRulePolishFilterValidationComputeRelatedAndFileUpload() throws Exception {
        // 1. 数据字典与通用编码规则创建
        requireResult(rpcCall(devToken, "meta_dict", "saveType",
                Map.of("values", Map.of("typeCode", "QA_PRIORITY", "name", "任务紧急度"))));
        requireResult(rpcCall(devToken, "meta_dict", "saveItem",
                Map.of("values", Map.of("typeCode", "QA_PRIORITY", "itemValue", "HIGH", "itemLabel", "紧急", "color", "danger", "sequence", 1))));
        requireResult(rpcCall(devToken, "meta_dict", "saveItem",
                Map.of("values", Map.of("typeCode", "QA_PRIORITY", "itemValue", "LOW", "itemLabel", "普通", "color", "info", "sequence", 2))));

        requireResult(rpcCall(devToken, "meta_code_rule", "saveRule",
                Map.of("values", Map.of("code", "QA_DOC", "name", "测试单号", "prefix", "QAD-", "dateFormat", "yyyyMMdd", "seqLength", 4))));
        Map<String, Object> previewCode = requireResult(rpcCall(devToken, "meta_code_rule", "generateCode", Map.of("ruleCode", "QA_DOC")));
        assertThat((String) previewCode.get("generatedCode")).startsWith("QAD-");

        // 2. 上传真实附件到 FileController
        org.springframework.util.MultiValueMap<String, Object> multipart = new org.springframework.util.LinkedMultiValueMap<>();
        byte[] sampleBytes = "lowcode-attachment-payload-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        multipart.add("file", new org.springframework.core.io.ByteArrayResource(sampleBytes) {
            @Override
            public String getFilename() { return "spec-sheet.txt"; }
        });
        Map<String, Object> fileMeta = restClient.post().uri("/api/files/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> headers.setBearerAuth(userToken))
                .body(multipart)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(fileMeta).containsEntry("fileName", "spec-sheet.txt");
        uploadedFileUrl = (String) fileMeta.get("downloadUrl");
        restClient.get().uri(uploadedFileUrl).exchange().expectStatus().isUnauthorized();
        restClient.get().uri(uploadedFileUrl)
                .headers(headers -> headers.setBearerAuth(userToken))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION,
                        value -> assertThat(ContentDisposition.parse(value).getType()).isEqualTo("attachment"))
                .expectBody(byte[].class).isEqualTo(sampleBytes);

        // 3. 发布包含 Dict(多选), CodeRule, related, computeScript, @Validate, selectionMethod, validate钩子与复合索引的模型
        String pkgJson = """
        {
          "app": { "name": "foundation_qa_app", "displayName": "基础能力测试应用", "category": "qa", "version": "1.0.0" },
          "models": [
            {
              "name": "qa_ticket",
              "displayName": "增强特性工单",
              "appName": "foundation_qa_app",
              "versionNo": "1.0.0",
              "indexes": [
                { "name": "uk_dept_title", "columnList": ["dept_id", "title"], "unique": true }
              ],
              "properties": [
                { "name": "code", "displayName": "自动单号", "dataType": "String", "codeRule": "QA_DOC", "unique": true },
                { "name": "title", "displayName": "工单标题", "dataType": "String", "required": true, "minLength": 3, "maxLength": 40, "displayForModel": true },
                { "name": "email", "displayName": "联系邮箱", "dataType": "String", "email": true },
                { "name": "dept_id", "displayName": "关联部门", "dataType": "ManyToOne", "relateModel": "test_dept" },
                { "name": "dept_code", "displayName": "部门编码(related)", "dataType": "String", "related": "dept_id.code", "store": false },
                { "name": "priority_tags", "displayName": "紧急度多选", "dataType": "Dict", "dictType": "QA_PRIORITY", "multiple": true, "defaultMethod": "return ['LOW']" },
                { "name": "dyn_choice", "displayName": "动态选项", "dataType": "Selection", "selectionMethod": "fetchDynamicChoices" },
                { "name": "qty", "displayName": "数量", "dataType": "Integer", "defaultValue": 2 },
                { "name": "price", "displayName": "单价", "dataType": "Double", "defaultValue": 10.5 },
                { "name": "total_amt", "displayName": "金额小计", "dataType": "Double", "computeScript": "(row.qty ?: 0) * (row.price ?: 0)", "store": false },
                { "name": "attach", "displayName": "技术附件", "dataType": "File" }
              ],
              "services": [
                {
                  "name": "fetchDynamicChoices",
                  "displayName": "加载动态选项",
                  "type": "GROOVY_SCRIPT",
                  "auth": "read",
                  "scriptContent": "return [[value:'OPT_A',label:'动态选项A'],[value:'OPT_B',label:'动态选项B']];"
                },
                {
                  "name": "validate",
                  "displayName": "保存前钩子校验",
                  "type": "GROOVY_SCRIPT",
                  "auth": "create",
                  "scriptContent": "if ('FORBIDDEN_TITLE' == args.values?.title) { throw com.edu.scnu.domain.shared.DomainException.validationFailed('钩子拦截非法标题'); }"
                }
              ]
            }
          ]
        }
        """;
        requireResult(rpcCall(devToken, "meta_app", "importPackage",
                Map.of("package", objectMapper.readValue(pkgJson, Map.class), "autoPublish", true)));

        // 验证 addMissingDefaultValues 与 ui_view.loadView 字典/动态选项注入
        Map<String, Object> defaults = requireResult(rpcCall(userToken, "qa_ticket", "addMissingDefaultValues", Map.of()));
        assertThat(defaults.get("priority_tags")).isEqualTo(List.of("LOW"));
        Map<String, Object> viewRes = requireResult(rpcCall(userToken, "ui_view", "loadView", Map.of("model", "qa_ticket")));
        assertThat((Map<String, Object>) viewRes.get("dictOptions")).containsKey("priority_tags");
        assertThat((Map<String, Object>) viewRes.get("dynamicOptions")).containsKey("dyn_choice");

        // 验证 @Validate 与 validate 钩子拦截 (-32602)
        requireError(rpcCall(userToken, "qa_ticket", "create", Map.of("values", Map.of("title", "AB", "email", "ok@sie.com"))), -32602);
        requireError(rpcCall(userToken, "qa_ticket", "create", Map.of("values", Map.of("title", "合法长度", "email", "not-an-email"))), -32602);
        requireError(rpcCall(userToken, "qa_ticket", "create", Map.of("values", Map.of("title", "FORBIDDEN_TITLE", "email", "ok@sie.com"))), -32602);

        // 验证成功创建（不传 code 时自动按 QA_DOC 生成，且计算字段 total_amt 与 related dept_code 正确返回）
        Map<String, Object> ticket = requireResult(rpcCall(userToken, "qa_ticket", "create", Map.of("values", Map.of(
                "title", "工单Alpha",
                "email", "qa@sie.com",
                "dept_id", deptId,
                "priority_tags", List.of("HIGH", "LOW"),
                "dyn_choice", "OPT_A",
                "qty", 3,
                "price", 15.5,
                "attach", fileMeta
        ))));
        assertThat((String) ticket.get("code")).matches("QAD-\\d{8}0002");
        assertThat(ticket.get("dept_code")).isEqualTo("D01");
        assertThat(((Number) ticket.get("total_amt")).doubleValue()).isEqualTo(46.5);

        // 复合唯一索引冲突测试 (dept_id + title)
        requireError(rpcCall(userToken, "qa_ticket", "create", Map.of("values", Map.of("title", "工单Alpha", "dept_id", deptId))), -32602);

        // 波兰前缀 Filter DSL 与 useDisplayForModel 字典翻译验证
        Map<String, Object> polishRes = requireResult(rpcCall(userToken, "qa_ticket", "search", Map.of(
                "filter", List.of("&", List.of("qty", ">", 1), "|", List.of("title", "=", "工单Alpha"), List.of("title", "=", "NONE")),
                "useDisplayForModel", true
        )));
        assertThat(((Number) polishRes.get("total")).longValue()).isEqualTo(1L);
        Map<String, Object> hit = ((List<Map<String, Object>>) polishRes.get("records")).get(0);
        List<Map<String, Object>> hydratedTags = (List<Map<String, Object>>) hit.get("priority_tags");
        assertThat(hydratedTags).extracting(t -> t.get("label")).containsExactly("紧急", "普通");

        // exists 存在性检查
        assertThat((Boolean) requireResult(rpcCall(userToken, "qa_ticket", "exists",
                Map.of("filter", List.of(List.of("title", "=", "工单Alpha")))))).isTrue();
    }

    @Test
    @Order(7)
    @SuppressWarnings("unchecked")
    void testAbstractInheritanceExtendModelCallSuperManyToManyAndUiAction() throws Exception {
        String pkgJson = """
        {
          "app": { "name": "ext_qa_app", "displayName": "继承扩展测试应用", "category": "qa", "version": "1.0.0" },
          "models": [
            {
              "name": "base_doc",
              "displayName": "抽象单据基类",
              "appName": "ext_qa_app",
              "versionNo": "1.0.0",
              "abstractModel": true,
              "properties": [
                { "name": "doc_no", "displayName": "基础单号", "dataType": "String", "required": true }
              ],
              "services": [
                {
                  "name": "greet",
                  "displayName": "多层调用",
                  "type": "GROOVY_SCRIPT",
                  "auth": "action",
                  "scriptContent": "return JsonRpcServiceResponse.message('父类base_doc:' + (args.tag ?: 'ok'));"
                }
              ]
            },
            {
              "name": "child_doc",
              "displayName": "子单据实体",
              "appName": "ext_qa_app",
              "versionNo": "1.0.0",
              "parents": ["base_doc"],
              "properties": [
                { "name": "title", "displayName": "标题", "dataType": "String", "required": true, "displayForModel": true },
                { "name": "dept_tags", "displayName": "多对多部门标签", "dataType": "ManyToMany", "relateModel": "test_dept" }
              ],
              "services": [
                {
                  "name": "greet",
                  "displayName": "多层调用",
                  "type": "GROOVY_SCRIPT",
                  "auth": "action",
                  "scriptContent": "def sup = rs.callSuper('greet', args); return JsonRpcServiceResponse.message((sup?.message ?: sup) + ' -> 子类child_doc');"
                }
              ]
            },
            {
              "name": "ext_child_doc",
              "displayName": "子单据非侵入扩展包",
              "appName": "ext_qa_app",
              "versionNo": "1.0.0",
              "extendModel": "child_doc",
              "properties": [
                { "name": "ext_note", "displayName": "扩展备注", "dataType": "String" }
              ],
              "services": [
                {
                  "name": "greet",
                  "displayName": "多层调用",
                  "type": "GROOVY_SCRIPT",
                  "auth": "action",
                  "scriptContent": "def sup = rs.callSuper('greet', args); return JsonRpcServiceResponse.message((sup?.message ?: sup) + ' -> 扩展ext_child_doc');"
                }
              ]
            }
          ]
        }
        """;
        requireResult(rpcCall(devToken, "meta_app", "importPackage",
                Map.of("package", objectMapper.readValue(pkgJson, Map.class), "autoPublish", true)));

        // 抽象父模型与扩展包不可作为独立 UI 加载
        requireError(rpcCall(userToken, "ui_view", "loadView", Map.of("model", "base_doc")), -32003);

        // 创建子实体：同时传入继承的 doc_no、自身 title、扩展包的 ext_note 以及 ManyToMany [4, deptId]
        Map<String, Object> doc = requireResult(rpcCall(userToken, "child_doc", "create", Map.of("values", Map.of(
                "doc_no", "DOC-100",
                "title", "扩展继承测试单",
                "ext_note", "来自extendModel包",
                "dept_tags", List.of(List.of(4, deptId))
        ))));
        String docId = (String) doc.get("id");
        assertThat(doc.get("doc_no")).isEqualTo("DOC-100");
        assertThat(doc.get("ext_note")).isEqualTo("来自extendModel包");
        assertThat((List<Map<String, Object>>) doc.get("dept_tags"))
                .extracting(m -> m.get("id")).containsExactly(deptId);

        // ManyToMany 解绑 [3, id] 与覆盖 [6, 0, ids]
        Map<String, Object> unlinked = requireResult(rpcCall(userToken, "child_doc", "update",
                Map.of("id", docId, "values", Map.of("dept_tags", List.of(List.of(3, deptId))))));
        assertThat((List<?>) unlinked.get("dept_tags")).isEmpty();
        Map<String, Object> replaced = requireResult(rpcCall(userToken, "child_doc", "update",
                Map.of("id", docId, "values", Map.of("dept_tags", List.of(List.of(6, 0, List.of(deptId)))))));
        assertThat((List<Map<String, Object>>) replaced.get("dept_tags")).hasSize(1);

        // 三层 callSuper 重写链 + JsonRpcServiceResponse  UI 反馈动作验证
        Map<String, Object> greetRes = requireResult(rpcCall(userToken, "child_doc", "greet", Map.of("id", docId, "tag", "OK")));
        assertThat(greetRes.get("_uiAction")).isEqualTo("message");
        assertThat((String) greetRes.get("message")).contains("父类base_doc:OK -> 子类child_doc -> 扩展ext_child_doc");
    }

    @Test
    @Order(8)
    @SuppressWarnings("unchecked")
    void testMultiTenantIsolationOrgRolePermissionsAndApiDoc() {
        // 1. 创建租户 tenant_b 并仅授权 foundation_qa_app
        requireResult(rpcCall(adminToken, "rbac_tenant", "saveTenant",
                Map.of("values", Map.of("id", "tenant_b", "code", "tenant_b", "name", "测试隔离租户B"))));
        requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeApps",
                Map.of("tenantId", "tenant_b", "authorizedApps", List.of("foundation_qa_app"))));

        // 2. 在 tenant_b 下创建组织节点、只读角色与读写角色、用户
        Map<String, Object> orgRes = requireResult(rpcCall(devToken, "rbac_org", "saveOrg",
                Map.of("values", Map.of("id", "org_b1", "tenantId", "tenant_b", "name", "租户B研发部", "orgType", "DEPT"))));
        assertThat(orgRes.get("tenantId")).isEqualTo("tenant_b");

        requireResult(rpcCall(adminToken, "rbac_role", "saveRole", Map.of("values", Map.of(
                "code", "ROLE_QA_READONLY",
                "name", "工单只读角色",
                "dataScope", "ALL",
                "allowedServices", List.of("ui_*:*", "qa_ticket:search", "qa_ticket:find", "meta_dict:*")
        ))));

        Map<String, Object> bReadUser = requireResult(rpcCall(adminToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "b_reader",
                "displayName", "租户B只读用户",
                "tenantId", "tenant_b",
                "orgId", "org_b1",
                "password", "init123",
                "roleCodes", List.of("ROLE_QA_READONLY")
        ))));
        requireResult(rpcCall(adminToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "b_writer",
                "displayName", "租户B业务用户",
                "tenantId", "tenant_b",
                "orgId", "org_b1",
                "password", "init123",
                "roleCodes", List.of("ROLE_APP_USER")
        ))));

        // 测试重置密码并新密码登录
        requireResult(rpcCall(adminToken, "rbac_user", "resetPassword",
                Map.of("userId", bReadUser.get("id"), "newPassword", "newpass99")));
        String bReaderToken = (String) ((Map<String, Object>) requireResult(
                rpcCall(null, "rbac_user", "login", Map.of("username", "b_reader", "password", "newpass99")))).get("token");
        String bWriterToken = (String) ((Map<String, Object>) requireResult(
                rpcCall(null, "rbac_user", "login", Map.of("username", "b_writer", "password", "init123")))).get("token");

        // 3. 租户应用授权：b_reader 菜单仅含 foundation_qa_app，不含 ext_qa_app / generic_qa_app
        List<Map<String, Object>> bMenus = requireResult(rpcCall(bReaderToken, "ui_menu", "loadMenu", null));
        assertThat(bMenus).extracting(m -> m.get("appName")).containsOnly("foundation_qa_app");
        // 访问未授权应用的模型视图将被拒绝 (-32001)
        requireError(rpcCall(bReaderToken, "ui_view", "loadView", Map.of("model", "child_doc")), -32001);

        // 4. 角色按钮权限裁切：b_reader 加载 qa_ticket 视图，create/delete=false，并尝试 create 被拒绝 (-32001)
        Map<String, Object> qaView = requireResult(rpcCall(bReaderToken, "ui_view", "loadView", Map.of("model", "qa_ticket")));
        Map<String, Object> auth = (Map<String, Object>) qaView.get("auth");
        assertThat(auth).containsEntry("read", true).containsEntry("create", false).containsEntry("delete", false);
        requireError(rpcCall(bReaderToken, "qa_ticket", "create", Map.of("values", Map.of("title", "越权写入"))), -32001);

        // 5. 多租户数据严格隔离：b_writer 新建数据归属 tenant_b，双方互相不可见（且不同租户唯一流水号 0002 不互相冲突）
        requireResult(rpcCall(bWriterToken, "qa_ticket", "create", Map.of("values", Map.of("title", "租户B专属工单"))));
        Map<String, Object> bSecondTicket = requireResult(rpcCall(bWriterToken, "qa_ticket", "create", Map.of("values", Map.of("title", "租户B第二单"))));
        assertThat((String) bSecondTicket.get("code")).endsWith("0002");
        Map<String, Object> bRows = requireResult(rpcCall(bReaderToken, "qa_ticket", "search", Map.of("filter", Collections.emptyList())));
        assertThat(((Number) bRows.get("total")).longValue()).isEqualTo(2L);
        assertThat((List<Map<String, Object>>) bRows.get("records"))
                .extracting(r -> r.get("title")).containsExactlyInAnyOrder("租户B专属工单", "租户B第二单");

        Map<String, Object> rootRows = requireResult(rpcCall(userToken, "qa_ticket", "search", Map.of("filter", Collections.emptyList())));
        assertThat((List<Map<String, Object>>) rootRows.get("records"))
                .extracting(r -> r.get("title")).containsExactly("工单Alpha");

        // 6. Swagger / OpenAPI 3+ 文档与 Apifox 分路径直调验证
        Map<String, Object> openApiSpec = (Map<String, Object>) restClient.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat((String) openApiSpec.get("openapi")).startsWith("3.0.");
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");
        assertThat(paths).containsKeys(
                "/rpc/open/service/rbac_user/login",
                "/{tenant}/rpc/service/child_doc/search",
                "/{tenant}/rpc/service/child_doc/create",
                "/{tenant}/rpc/service/qa_ticket/search",
                "/api/files/upload");
        Map<String, Object> components = (Map<String, Object>) openApiSpec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).containsKeys("JsonRpcSuccessResponse", "JsonRpcErrorObject", "JsonRpcRequestEnvelope", "ChildDocRecord");
        Map<String, Object> childSchema = (Map<String, Object>) schemas.get("ChildDocRecord");
        Map<String, Object> childProps = (Map<String, Object>) childSchema.get("properties");
        assertThat(childProps).containsKeys("doc_no", "title", "ext_note", "dept_tags");
        assertThat((Map<String, Object>) components.get("securitySchemes")).containsKey("bearerAuth");

        // Swagger UI 资源与 Config 正常提供服务
        restClient.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        Map<String, Object> swaggerCfg = (Map<String, Object>) restClient.get().uri("/v3/api-docs/swagger-config")
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat((String) swaggerCfg.get("url")).contains("/v3/api-docs");

        // Apifox 风格直连端点：匿名登录 + 简写 JSON 调用 + 标准 JSON-RPC 2.0 兼容
        Map<String, Object> apifoxLogin = (Map<String, Object>) restClient.post()
                .uri("/rpc/open/service/rbac_user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "b_reader", "password", "newpass99"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        String apifoxToken = (String) ((Map<String, Object>) apifoxLogin.get("result")).get("token");
        assertThat(apifoxToken).isNotBlank();

        Map<String, Object> apifoxSearch = (Map<String, Object>) restClient.post()
                .uri("/tenant_b/rpc/service/qa_ticket/search")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(apifoxToken))
                .body(Map.of("args", Map.of("filter", Collections.emptyList())))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(((Number) ((Map<String, Object>) apifoxSearch.get("result")).get("total")).longValue()).isEqualTo(2L);

        Map<String, Object> apifoxCount = (Map<String, Object>) restClient.post()
                .uri("/rpc/service/qa_ticket/count")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(userToken))
                .body(Collections.emptyMap())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(((Number) apifoxCount.get("result")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> permCatalog = requireResult(rpcCall(devToken, "rbac_role", "permissionCatalog", null));
        assertThat(permCatalog).extracting(g -> g.get("groupKey")).contains("kernel", "builtin_wildcard");

        // 7. 租户登录页面应用授权门禁与 RPC 网关拦截验证
        // (a) 未授权任何应用的租户无法登录业务工作区
        requireResult(rpcCall(adminToken, "rbac_tenant", "saveTenant",
                Map.of("values", Map.of("id", "tenant_empty", "code", "tenant_empty", "name", "无授权测试租户"))));
        requireResult(rpcCall(adminToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "empty_u", "displayName", "空租户用户", "tenantId", "tenant_empty",
                "password", "123456", "roleCodes", List.of("ROLE_APP_USER")
        ))));
        requireError(rpcCall(null, "rbac_user", "login", Map.of("username", "empty_u", "password", "123456")), -32001);

        // (b) 租户用户登录未授权的应用 ext_qa_app 或管理后台 __studio__ 均被拒绝 (-32001)
        requireError(rpcCall(null, "rbac_user", "login",
                Map.of("username", "b_reader", "password", "newpass99", "appName", "ext_qa_app")), -32001);
        requireError(rpcCall(null, "rbac_user", "login",
                Map.of("username", "b_reader", "password", "newpass99", "appName", "__studio__")), -32001);

        // (c) 登录已授权的应用 foundation_qa_app 成功，且返回仅包含已授权应用列表
        Map<String, Object> bAppLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "b_reader", "password", "newpass99", "appName", "foundation_qa_app")));
        assertThat((Boolean) bAppLogin.get("canAccessStudio")).isFalse();
        assertThat((List<Map<String, Object>>) bAppLogin.get("authorizedApps"))
                .extracting(a -> a.get("name")).containsExactly("foundation_qa_app");

        // (d) 绕过界面直接调用未授权应用下的模型数据 RPC 接口亦被 PermissionRpcHandler 拦截 (-32001)
        requireError(rpcCall(bWriterToken, "child_doc", "search", Map.of("filter", Collections.emptyList())), -32001);

        // (e) 公共登录页查询开放应用列表 sys_portal.publicApps 无需 Token 可访问
        Map<String, Object> openReq = new LinkedHashMap<>();
        openReq.put("jsonrpc", "2.0");
        openReq.put("id", "pub-apps-1");
        openReq.put("method", "sys_portal.publicApps");
        openReq.put("params", Map.of());
        Map<?, ?> openResp = restClient.post().uri("/rpc/open/service")
                .contentType(MediaType.APPLICATION_JSON)
                .body(openReq)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) openResp.get("result"))
                .extracting(a -> a.get("name")).contains("foundation_qa_app", "ext_qa_app");

        // (f) 应用直建 meta_app.save 与用户自助修改密码 rbac_user.changePassword
        Map<String, Object> savedApp = requireResult(rpcCall(devToken, "meta_app", "save",
                Map.of("app", Map.of("name", "custom_studio_app", "displayName", "可视化自建应用", "category", "enterprise", "version", "1.0.0"))));
        assertThat(savedApp.get("displayName")).isEqualTo("可视化自建应用");

        requireError(rpcCall(bWriterToken, "rbac_user", "changePassword",
                Map.of("oldPassword", "wrong", "newPassword", "bPass2026")), -32602);
        requireResult(rpcCall(bWriterToken, "rbac_user", "changePassword",
                Map.of("oldPassword", "init123", "newPassword", "bPass2026")));
        requireResult(rpcCall(null, "rbac_user", "login", Map.of("username", "b_writer", "password", "bPass2026")));

        // (g) 分级租户管理员 (ROLE_TENANT_ADMIN) 严格边界隔离验证
        requireResult(rpcCall(adminToken, "rbac_tenant", "saveTenant",
                Map.of("values", Map.of("id", "hq_corp", "code", "hq_corp", "name", "测试企业总部", "parentTenantId", "rbac_tenant_root"))));
        requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeApps",
                Map.of("tenantId", "hq_corp", "authorizedApps", List.of("foundation_qa_app"))));
        requireResult(rpcCall(adminToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "id", "usr_hq_mgr", "username", "hq_mgr", "displayName", "企业总部主管",
                "tenantId", "hq_corp", "password", "123456", "roleCodes", List.of("ROLE_TENANT_ADMIN")
        ))));
        requireResult(rpcCall(adminToken, "rbac_tenant", "saveTenant",
                Map.of("values", Map.of("id", "sub_branch", "code", "sub_branch", "name", "下属分站", "parentTenantId", "hq_corp"))));

        Map<String, Object> hqLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "hq_mgr", "password", "123456")));
        assertThat((Boolean) hqLogin.get("canAccessStudio")).isFalse();
        assertThat((Boolean) hqLogin.get("canManageTenants")).isTrue();
        assertThat((Boolean) hqLogin.get("isSystemAdmin")).isFalse();
        String hqToken = (String) hqLogin.get("token");

        // 1) hq_mgr 只能看到自己及下属租户与账号，不可见 admin / user / tenant_b
        List<Map<String, Object>> hqTenants = requireResult(rpcCall(hqToken, "rbac_tenant", "listTenants", null));
        assertThat(hqTenants).extracting(t -> t.get("id")).containsExactlyInAnyOrder("hq_corp", "sub_branch");
        List<Map<String, Object>> hqUsers = requireResult(rpcCall(hqToken, "rbac_user", "listUsers", null));
        assertThat(hqUsers).extracting(u -> u.get("username")).containsOnly("hq_mgr");

        // 2) hq_mgr 禁止调用根平台开发、建表、字典、修改内置角色、授予通配符权限及清理等管理接口 (-32001)
        requireError(rpcCall(hqToken, "meta_app", "save", Map.of("app", Map.of("name", "bad", "displayName", "bad"))), -32001);
        requireError(rpcCall(hqToken, "meta_dict", "saveType", Map.of("values", Map.of("typeCode", "BAD", "name", "BAD"))), -32001);
        requireError(rpcCall(hqToken, "rbac_role", "saveRole", Map.of("values", Map.of("code", "ROLE_APP_USER", "name", "hack"))), -32001);
        requireError(rpcCall(hqToken, "rbac_role", "saveRole", Map.of("values", Map.of(
                "code", "ROLE_EVIL", "name", "hack", "allowedServices", List.of("*")))), -32001);
        // hq_mgr 获取权限目录时不应看到平台核心组 (kernel / builtin_wildcard) 或未授权应用模型
        List<Map<String, Object>> hqCatalog = requireResult(rpcCall(hqToken, "rbac_role", "permissionCatalog", null));
        assertThat(hqCatalog).extracting(g -> g.get("groupKey")).doesNotContain("kernel", "builtin_wildcard");

        // 3) hq_mgr 禁止操作根系统或其他企业的账号/租户，禁止赋予 ROLE_ADMIN (-32001)
        requireError(rpcCall(hqToken, "rbac_user", "resetPassword", Map.of("userId", "usr_admin", "newPassword", "hack")), -32001);
        requireError(rpcCall(hqToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "evil", "tenantId", "sub_branch", "roleCodes", List.of("ROLE_ADMIN")))), -32001);
        requireError(rpcCall(hqToken, "rbac_tenant", "authorizeApps",
                Map.of("tenantId", "tenant_b", "authorizedApps", List.of("foundation_qa_app"))), -32001);
        // 禁止给下属授出总部自己未获授权的应用 ext_qa_app 或通配符 "*" (-32001)
        requireError(rpcCall(hqToken, "rbac_tenant", "authorizeApps",
                Map.of("tenantId", "sub_branch", "authorizedApps", List.of("ext_qa_app"))), -32001);
        requireError(rpcCall(hqToken, "rbac_tenant", "authorizeApps",
                Map.of("tenantId", "hq_corp", "authorizedApps", List.of("foundation_qa_app"))), -32001);

        // 4) hq_mgr 正常给下属 sub_branch 授权 foundation_qa_app 并仅勾选 qa_ticket 对应菜单，隐藏 qa_notice 菜单，创建下级业务账号 sub_u1
        Map<String, Object> noticeDraft = requireResult(rpcCall(adminToken, "meta_model", "saveDraft", Map.of("snapshot", Map.of(
                "name", "qa_notice",
                "displayName", "QA公告模型",
                "appName", "foundation_qa_app",
                "versionNo", "1.0.0",
                "properties", List.of(
                        Map.of("name", "title", "displayName", "公告标题", "dataType", "String", "required", true, "displayForModel", true)
                )
        ))));
        Map<String, Object> noticeImpact = requireResult(rpcCall(adminToken, "meta_model", "checkImpact",
                Map.of("modelName", "qa_notice", "versionId", noticeDraft.get("id"))));
        requireResult(rpcCall(adminToken, "meta_model", "publish", Map.of(
                "modelName", "qa_notice", "versionId", noticeDraft.get("id"), "validationHash", noticeImpact.get("validationHash"))));
        requireResult(rpcCall(adminToken, "meta_model", "buildDefaultViews", Map.of("modelName", "qa_notice")));
        requireResult(rpcCall(adminToken, "meta_menu", "saveMenu", Map.of("menu", Map.of(
                "name", "qa_notice_menu", "displayName", "QA公告菜单",
                "appName", "foundation_qa_app", "model", "qa_notice", "sequence", 20, "active", true))));

        // 从 hq_mgr 视角获取可分配菜单列表，找出 qa_ticket 对应的菜单名
        List<Map<String, Object>> hqAssignable = requireResult(rpcCall(hqToken, "ui_menu", "listAssignableMenus", null));
        List<Map<String, Object>> fqaMenus = hqAssignable.stream()
                .filter(g -> "foundation_qa_app".equals(g.get("appName")))
                .flatMap(g -> ((List<Map<String, Object>>) g.get("menus")).stream())
                .toList();
        String ticketMenuName = fqaMenus.stream()
                .filter(m -> "qa_ticket".equals(m.get("model")))
                .map(m -> String.valueOf(m.get("name")))
                .findFirst().orElseThrow();
        assertThat(fqaMenus).extracting(m -> m.get("name")).contains(ticketMenuName, "qa_notice_menu");

        requireResult(rpcCall(hqToken, "rbac_tenant", "authorizeApps", Map.of(
                "tenantId", "sub_branch",
                "authorizedApps", List.of("foundation_qa_app"),
                "authorizedMenus", List.of(ticketMenuName))));
        requireResult(rpcCall(hqToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "sub_u1", "displayName", "分站业务员", "tenantId", "sub_branch",
                "password", "123456", "roleCodes", List.of("ROLE_APP_USER")
        ))));
        Map<String, Object> subLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "sub_u1", "password", "123456")));
        String subToken = (String) subLogin.get("token");

        // sub_u1 仅能看到勾选的 ticketMenuName，看不到未勾选的 qa_notice_menu 且直接 loadView 被拒，但跨菜单外键 lookup 仍可用
        List<String> subMenus = flattenMenuNames(requireResult(rpcCall(subToken, "ui_menu", "loadMenu", null)));
        assertThat(subMenus).contains(ticketMenuName).doesNotContain("qa_notice_menu");
        requireResult(rpcCall(subToken, "ui_view", "loadView", Map.of("model", "qa_ticket")));
        requireError(rpcCall(subToken, "ui_view", "loadView", Map.of("model", "qa_notice")), -32001);
        requireResult(rpcCall(subToken, "qa_notice", "lookup", Map.of("keyword", "")));

        // 5) hq_mgr 为下属分站创建自定义角色 ROLE_SUB_NONE 限制菜单为空 (__NONE__)，验证角色级菜单进一步收敛
        requireResult(rpcCall(hqToken, "rbac_role", "saveRole", Map.of("values", Map.of(
                "code", "ROLE_SUB_NONE",
                "name", "无业务菜单测试角色",
                "tenantId", "sub_branch",
                "dataScope", "ALL",
                "allowedServices", List.of("ui_*:*", "qa_ticket:search", "qa_ticket:find", "*:lookup"),
                "allowedMenus", List.of("__NONE__")))));
        requireResult(rpcCall(hqToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "sub_limited", "displayName", "受限角色员工", "tenantId", "sub_branch",
                "password", "123456", "roleCodes", List.of("ROLE_SUB_NONE")))));
        Map<String, Object> subLimitedLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "sub_limited", "password", "123456")));
        String subLimitedToken = (String) subLimitedLogin.get("token");
        List<String> limitedMenus = flattenMenuNames(requireResult(rpcCall(subLimitedToken, "ui_menu", "loadMenu", null)));
        assertThat(limitedMenus).doesNotContain(ticketMenuName, "qa_notice_menu");
        requireError(rpcCall(subLimitedToken, "ui_view", "loadView", Map.of("model", "qa_ticket")), -32001);

        // admin 兜底重新放开 sub_branch 菜单为 ["*"] 后，sub_u1 即可恢复可见 qa_notice_menu
        requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeMenus", Map.of(
                "tenantId", "sub_branch", "authorizedMenus", List.of("*"))));
        List<String> restoredMenus = flattenMenuNames(requireResult(rpcCall(subToken, "ui_menu", "loadMenu", null)));
        assertThat(restoredMenus).contains(ticketMenuName, "qa_notice_menu");

        // 6) 业务数据层级边界：sub_u1 新建一条工单，hq_mgr 可看下属且不可见 root(user) 与 tenant_b 数据；sub_u1 只见自己的
        requireResult(rpcCall(subToken, "qa_ticket", "create", Map.of("values", Map.of("title", "分站专属工单1"))));
        Map<String, Object> hqVisible = requireResult(rpcCall(hqToken, "qa_ticket", "search", Map.of("filter", Collections.emptyList())));
        assertThat((List<Map<String, Object>>) hqVisible.get("records"))
                .extracting(r -> r.get("title")).containsExactly("分站专属工单1");
    }

    @Test
    @Order(9)
    @SuppressWarnings("unchecked")
    void testManufacturingPackageAndWorkOrderLifecycle() throws IOException {
        Path packageFile = Path.of("..", "docs", "mes-manufacturing-package.json");
        Map<String, Object> pkg = objectMapper.readValue(packageFile.toFile(), Map.class);
        String legacyPackageJson = Files.readString(packageFile).replace("com.edu.scnu.", "com.sie.lowcode.");
        Map<String, Object> legacyPkg = objectMapper.readValue(legacyPackageJson, Map.class);
        ((Map<String, Object>) legacyPkg.get("app")).put("version", "1.0.0");
        List<Map<String, Object>> legacyModels = (List<Map<String, Object>>) legacyPkg.get("models");
        legacyModels.removeIf(model -> "mes_operation_execution".equals(model.get("name")));
        ((Map<String, Object>) legacyPkg.get("views")).keySet().removeIf(key -> key.startsWith("mes_operation_execution_"));
        ((Map<String, Object>) legacyPkg.get("menus")).remove("mes_operation_execution_menu");
        for (Map<String, Object> model : legacyModels) {
            model.put("versionNo", "1.0.0");
            model.remove("validateWritesWithActiveVersion");
            model.remove("useActiveVersionForRecords");
            if ("mes_work_order".equals(model.get("name"))) {
                Set<String> originalFields = Set.of("order_no", "product_id", "route_id", "planned_quantity", "due_date", "status");
                ((List<Map<String, Object>>) model.get("properties")).removeIf(field -> !originalFields.contains(field.get("name")));
            }
            if (model.get("services") instanceof List<?> services) {
                services.removeIf(service -> "validate".equals(((Map<?, ?>) service).get("name")));
            }
        }
        Map<String, Object> imported = requireResult(rpcCall(adminToken, "meta_app", "importPackage",
                Map.of("package", legacyPkg, "autoPublish", true)));
        assertThat((List<?>) imported.get("importedModels")).hasSize(7);

        Map<String, Object> material = requireResult(rpcCall(userToken, "mes_material", "create",
                Map.of("values", Map.of("code", "MAT-01", "name", "钢坯", "unit", "件"))));
        String materialId = (String) material.get("id");
        Map<String, Object> product = requireResult(rpcCall(userToken, "mes_product", "create",
                Map.of("values", Map.of("code", "PRD-01", "name", "电机支架", "bom_lines",
                        List.of(List.of(0, 0, Map.of("material_id", materialId, "quantity", 2)))))));
        String productId = (String) product.get("id");
        assertThat((List<Map<String, Object>>) product.get("bom_lines"))
                .extracting(line -> line.get("quantity")).containsExactly(2.0);

        Map<String, Object> route = requireResult(rpcCall(userToken, "mes_process_route", "create",
                Map.of("values", Map.of("code", "RT-01", "name", "支架标准路线", "product_id", productId, "operations",
                        List.of(
                                List.of(0, 0, Map.of("step_no", 20, "name", "去毛刺")),
                                List.of(0, 0, Map.of("step_no", 10, "name", "切割", "bom_lines",
                                        List.of(List.of(0, 0, Map.of("material_id", materialId, "quantity", 1)))))
                        )))));
        String routeId = (String) route.get("id");
        Map<String, Object> steps = requireResult(rpcCall(userToken, "mes_process", "search",
                Map.of("filter", List.of(List.of("route_id", "=", routeId)))));
        List<Map<String, Object>> stepRecords = (List<Map<String, Object>>) steps.get("records");
        assertThat(stepRecords).extracting(step -> step.get("step_no")).containsExactly(10, 20);
        Map<String, Object> firstStep = requireResult(rpcCall(userToken, "mes_process", "find", Map.of("id", stepRecords.get(0).get("id"))));
        assertThat((List<?>) firstStep.get("bom_lines")).hasSize(1);

        Map<String, Object> otherProduct = requireResult(rpcCall(userToken, "mes_product", "create",
                Map.of("values", Map.of("code", "PRD-02", "name", "压板"))));
        Map<String, Object> emptyRoute = requireResult(rpcCall(userToken, "mes_process_route", "create",
                Map.of("values", Map.of("code", "RT-EMPTY", "name", "待编制工艺", "product_id", productId))));
        Map<String, Object> legacyOrder = requireResult(rpcCall(userToken, "mes_work_order", "create", Map.of("values", Map.of(
                "order_no", "WO-LEGACY", "product_id", productId, "route_id", emptyRoute.get("id"), "planned_quantity", 20))));
        Map<String, Object> inFlight = requireResult(rpcCall(userToken, "mes_work_order", "create", Map.of("values", Map.of(
                "order_no", "WO-INFLIGHT", "product_id", productId, "route_id", routeId, "planned_quantity", 20))));
        Map<String, Object> inFlightArgs = Map.of("id", inFlight.get("id"));
        requireResult(rpcCall(userToken, "mes_work_order", "update", Map.of(
                "id", inFlight.get("id"), "values", Map.of("status", "RUNNING"))));
        Map<String, Object> upgraded = requireResult(rpcCall(adminToken, "meta_app", "importPackage",
                Map.of("package", pkg, "autoPublish", true)));
        assertThat((List<?>) upgraded.get("importedModels")).hasSize(8);
        requireError(rpcCall(userToken, "mes_work_order", "startProduction", Map.of("id", legacyOrder.get("id"))), -32003);
        requireError(rpcCall(userToken, "mes_process_route", "update", Map.of(
                "id", emptyRoute.get("id"), "values", Map.of("product_id", otherProduct.get("id")))), -32003);
        Map<String, Object> legacyReloaded = requireResult(rpcCall(userToken, "mes_work_order", "find", Map.of("id", legacyOrder.get("id"))));
        assertThat(legacyReloaded).containsEntry("bound_version_id", "mes_work_order@1.0.0").containsEntry("status", "PENDING");
        Map<String, Object> recovered = requireResult(rpcCall(userToken, "mes_work_order", "startProduction", inFlightArgs));
        assertThat(recovered).containsEntry("bound_version_id", "mes_work_order@1.0.0").containsEntry("status", "RUNNING");
        assertThat(recovered.get("started_at")).isNull();
        assertThat(recovered.get("started_by")).isNull();
        assertThat((List<?>) recovered.get("operation_executions")).hasSize(2);
        requireError(rpcCall(userToken, "mes_work_order", "startProduction", inFlightArgs), -32003);
        requireError(rpcCall(userToken, "mes_work_order", "cancelProduction",
                Map.of("id", inFlight.get("id"), "reason", "  ")), -32602);
        Map<String, Object> cancelled = requireResult(rpcCall(userToken, "mes_work_order", "cancelProduction",
                Map.of("id", inFlight.get("id"), "reason", "生产计划取消")));
        assertThat(cancelled).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "生产计划取消")
                .containsEntry("cancelled_by", "user");
        assertThat((List<?>) cancelled.get("operation_executions")).hasSize(2);
        requireError(rpcCall(userToken, "mes_work_order", "delete", inFlightArgs), -32003);
        Object cancelledStep = ((List<Map<String, Object>>) cancelled.get("operation_executions")).get(0).get("id");
        requireError(rpcCall(userToken, "mes_operation_execution", "startOperation", Map.of("id", cancelledStep)), -32003);

        requireError(rpcCall(userToken, "mes_work_order", "create", Map.of("values", Map.of(
                "order_no", "WO-WRONG", "product_id", otherProduct.get("id"), "route_id", routeId, "planned_quantity", 20))), -32602);
        requireError(rpcCall(userToken, "mes_work_order", "create", Map.of("values", Map.of(
                "order_no", "WO-FRACTION", "product_id", productId, "route_id", routeId, "planned_quantity", 1.5))), -32602);
        Map<String, Object> order = requireResult(rpcCall(userToken, "mes_work_order", "create", Map.of("values", Map.of(
                "order_no", "WO-001", "product_id", productId, "route_id", routeId, "planned_quantity", 20,
                "due_date", "2026-11-26"))));
        String orderId = (String) order.get("id");
        Map<String, Object> orderArgs = Map.of("id", orderId);
        assertThat(order.get("status")).isEqualTo("PENDING");
        Map<String, Object> emptyQuantity = new HashMap<>();
        emptyQuantity.put("planned_quantity", null);
        requireError(rpcCall(userToken, "mes_work_order", "update", Map.of("id", orderId, "values", emptyQuantity)), -32602);
        requireError(rpcCall(userToken, "mes_work_order", "update",
                Map.of("id", orderId, "values", Map.of("order_no", ""))), -32602);
        requireError(rpcCall(userToken, "mes_work_order", "finishProduction", orderArgs), -32003);
        requireError(rpcCall(userToken, "mes_work_order", "update",
                Map.of("id", orderId, "values", Map.of("status", "DONE"))), -32003);
        Map<String, Object> started = requireResult(rpcCall(userToken, "mes_work_order", "startProduction", orderArgs));
        assertThat(started).containsEntry("status", "RUNNING").containsEntry("started_by", "user");
        assertThat(started.get("started_at")).isNotNull();
        requireError(rpcCall(userToken, "mes_work_order", "startProduction", orderArgs), -32003);
        requireError(rpcCall(userToken, "mes_work_order", "finishProduction", orderArgs), -32003);
        requireError(rpcCall(userToken, "mes_work_order", "update",
                Map.of("id", orderId, "values", Map.of("planned_quantity", 5))), -32003);
        List<Map<String, Object>> operations = (List<Map<String, Object>>) started.get("operation_executions");
        assertThat(operations).extracting(record -> record.get("step_no")).containsExactly(10, 20);
        Object firstExecution = operations.get(0).get("id");
        Object lastExecution = operations.get(1).get("id");
        requireError(rpcCall(userToken, "mes_operation_execution", "startOperation", Map.of("id", lastExecution)), -32003);
        requireError(rpcCall(userToken, "mes_operation_execution", "update",
                Map.of("id", firstExecution, "values", Map.of("status", "DONE"))), -32003);
        requireError(rpcCall(userToken, "mes_operation_execution", "delete", Map.of("id", firstExecution)), -32003);
        requireResult(rpcCall(userToken, "mes_operation_execution", "startOperation", Map.of("id", firstExecution)));
        requireError(rpcCall(userToken, "mes_operation_execution", "reportOperation",
                Map.of("id", firstExecution, "actual_quantity", -1)), -32602);
        requireResult(rpcCall(userToken, "mes_operation_execution", "reportOperation",
                Map.of("id", firstExecution, "actual_quantity", 19)));
        requireError(rpcCall(userToken, "mes_operation_execution", "reportOperation",
                Map.of("id", firstExecution, "actual_quantity", 20)), -32003);
        requireResult(rpcCall(userToken, "mes_operation_execution", "startOperation", Map.of("id", lastExecution)));
        Map<String, Object> reported = requireResult(rpcCall(userToken, "mes_operation_execution", "reportOperation",
                Map.of("id", lastExecution, "actual_quantity", 18)));
        assertThat(reported).containsEntry("actual_quantity", 18).containsEntry("reported_by", "user");
        assertThat(reported.get("reported_at")).isNotNull();
        Map<String, Object> finished = requireResult(rpcCall(userToken, "mes_work_order", "finishProduction", orderArgs));
        assertThat(finished).containsEntry("status", "DONE").containsEntry("actual_quantity", 18)
                .containsEntry("finished_by", "user");
        assertThat(finished.get("finished_at")).isNotNull();
        requireError(rpcCall(userToken, "mes_work_order", "delete", orderArgs), -32003);
        requireError(rpcCall(userToken, "mes_work_order", "finishProduction", orderArgs), -32003);
        requireError(rpcCall(userToken, "mes_work_order", "update",
                Map.of("id", orderId, "values", Map.of("planned_quantity", 99))), -32003);
        Map<String, Object> reloaded = requireResult(rpcCall(userToken, "mes_work_order", "find", orderArgs));
        assertThat(reloaded).containsEntry("status", "DONE").containsEntry("planned_quantity", 20);
        Map<String, Object> draft = requireResult(rpcCall(userToken, "mes_work_order", "create", Map.of("values", Map.of(
                "order_no", "WO-REMOVE", "product_id", productId, "route_id", routeId, "planned_quantity", 1))));
        Map<String, Object> removed = requireResult(rpcCall(userToken, "mes_work_order", "delete", Map.of("id", draft.get("id"))));
        assertThat(removed).containsEntry("deletedCount", 1);
        requireError(rpcCall(userToken, "mes_process_bom", "create", Map.of("values", Map.of(
                "process_id", firstStep.get("id"), "material_id", "missing-material", "quantity", 1))), -32602);
        requireError(rpcCall(userToken, "mes_process_route", "delete", Map.of("id", routeId)), -32003);
        assertThat((List<?>) ((Map<?, ?>) requireResult(rpcCall(userToken, "mes_process", "find",
                Map.of("id", firstStep.get("id"))))).get("bom_lines")).hasSize(1);
        Map<String, Object> disposableStep = Map.of("step_no", 10, "name", "测试工序", "bom_lines",
                List.of(List.of(0, 0, Map.of("material_id", materialId, "quantity", 1))));
        Map<String, Object> disposableRoute = requireResult(rpcCall(userToken, "mes_process_route", "create",
                Map.of("values", Map.of("code", "RT-CASCADE", "name", "级联测试路线", "product_id", productId,
                        "operations", List.of(List.of(0, 0, disposableStep))))));
        String disposableRouteId = (String) disposableRoute.get("id");
        Object disposableProcessId = ((List<Map<String, Object>>) disposableRoute.get("operations")).get(0).get("id");
        requireResult(rpcCall(userToken, "mes_process_route", "delete", Map.of("id", disposableRouteId)));
        Number stepCount = requireResult(rpcCall(userToken, "mes_process", "count",
                Map.of("filter", List.of(List.of("route_id", "=", disposableRouteId)))));
        Number bomCount = requireResult(rpcCall(userToken, "mes_process_bom", "count",
                Map.of("filter", List.of(List.of("process_id", "=", disposableProcessId)))));
        assertThat(stepCount.intValue()).isZero();
        assertThat(bomCount.intValue()).isZero();

        String managementMenu = "mes_work_order_menu";
        String executionMenu = "mes_work_order_execution_menu";
        List<Map<String, Object>> menuTree = requireResult(rpcCall(userToken, "ui_menu", "loadMenu", null));
        Map<String, Object> appRoot = menuTree.stream().filter(m -> "mes_manufacturing_root_menu".equals(m.get("name")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> children = (List<Map<String, Object>>) appRoot.get("children");
        assertThat(children).extracting(m -> m.get("routePath"))
                .contains("/menu/" + managementMenu, "/menu/" + executionMenu);
        Map<String, Object> execution = requireResult(rpcCall(userToken, "ui_view", "loadView",
                Map.of("menu", executionMenu, "type", "mes_work_order_grid")));
        assertThat((Map<String, Object>) execution.get("views"))
                .containsKey("mes_work_order_execution_grid").doesNotContainKey("mes_work_order_grid");
        Map<?, ?> formBody = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) execution.get("views")).get("form")).get("body");
        assertThat((List<?>) formBody.get("groupConf")).hasSize(3);
        Map<String, Object> dateRange = requireResult(rpcCall(userToken, "mes_work_order", "search", Map.of("filter",
                List.of(List.of("due_date", ">=", "2026-11-26"), List.of("due_date", "<=", "2026-11-26")))));
        assertThat((List<Map<String, Object>>) dateRange.get("records"))
                .extracting(record -> record.get("order_no")).containsExactly("WO-001");
        Map<String, Object> operationView = requireResult(rpcCall(userToken, "ui_view", "loadView",
                Map.of("menu", "mes_operation_execution_menu")));
        assertThat(operationFinishAllowed(operationView)).isEqualTo(true);

        requireResult(rpcCall(adminToken, "rbac_tenant", "saveTenant", Map.of("values",
                Map.of("id", "mes_execution_tenant", "code", "mes_execution_tenant", "name", "MES 执行测试租户"))));
        requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeApps", Map.of(
                "tenantId", "mes_execution_tenant", "authorizedApps", List.of("mes_manufacturing"),
                "authorizedMenus", List.of(executionMenu))));
        requireResult(rpcCall(adminToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "mes_execution_test", "displayName", "工单执行测试员", "tenantId", "mes_execution_tenant",
                "password", "123456", "roleCodes", List.of("ROLE_APP_USER")))));
        Map<String, Object> operator = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "mes_execution_test", "password", "123456")));
        String operatorToken = (String) operator.get("token");
        requireResult(rpcCall(operatorToken, "ui_view", "loadView", Map.of("menu", executionMenu)));
        requireError(rpcCall(operatorToken, "ui_view", "loadView", Map.of("menu", managementMenu)), -32001);
        requireError(rpcCall(operatorToken, "ui_view", "loadView",
                Map.of("model", "mes_work_order", "type", "mes_work_order_grid")), -32001);
        Map<String, Object> legacyView = requireResult(rpcCall(operatorToken, "ui_view", "loadView",
                Map.of("model", "mes_work_order")));
        assertThat((Map<String, Object>) legacyView.get("views"))
                .containsKey("mes_work_order_execution_grid").doesNotContainKey("mes_work_order_grid");
        restClient.get().uri(uploadedFileUrl)
                .headers(headers -> headers.setBearerAuth(operatorToken))
                .exchange()
                .expectStatus().isNotFound();

        requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeMenus", Map.of(
                "tenantId", "mes_execution_tenant", "authorizedMenus", List.of(executionMenu, "mes_operation_execution_menu"))));
        requireResult(rpcCall(adminToken, "rbac_role", "saveRole", Map.of("values", Map.of(
                "tenantId", "mes_execution_tenant", "code", "ROLE_MES_REPORT_ONLY", "name", "仅报工",
                "allowedServices", List.of("ui_*:*", "mes_operation_execution:search", "mes_operation_execution:reportOperation")))));
        requireResult(rpcCall(adminToken, "rbac_user", "saveUser", Map.of("values", Map.of(
                "username", "mes_report_only", "displayName", "仅报工人员", "tenantId", "mes_execution_tenant",
                "password", "123456", "roleCodes", List.of("ROLE_MES_REPORT_ONLY")))));
        Map<String, Object> reportOnly = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "mes_report_only", "password", "123456")));
        Map<String, Object> reportOnlyView = requireResult(rpcCall((String) reportOnly.get("token"), "ui_view", "loadView",
                Map.of("menu", "mes_operation_execution_menu")));
        assertThat(operationFinishAllowed(reportOnlyView)).isEqualTo(false);

        requireResult(rpcCall(adminToken, "meta_menu", "saveMenu", Map.of("menu", Map.of(
                "name", "mes_finished_menu", "displayName", "已完工", "appName", "mes_manufacturing",
                "model", "mes_work_order", "view", "mes_work_order_grid",
                "filter", "[[\"status\",\"=\",\"DONE\"]]"))));
        Map<String, Object> filtered = requireResult(rpcCall(userToken, "mes_work_order", "search",
                Map.of("menu", "mes_finished_menu")));
        assertThat((List<Map<String, Object>>) filtered.get("records"))
                .extracting(record -> record.get("order_no")).containsExactly("WO-001");
        Number filteredCount = requireResult(rpcCall(userToken, "mes_work_order", "count",
                Map.of("menu", "mes_finished_menu", "filter", List.of(List.of("status", "=", "PENDING")))));
        assertThat(filteredCount.intValue()).isZero();
    }

    private Object operationFinishAllowed(Map<String, Object> payload) {
        Map<?, ?> auth = (Map<?, ?>) payload.get("auth");
        Map<?, ?> targets = (Map<?, ?>) auth.get("targetServices");
        return ((Map<?, ?>) targets.get("mes_work_order")).get("finishProduction");
    }

    @Test
    @Order(10)
    @SuppressWarnings("unchecked")
    void testDraftUiIsolationAndTableNameProtection() {
        String modelName = "release_check_model";
        String appName = "release_check_app";
        String tableName = "biz_release_check";
        String viewKey = modelName + "_grid";
        Map<String, Object> app = Map.of("name", appName, "displayName", "测试发布应用");
        Map<String, Object> model = new LinkedHashMap<>(Map.of(
                "name", modelName, "displayName", "发布验证", "appName", appName, "tableName", tableName, "versionNo", "1.0.0",
                "properties", List.of(Map.of("name", "title", "displayName", "原表头", "dataType", "String", "displayForModel", true))));
        requireResult(rpcCall(adminToken, "meta_app", "importPackage",
                Map.of("package", Map.of("app", app, "models", List.of(model)), "autoPublish", true)));
        requireResult(rpcCall(userToken, modelName, "create", Map.of("values", Map.of("title", "要保留的数据"))));

        Map<String, Object> renamed = new LinkedHashMap<>(model);
        renamed.put("tableName", "biz_release_check_renamed");
        renamed.put("versionNo", "1.1.0");
        Map<String, Object> draftRename = requireResult(rpcCall(adminToken, "meta_model", "saveDraft", Map.of("snapshot", renamed)));
        Map<String, Object> renameArgs = Map.of("modelName", modelName, "versionId", draftRename.get("id"));
        Map<String, Object> renameImpact = requireResult(rpcCall(adminToken, "meta_model", "checkImpact", renameArgs));
        assertThat(renameImpact).containsEntry("passed", false);
        assertThat((List<String>) renameImpact.get("blockingReasons")).anyMatch(reason -> reason.contains("物理表名称"));
        requireError(rpcCall(adminToken, "meta_model", "publish", renameArgs), -32002);

        Map<String, Object> next = new LinkedHashMap<>(model);
        next.put("versionNo", "1.2.0");
        Map<String, Object> grid = Map.of("name", "草稿表格", "model", modelName, "type", "grid",
                "body", Map.of("type", "grid", "columns", List.of(Map.of("name", "title", "displayName", "新表头"))));
        String menuName = "release_check_extra_menu";
        Map<String, Object> newMenu = Map.of("name", menuName, "display_name", "草稿菜单", "model", modelName, "view", viewKey);
        Map<String, Object> packageDraft = Map.of(
                "app", Map.of("name", appName, "displayName", "草稿应用标题"), "models", List.of(next),
                "views", Map.of(viewKey, grid), "menus", Map.of(menuName, newMenu));
        Map<String, Object> staged = requireResult(rpcCall(adminToken, "meta_app", "importPackage",
                Map.of("package", packageDraft, "autoPublish", false)));
        String stagedId = (String) ((List<Map<String, Object>>) staged.get("importedModels")).get(0).get("versionId");

        Map<String, Object> original = requireResult(rpcCall(userToken, "ui_view", "loadView", Map.of("model", modelName)));
        assertThat(((Map<?, ?>) original.get("modelMeta")).get("versionNo")).isEqualTo("1.0.0");
        assertThat(gridHeader(original)).isEqualTo("原表头");
        assertThat(flattenMenuNames(requireResult(rpcCall(userToken, "ui_menu", "loadMenu", null)))).doesNotContain(menuName);
        List<Map<String, Object>> beforeApps = requireResult(rpcCall("/rpc/open/service", null, "sys_portal", "publicApps", null));
        assertThat(beforeApps.stream().filter(a -> appName.equals(a.get("name"))).findFirst().orElseThrow())
                .containsEntry("displayName", "测试发布应用");

        Map<String, Object> impact = requireResult(rpcCall(adminToken, "meta_model", "checkImpact",
                Map.of("modelName", modelName, "versionId", stagedId)));
        assertThat(impact).containsEntry("passed", true);
        requireResult(rpcCall(adminToken, "meta_model", "publish", Map.of(
                "modelName", modelName, "versionId", stagedId, "validationHash", impact.get("validationHash"))));
        Map<String, Object> published = requireResult(rpcCall(userToken, "ui_view", "loadView", Map.of("menu", menuName)));
        assertThat(((Map<?, ?>) published.get("modelMeta")).get("versionNo")).isEqualTo("1.2.0");
        assertThat(gridHeader(published)).isEqualTo("新表头");
        Number retained = requireResult(rpcCall(userToken, modelName, "count", null));
        assertThat(retained.intValue()).isEqualTo(1);
    }

    @Test
    @Order(11)
    @SuppressWarnings("unchecked")
    void testRootTenantRespectsCheckedAppsAndMenusForAdmin() {
        try {
            requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeApps", Map.of(
                    "tenantId", "rbac_tenant_root",
                    "authorizedApps", List.of("foundation_qa_app"),
                    "authorizedMenus", List.of("*"))));

            Map<String, Object> profile = requireResult(rpcCall(adminToken, "rbac_user", "profile", null));
            List<Map<String, Object>> authApps = (List<Map<String, Object>>) profile.get("authorizedApps");
            assertThat(authApps).extracting(a -> a.get("name")).containsExactly("foundation_qa_app");

            List<String> adminMenus = flattenMenuNames(requireResult(rpcCall(adminToken, "ui_menu", "loadMenu", null)));
            assertThat(adminMenus).doesNotContain("mes_work_order_menu", "mes_material_menu");

            requireError(rpcCall(adminToken, "ui_view", "loadView", Map.of("model", "mes_work_order")), -32001);
            requireError(rpcCall(adminToken, "mes_work_order", "search", null), -32001);

            List<Map<String, Object>> assignable = requireResult(rpcCall(adminToken, "ui_menu", "listAssignableMenus", null));
            assertThat(assignable).extracting(g -> g.get("appName")).contains("foundation_qa_app", "mes_manufacturing");

            requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeApps", Map.of(
                    "tenantId", "rbac_tenant_root",
                    "authorizedApps", List.of(),
                    "authorizedMenus", List.of("*"))));
            Map<String, Object> emptyLogin = requireResult(rpcCall(null, "rbac_user", "login",
                    Map.of("username", "admin", "password", "123456")));
            assertThat((List<?>) emptyLogin.get("authorizedApps")).isEmpty();
            assertThat(emptyLogin.get("canAccessStudio")).isEqualTo(true);
        } finally {
            requireResult(rpcCall(adminToken, "rbac_tenant", "authorizeApps", Map.of(
                    "tenantId", "rbac_tenant_root",
                    "authorizedApps", List.of("*"),
                    "authorizedMenus", List.of("*"))));
        }
    }

    private Object gridHeader(Map<String, Object> viewResponse) {
        Map<?, ?> grid = (Map<?, ?>) ((Map<?, ?>) viewResponse.get("views")).get("grid");
        List<?> columns = (List<?>) ((Map<?, ?>) grid.get("body")).get("columns");
        return ((Map<?, ?>) columns.get(0)).get("displayName");
    }

    @SuppressWarnings("unchecked")
    private List<String> flattenMenuNames(List<Map<String, Object>> tree) {
        List<String> names = new ArrayList<>();
        if (tree == null) return names;
        for (Map<String, Object> node : tree) {
            if (node.get("name") != null) names.add(String.valueOf(node.get("name")));
            if (node.get("children") instanceof List<?> children) {
                names.addAll(flattenMenuNames((List<Map<String, Object>>) children));
            }
        }
        return names;
    }
}
