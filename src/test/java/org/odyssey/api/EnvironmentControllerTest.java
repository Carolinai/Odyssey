package org.odyssey.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import org.odyssey.config.AuthedMockMvcConfig;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口测试：临时数据目录，覆盖搜索/CRUD/404/409 分支。
 * 每个用例使用独立客户名，避免用例间数据残留互相影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthedMockMvcConfig.class)
class EnvironmentControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("odyssey.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mvc;

    private static final String FULL_ENV_JSON = "{"
            + "\"name\": \"%s\","
            + "\"alias\": [\"XX行\", \"某行生产\"],"
            + "\"tags\": [{\"name\": \"重点客户\", \"color\": \"#e74c3c\"}],"
            + "\"environments\": {"
            + "  \"prod\": {"
            + "    \"access\": [{\"method\": \"ssh\", \"target\": \"应用服务器 10.0.1.10\"}],"
            + "    \"admin_account\": {\"user\": \"admin\", \"password\": \"p1\"}"
            + "  },"
            + "  \"test\": {\"quirks\": \"测试环境备注\"}"
            + "}"
            + "}";

    private String createEnv(String name) throws Exception {
        return mvc.perform(post("/api/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(FULL_ENV_JSON, name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @AfterEach
    void cleanDataDir() throws Exception {
        // 兜底清理，正常情况下各用例数据互相独立
        Path data = tempDir.resolve("data");
        if (!Files.exists(data)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(data)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void 新建后可查询_摘要含name_alias_tags() throws Exception {
        createEnv("摘要银行");

        mvc.perform(get("/api/environments?kw=摘要银行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("摘要银行"))
                .andExpect(jsonPath("$[0].alias[0]").value("XX行"))
                .andExpect(jsonPath("$[0].tags[0].name").value("重点客户"))
                .andExpect(jsonPath("$[0].tags[0].color").value("#e74c3c"));
    }

    @Test
    void 按别名模糊搜索命中() throws Exception {
        createEnv("别名银行");

        mvc.perform(get("/api/environments?kw=某行生产"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("别名银行"));
    }

    @Test
    void 按标签名模糊搜索命中() throws Exception {
        createEnv("标签银行");

        mvc.perform(get("/api/environments?kw=重点客户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("标签银行"));
    }

    @Test
    void 无命中时返回空列表() throws Exception {
        mvc.perform(get("/api/environments?kw=不存在关键词xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void 详情返回完整数据_含环境块() throws Exception {
        createEnv("详情银行");

        mvc.perform(get("/api/environments/详情银行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("详情银行"))
                .andExpect(jsonPath("$.environments.prod.access[0].method").value("ssh"))
                .andExpect(jsonPath("$.environments.test.quirks").value("测试环境备注"))
                .andExpect(jsonPath("$.environments.validation").doesNotExist());
    }

    @Test
    void 新建重名_返回409() throws Exception {
        createEnv("重名银行");

        mvc.perform(post("/api/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(FULL_ENV_JSON, "重名银行")))
                .andExpect(status().isConflict());
    }

    @Test
    void 查询不存在的客户_返回404() throws Exception {
        mvc.perform(get("/api/environments/不存在"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 修改以路径参数为准_并写回() throws Exception {
        createEnv("修改银行");

        mvc.perform(put("/api/environments/修改银行")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"试图改名\", \"environments\": {\"prod\": {\"quirks\": \"改后的坑\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("修改银行"))
                .andExpect(jsonPath("$.environments.prod.quirks").value("改后的坑"));
    }

    @Test
    void 删除后查询返回404() throws Exception {
        createEnv("删除银行");

        mvc.perform(delete("/api/environments/删除银行"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/environments/删除银行"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 附件上传后可下载_删除后消失() throws Exception {
        createEnv("附件银行");

        // 上传（文件名含中文）
        String resp = mvc.perform(multipart("/api/environments/附件银行/files")
                        .file(new MockMultipartFile("file", "VPN客户端.exe",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, "hello-vpn".getBytes(StandardCharsets.UTF_8)))
                        .param("note", "VPN 软件"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("VPN客户端.exe"))
                .andExpect(jsonPath("$.note").value("VPN 软件"))
                .andExpect(jsonPath("$.size").value(9))
                .andExpect(jsonPath("$.stored_name").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String stored = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resp).get("stored_name").asText();

        // 详情里能看到附件元数据
        mvc.perform(get("/api/environments/附件银行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].name").value("VPN客户端.exe"));

        // 下载：内容与原始文件名一致
        mvc.perform(get("/api/environments/附件银行/files/" + stored))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals(
                        "hello-vpn", result.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        // 非 ASCII 文件名按 RFC 5987 编码为 filename*=UTF-8''...，此处断言下载头与扩展名
                        result.getResponse().getHeader("Content-Disposition").contains("attachment")
                                && result.getResponse().getHeader("Content-Disposition").contains(".exe")));

        // 删除后详情中不再出现
        mvc.perform(delete("/api/environments/附件银行/files/" + stored))
                .andExpect(status().isOk());
        mvc.perform(get("/api/environments/附件银行"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files").isEmpty());
    }

    @Test
    void 给不存在的客户上传附件_返回404() throws Exception {
        mvc.perform(multipart("/api/environments/不存在/files")
                        .file(new MockMultipartFile("file", "a.txt",
                                MediaType.TEXT_PLAIN_VALUE, "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 增改删均记录审计日志_倒序返回() throws Exception {
        createEnv("审计甲银行");

        mvc.perform(put("/api/environments/审计甲银行")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"审计甲银行\", \"environments\": {\"prod\": {\"quirks\": \"改后备注\"}}}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/environments/审计甲银行"))
                .andExpect(status().isOk());

        String resp = mvc.perform(get("/api/audit?target_name=审计甲银行"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode logs =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp);
        assertEquals(3, logs.size(), "增改删各记一条");
        assertEquals("删除", logs.get(0).get("event").asText());
        assertEquals("修改", logs.get(1).get("event").asText());
        assertTrue(logs.get(1).get("description").asText().contains("备注"), "修改描述应含变更字段");
        assertEquals("新增", logs.get(2).get("event").asText());
        // 字段维度：账号 / 用户名称 / 操作对象 / 对象名称
        assertEquals("tester", logs.get(2).get("user").asText());
        assertEquals("测试用户", logs.get(2).get("user_name").asText());
        assertEquals("客户信息", logs.get(2).get("target").asText());
        assertEquals("审计甲银行", logs.get(2).get("target_name").asText());
        assertTrue(logs.get(2).has("time"), "应含操作时间");
    }

    @Test
    void 无差异的保存不记审计日志() throws Exception {
        createEnv("审计乙银行");
        // 原样保存一遍（前端提交全量数据、内容未变的场景）
        mvc.perform(put("/api/environments/审计乙银行")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(FULL_ENV_JSON, "审计乙银行")))
                .andExpect(status().isOk());

        String resp = mvc.perform(get("/api/audit?target_name=审计乙银行"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode logs =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp);
        assertEquals(1, logs.size(), "只有创建一条，无差异保存不记审计");
    }
}
