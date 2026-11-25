package com.sie.lowcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 低代码平台全链路集成测试：
 * 验证通用元模型引擎、JSON-RPC 2.0 协议、Filter DSL、ManyToOne/OneToMany 级联、
 * Groovy 脚本扩展与事务回滚、声明式状态机、版本影响检查/兼容退役/回滚、RBAC 与双人互斥清除治理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    private TestRestTemplate restTemplate;

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcCall(String token, String model, String service, Map<String, Object> args) {
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/rbac_tenant_root/rpc/service",
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) resp.getBody();
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
    @Order(1)
    void testHealthAuthAndPureBootstrapMenus() {
        ResponseEntity<Map> health = restTemplate.getForEntity("/api/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");

        // 未携带 Token 访问受保护端点 -> -32001
        Map<String, Object> unauth = rpcCall(null, "ui_menu", "loadMenu", null);
        requireError(unauth, -32001);

        // 登录 developer / auditor / user 账号
        Map<String, Object> devLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "developer", "password", "123456")));
        devToken = (String) devLogin.get("token");
        assertThat(devToken).isNotBlank();

        Map<String, Object> auditorLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "auditor", "password", "123456")));
        adminToken = (String) auditorLogin.get("token");
        assertThat(adminToken).isNotBlank();

        Map<String, Object> userLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "user", "password", "123456")));
        userToken = (String) userLogin.get("token");

        // 确认纯平台初始化完成后无任何硬编码业务示例菜单
        List<Map<String, Object>> menus = requireResult(rpcCall(devToken, "ui_menu", "loadMenu", null));
        assertThat(menus).isEmpty();

        // 确认自举内核模型已存在且标记为 kernelModel=true
        List<Map<String, Object>> models = requireResult(rpcCall(devToken, "meta_model", "listModels", null));
        assertThat(models).extracting(m -> m.get("name"))
                .contains("meta_app", "meta_model", "meta_publish_record", "meta_purge_audit");
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
                  "scriptContent": "def row = rs.call('find', [id: args.id]);\\nint nextP = ((row.priority ?: 1) as int) + (args.delta ?: 1);\\nif (args.failAfterUpdate) { rs.call('update', [id: args.id, values: [priority: 4]]); throw com.sie.lowcode.domain.shared.DomainException.invalidParams('模拟脚本异常触发回滚'); };\\nreturn rs.call('update', [id: args.id, values: [priority: nextP, status: 'DONE']]);"
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
                .contains("/m/test_dept", "/m/test_task", "/m/test_task_item");

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
    void testOfflineAndDualReviewPurgeGovernance() {
        // 登录全权限 admin 用于验证“同账号既发起又终审”双人互斥拦截 (-32004)
        Map<String, Object> adminSelfLogin = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "admin", "password", "123456")));
        String superAdminToken = (String) adminSelfLogin.get("token");

        // 1. 下线 test_task 模型 -> 动态菜单自动隐藏 + 普通运行时 RPC 被阻断 (-32003)
        requireResult(rpcCall(devToken, "meta_model", "offline", Map.of("modelName", "test_task")));
        requireError(rpcCall(userToken, "test_task", "search", Map.of("filter", Collections.emptyList())), -32003);

        List<Map<String, Object>> menusWhileOffline = requireResult(rpcCall(userToken, "ui_menu", "loadMenu", null));
        List<Map<String, Object>> children = (List<Map<String, Object>>) menusWhileOffline.get(0).get("children");
        assertThat(children).extracting(m -> m.get("model")).doesNotContain("test_task");

        // 2. 无导出凭据直接申请清除 -> 守卫阻断 (-32003)
        requireError(rpcCall(devToken, "meta_model", "applyPurge",
                Map.of("modelName", "test_task", "exportTicket", "FAKE_TICKET")), -32003);

        // 3. 先清除依赖子表 test_task_item（顺带验证 admin 发起后不可自我确认 -32004）
        requireResult(rpcCall(devToken, "meta_model", "offline", Map.of("modelName", "test_task_item")));
        Map<String, Object> expItem = requireResult(rpcCall(devToken, "meta_model", "exportData",
                Map.of("modelName", "test_task_item")));
        Map<String, Object> applyItem = requireResult(rpcCall(superAdminToken, "meta_model", "applyPurge",
                Map.of("modelName", "test_task_item", "exportTicket", expItem.get("exportTicket"))));
        String applyItemId = (String) applyItem.get("id");
        // 同身份 admin 既是发起人又有 ROLE_ADMIN -> 触发双人互斥异常 (-32004)
        requireError(rpcCall(superAdminToken, "meta_model", "confirmPurge", Map.of("applyId", applyItemId)), -32004);
        // 由独立审查员 auditor 完成确认清除
        requireResult(rpcCall(adminToken, "meta_model", "confirmPurge", Map.of("applyId", applyItemId)));

        // 4. 导出 test_task 数据获取有效凭据
        Map<String, Object> exp = requireResult(rpcCall(devToken, "meta_model", "exportData",
                Map.of("modelName", "test_task")));
        String exportTicket = (String) exp.get("exportTicket");
        assertThat(((Number) exp.get("recordCount")).intValue()).isEqualTo(2);

        // 存在未清理业务数据时守卫阻断，必须先清理测试数据
        requireError(rpcCall(devToken, "meta_model", "applyPurge",
                Map.of("modelName", "test_task", "exportTicket", exportTicket)), -32003);

        // 临时上线删除业务记录后再下线，完成合规清理链路
        requireResult(rpcCall(devToken, "meta_model", "online", Map.of("modelName", "test_task")));
        Map<String, Object> allRows = requireResult(rpcCall(userToken, "test_task", "search", Map.of("filter", Collections.emptyList())));
        List<String> allIds = ((List<Map<String, Object>>) allRows.get("records")).stream()
                .map(r -> (String) r.get("id")).toList();
        requireResult(rpcCall(devToken, "test_task", "delete", Map.of("ids", allIds)));
        requireResult(rpcCall(devToken, "meta_model", "offline", Map.of("modelName", "test_task")));

        // developer 发起清除申请
        Map<String, Object> apply = requireResult(rpcCall(devToken, "meta_model", "applyPurge",
                Map.of("modelName", "test_task", "exportTicket", exportTicket)));
        String applyId = (String) apply.get("id");

        // developer 无 ROLE_ADMIN 权限 -> -32001
        requireError(rpcCall(devToken, "meta_model", "confirmPurge", Map.of("applyId", applyId)), -32001);

        // 由独立审查员 auditor 确认清除
        Map<String, Object> audit = requireResult(rpcCall(adminToken, "meta_model", "confirmPurge",
                Map.of("applyId", applyId)));
        assertThat(audit.get("modelName")).isEqualTo("test_task");
        assertThat(audit.get("applyBy")).isNotEqualTo(audit.get("confirmBy"));

        // 清除后台账中不再存在 test_task，且审计记录保留
        List<Map<String, Object>> remainingModels = requireResult(rpcCall(devToken, "meta_model", "listModels", null));
        assertThat(remainingModels).extracting(m -> m.get("name")).doesNotContain("test_task");
        List<Map<String, Object>> audits = requireResult(rpcCall(adminToken, "meta_model", "listPurgeAudits", null));
        assertThat(audits).extracting(a -> a.get("modelName")).contains("test_task", "test_task_item");
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
        HttpHeaders upHeaders = new HttpHeaders();
        upHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        upHeaders.set("Authorization", "Bearer " + userToken);
        ResponseEntity<Map> upResp = restTemplate.postForEntity("/api/files/upload", new HttpEntity<>(multipart, upHeaders), Map.class);
        assertThat(upResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> fileMeta = upResp.getBody();
        assertThat(fileMeta).containsEntry("fileName", "spec-sheet.txt");
        ResponseEntity<byte[]> dlResp = restTemplate.getForEntity((String) fileMeta.get("downloadUrl"), byte[].class);
        assertThat(dlResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dlResp.getBody()).isEqualTo(sampleBytes);

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
                  "scriptContent": "if ('FORBIDDEN_TITLE' == args.values?.title) { throw com.sie.lowcode.domain.shared.DomainException.validationFailed('钩子拦截非法标题'); }"
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
                "allowedServices", List.of("ui_*:*", "qa_ticket:search", "qa_ticket:find", "meta_dict:*", "api_doc:*")
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

        // 5. 多租户数据严格隔离：b_writer 新建数据归属 tenant_b，双方互相不可见
        requireResult(rpcCall(bWriterToken, "qa_ticket", "create", Map.of("values", Map.of("title", "租户B专属工单"))));
        Map<String, Object> bRows = requireResult(rpcCall(bReaderToken, "qa_ticket", "search", Map.of("filter", Collections.emptyList())));
        assertThat(((Number) bRows.get("total")).longValue()).isEqualTo(1L);
        assertThat(((List<Map<String, Object>>) bRows.get("records")).get(0).get("title")).isEqualTo("租户B专属工单");

        Map<String, Object> rootRows = requireResult(rpcCall(userToken, "qa_ticket", "search", Map.of("filter", Collections.emptyList())));
        assertThat((List<Map<String, Object>>) rootRows.get("records"))
                .extracting(r -> r.get("title")).containsExactly("工单Alpha");

        // 6. 自动 API 文档与权限目录验证
        List<Map<String, Object>> catalog = requireResult(rpcCall(devToken, "api_doc", "listCatalog", null));
        assertThat(catalog).isNotEmpty();
        Map<String, Object> childSpec = requireResult(rpcCall(devToken, "api_doc", "describeModel", Map.of("modelName", "child_doc")));
        assertThat((List<Map<String, Object>>) childSpec.get("properties"))
                .extracting(p -> p.get("name")).contains("doc_no", "title", "ext_note", "dept_tags");
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
        ResponseEntity<Map> openResp = restTemplate.postForEntity("/rpc/open/service", openReq, Map.class);
        assertThat(openResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) openResp.getBody().get("result"))
                .extracting(a -> a.get("name")).contains("foundation_qa_app", "ext_qa_app");
    }
}
