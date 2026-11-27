package com.edu.scnu;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ExpressStationIntegrationTests {

    private static Path tempDbFile;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws IOException {
        Path tempDir = Files.createTempDirectory("express-test-");
        tempDbFile = tempDir.resolve("express-test.db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDbFile.toAbsolutePath());
    }

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private ObjectMapper objectMapper;

    private static String adminToken;

    @SuppressWarnings("unchecked")
    private Map<String, Object> rpcCall(String token, String model, String service, Map<String, Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("app", "express_test");
        params.put("tag", "master");
        params.put("model", model);
        params.put("service", service);
        params.put("args", args != null ? args : Collections.emptyMap());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", UUID.randomUUID().toString());
        payload.put("method", "service");
        payload.put("params", params);

        return (Map<String, Object>) restClient.post().uri("/rbac_tenant_root/rpc/service")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .body(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private <T> T requireResult(Map<String, Object> resp) {
        assertThat(resp).isNotNull();
        assertThat(resp.get("error")).withFailMessage("Unexpected RPC error: %s", resp.get("error")).isNull();
        return (T) resp.get("result");
    }

    @SuppressWarnings("unchecked")
    private void requireAnyError(Map<String, Object> resp, String messagePart) {
        assertThat(resp).isNotNull();
        Map<String, Object> err = (Map<String, Object>) resp.get("error");
        assertThat(err).withFailMessage("Expected RPC error containing [%s] but got result: %s", messagePart, resp.get("result")).isNotNull();
        if (messagePart != null) {
            assertThat(String.valueOf(err.get("message")) + " " + String.valueOf(err.get("data"))).contains(messagePart);
        }
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void testExpressStationEndToEndWorkflow() throws Exception {
        Map<String, Object> loginRes = requireResult(rpcCall(null, "rbac_user", "login",
                Map.of("username", "admin", "password", "123456")));
        adminToken = (String) loginRes.get("token");
        assertThat(adminToken).isNotBlank();

        Path pkgPath = Path.of("../docs/express-station-package.json").normalize();
        if (!Files.exists(pkgPath)) {
            pkgPath = Path.of("docs/express-station-package.json").toAbsolutePath();
        }
        Map<String, Object> pkg = objectMapper.readValue(Files.readString(pkgPath), Map.class);
        Map<String, Object> imported = requireResult(rpcCall(adminToken, "meta_app", "importPackage",
                Map.of("package", pkg, "autoPublish", true)));
        assertThat((List<?>) imported.get("importedModels")).hasSize(6);

        // 1. 货架管理维护：创建货架、停用维护、禁止在停用/满容货架上架、容量与删除校验
        requireAnyError(rpcCall(adminToken, "exp_shelf", "create", Map.of("values", Map.of(
                "shelf_code", "X区-0号架", "zone_type", "A", "capacity", 0, "manager_name", "测试员"))), "容量");

        Map<String, Object> shelfA = requireResult(rpcCall(adminToken, "exp_shelf", "create", Map.of("values", Map.of(
                "shelf_code", "A区-小件1号架", "zone_type", "A", "capacity", 1, "manager_name", "李明"))));
        String shelfAId = (String) shelfA.get("id");
        assertThat(((Number) shelfA.get("used_count")).intValue()).isEqualTo(0);
        assertThat(((Number) shelfA.get("available_count")).intValue()).isEqualTo(1);

        Map<String, Object> shelfB = requireResult(rpcCall(adminToken, "exp_shelf", "create", Map.of("values", Map.of(
                "shelf_code", "B区-中件2号架", "zone_type", "B", "capacity", 2, "manager_name", "王芳"))));
        String shelfBId = (String) shelfB.get("id");

        // 停用货架 -> 不可入库存放 -> 恢复启用
        Map<String, Object> disabledShelf = requireResult(rpcCall(adminToken, "exp_shelf", "disableShelf", Map.of("id", shelfAId)));
        assertThat(((Map<?, ?>) disabledShelf.get("status")).get("value")).isEqualTo("DISABLED");
        requireAnyError(rpcCall(adminToken, "exp_parcel", "create", Map.of("values", Map.of(
                "tracking_no", "SF000001", "courier_brand", "SF", "recipient_name", "张三",
                "recipient_phone", "13800138000", "shelf_id", shelfAId))), "已停用");
        requireResult(rpcCall(adminToken, "exp_shelf", "enableShelf", Map.of("id", shelfAId)));

        // 2. 快递车到站清点入库生成待上架包裹 + 直接入库登记（无需手填取件码）
        Map<String, Object> batch = requireResult(rpcCall(adminToken, "exp_inbound_batch", "create", Map.of("values", Map.of(
                "courier_brand", "JD", "driver_name", "张师傅", "total_count", 2,
                "details", List.of(
                        List.of(0, 0, Map.of("tracking_no", "JD1000001", "recipient_name", "赵敏", "recipient_phone", "13900139001", "damaged", false)),
                        List.of(0, 0, Map.of("tracking_no", "JD1000002", "recipient_name", "周芷若", "recipient_phone", "13900139002", "damaged", true, "note", "外箱压扁"))
                )))));
        requireResult(rpcCall(adminToken, "exp_inbound_batch", "finishCheck", Map.of("id", batch.get("id"))));

        Map<String, Object> batchParcels = requireResult(rpcCall(adminToken, "exp_parcel", "search",
                Map.of("filter", List.of(List.of("tracking_no", "=", "JD1000001")))));
        assertThat(((List<?>) batchParcels.get("records"))).hasSize(1);
        Map<String, Object> parcel1 = ((List<Map<String, Object>>) batchParcels.get("records")).get(0);
        String parcel1Id = (String) parcel1.get("id");
        assertThat(parcel1).containsEntry("status", "PENDING");
        assertThat(parcel1.get("pickup_code")).isNull();

        Map<String, Object> parcel2 = requireResult(rpcCall(adminToken, "exp_parcel", "create", Map.of("values", Map.of(
                "tracking_no", "SF2000002", "courier_brand", "SF", "parcel_size", "MEDIUM",
                "recipient_name", "张无忌", "recipient_phone", "13700137002"))));
        String parcel2Id = (String) parcel2.get("id");
        assertThat(parcel2).containsEntry("status", "PENDING");

        // 3. 上架自动生成取件码 + 货架容量与停用校验
        Map<String, Object> shelveRes = requireResult(rpcCall(adminToken, "exp_parcel", "putOnShelf",
                Map.of("id", parcel1Id, "shelf_id", shelfAId)));
        assertThat(String.valueOf(shelveRes.get("message"))).contains("生成取件码");
        Map<String, Object> parcel1After = requireResult(rpcCall(adminToken, "exp_parcel", "find", Map.of("id", parcel1Id)));
        String code1 = (String) parcel1After.get("pickup_code");
        assertThat(code1).isEqualTo("A-1-001");
        assertThat(parcel1After).containsEntry("status", "READY");

        // 货架A已满(capacity=1)，继续上架或停用或缩小容量或删除均拦截
        requireAnyError(rpcCall(adminToken, "exp_parcel", "putOnShelf", Map.of("id", parcel2Id, "shelf_id", shelfAId)), "已满");
        requireAnyError(rpcCall(adminToken, "exp_shelf", "disableShelf", Map.of("id", shelfAId)), "未出库包裹");
        requireAnyError(rpcCall(adminToken, "exp_shelf", "delete", Map.of("id", shelfAId)), "在架包裹");

        // 包裹2上货架B
        requireResult(rpcCall(adminToken, "exp_parcel", "putOnShelf", Map.of("id", parcel2Id, "shelf_id", shelfBId)));
        Map<String, Object> parcel2After = requireResult(rpcCall(adminToken, "exp_parcel", "find", Map.of("id", parcel2Id)));
        String code2 = (String) parcel2After.get("pickup_code");
        assertThat(code2).isEqualTo("B-2-001");

        // 4. 不能出现重复有效的取件码（创建或修改为已占用的有效取件码会被拒绝）
        requireAnyError(rpcCall(adminToken, "exp_parcel", "create", Map.of("values", Map.of(
                "tracking_no", "YTO3000003", "courier_brand", "YTO", "recipient_name", "韦一笑",
                "recipient_phone", "13600136003", "pickup_code", code1))), "重复有效的取件码");

        // 5. 取件出库 -> 自动生成出库单 -> 不能重复出库 -> 取件码释放可复用
        Map<String, Object> pickupRes = requireResult(rpcCall(adminToken, "exp_parcel", "confirmPickup", Map.of("id", parcel1Id)));
        assertThat(String.valueOf(pickupRes.get("message"))).contains("取件出库成功");
        Map<String, Object> parcel1Picked = requireResult(rpcCall(adminToken, "exp_parcel", "find", Map.of("id", parcel1Id)));
        assertThat(parcel1Picked).containsEntry("status", "PICKED");

        // 已取走包裹不能再通过任何途径重复出库（confirmPickup / returnSender / exp_outbound.create）
        requireAnyError(rpcCall(adminToken, "exp_parcel", "confirmPickup", Map.of("id", parcel1Id)), "不能重复出库");
        requireAnyError(rpcCall(adminToken, "exp_parcel", "returnSender", Map.of("id", parcel1Id)), "不能重复出库");
        requireAnyError(rpcCall(adminToken, "exp_outbound", "create", Map.of("values", Map.of(
                "outbound_type", "PICKUP", "parcel_id", parcel1Id))), "严禁重复出库");

        // 货架A已腾空，取件码 code1 已失效可被新包裹安全复用
        Map<String, Object> shelfAReloaded = requireResult(rpcCall(adminToken, "exp_shelf", "find", Map.of("id", shelfAId)));
        assertThat(((Number) shelfAReloaded.get("used_count")).intValue()).isEqualTo(0);
        assertThat(((Number) shelfAReloaded.get("available_count")).intValue()).isEqualTo(1);
        Map<String, Object> parcel3 = requireResult(rpcCall(adminToken, "exp_parcel", "create", Map.of("values", Map.of(
                "tracking_no", "YTO3000003", "courier_brand", "YTO", "recipient_name", "韦一笑",
                "recipient_phone", "13600136003", "shelf_id", shelfAId, "pickup_code", code1))));
        requireResult(rpcCall(adminToken, "exp_parcel", "putOnShelf", Map.of("id", parcel3.get("id"))));

        // 6. 退货出库 & 通过快递出库模型直接办理出库 -> 防重
        requireResult(rpcCall(adminToken, "exp_parcel", "returnSender", Map.of("id", parcel2Id, "reason", "收件人拒收退回")));
        requireAnyError(rpcCall(adminToken, "exp_parcel", "confirmPickup", Map.of("id", parcel2Id)), "不能重复出库");
        requireAnyError(rpcCall(adminToken, "exp_parcel", "returnSender", Map.of("id", parcel2Id)), "不能重复出库");

        Map<String, Object> directOutbound = requireResult(rpcCall(adminToken, "exp_outbound", "create", Map.of("values", Map.of(
                "outbound_type", "PICKUP", "parcel_id", parcel3.get("id"), "reason", "前台直办取件出库"))));
        assertThat((String) directOutbound.get("outbound_no")).startsWith("OUT-");
        requireAnyError(rpcCall(adminToken, "exp_parcel", "confirmPickup", Map.of("id", parcel3.get("id"))), "不能重复出库");

        Map<String, Object> outboundList = requireResult(rpcCall(adminToken, "exp_outbound", "search", Map.of("filter", Collections.emptyList())));
        assertThat(((Number) outboundList.get("total")).intValue()).isEqualTo(3);
        requireAnyError(rpcCall(adminToken, "exp_outbound", "delete", Map.of("id", directOutbound.get("id"))), "不可删除");
    }
}
