package org.odyssey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.odyssey.model.OnesTicket;
import org.odyssey.model.OnesTaskMessages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OnesClient 真机联调（访问内网 ONES）。
 *
 * 默认跳过；联调时运行：
 *   mvn test -Dtest=OnesClientLiveTest -Dones.live=true
 * Cookie 从本机 ~/.codex/.ones-mcp.json 读取（无该文件时自动跳过）。
 */
class OnesClientLiveTest {

    @Test
    void 联调_拉取近两年工单_动态流_用户表() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ones.live"), "未开启联调开关 -Dones.live=true");
        Path mcp = Paths.get(System.getProperty("user.home"), ".codex", ".ones-mcp.json");
        Assumptions.assumeTrue(Files.exists(mcp), "本机无 ~/.codex/.ones-mcp.json，跳过联调");
        JsonNode cfg = new ObjectMapper().readTree(Files.readAllBytes(mcp));
        Assumptions.assumeTrue(cfg.hasNonNull("cookie") && !cfg.get("cookie").asText().isEmpty(), "凭证文件无 Cookie");

        String cookie = cfg.get("cookie").asText();
        OnesClient client = new OnesClient();

        // 近两年工单全量（调研实测 7791 条，量级断言放宽）
        long cutoff = System.currentTimeMillis() - 2L * 365 * 24 * 3600 * 1000;
        List<OnesTicket> tickets = client.fetchTicketsCreatedSince(cookie, cutoff);
        assertTrue(tickets.size() > 7000 && tickets.size() < 9000,
                "近两年工单量级不符：" + tickets.size());

        long withCustomer = tickets.stream().filter(t -> t.getCustomerName() != null).count();
        assertTrue(withCustomer > tickets.size() / 2, "有客户字段的工单过少：" + withCustomer);
        System.out.println("[联调] 近两年工单 " + tickets.size() + " 条，其中有客户字段 " + withCustomer + " 条");

        // 动态流接口可用（结构与解析无异常）
        OnesTaskMessages messages = client.fetchMessages(cookie, tickets.get(0).getUuid());
        System.out.println("[联调] 样例工单 #" + tickets.get(0).getNumber()
                + "：评论 " + messages.getComments().size() + " 条，历史 " + messages.getHistories().size() + " 条");

        // 用户表可用
        assertFalse(client.fetchUserNames(cookie).isEmpty(), "用户表不应为空");
        assertEquals(tickets.size(), tickets.stream().filter(t -> t.getUuid() != null).count(),
                "全部工单应有 uuid");
    }
}
