package org.odyssey.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局配置接口测试：初始为空、保存后读回、保存落盘为 YAML 文件。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("odyssey.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mvc;

    @AfterEach
    void cleanConfigFile() throws Exception {
        // 各用例独立：删掉上一用例落盘的 config.yaml
        Files.deleteIfExists(tempDir.resolve("config.yaml"));
    }

    @Test
    void 初始配置为空() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchers").isEmpty());
    }

    @Test
    void 保存后读回_并落盘YAML() throws Exception {
        mvc.perform(put("/api/config")
                        .contentType("application/json")
                        .content("{\"launchers\":{\"向日葵\":\"sunlogin://\",\"Todesk\":\"todesk://\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchers['向日葵']").value("sunlogin://"))
                .andExpect(jsonPath("$.launchers['Todesk']").value("todesk://"));

        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchers['向日葵']").value("sunlogin://"));

        assertTrue(Files.exists(tempDir.resolve("config.yaml")), "配置应落盘为 config.yaml");
    }

    @Test
    void 空白键值_保存时被剔除() throws Exception {
        mvc.perform(put("/api/config")
                        .contentType("application/json")
                        .content("{\"launchers\":{\"向日葵\":\"sunlogin://\",\"  \":\"x://\",\"坏\":\"  \"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.launchers['向日葵']").value("sunlogin://"))
                .andExpect(jsonPath("$.launchers['坏']").doesNotExist());
    }
}
