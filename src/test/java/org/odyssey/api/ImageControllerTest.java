package org.odyssey.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块截图接口测试：上传/读取/参数校验，以及保存环境时的孤儿图片清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImageControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("odyssey.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mvc;

    private Path imagesDir() {
        return tempDir.resolve("data").resolve("images");
    }

    /** 上传一张 PNG（最小合法 PNG 头即可，接口只看扩展名与大小） */
    private String uploadPng() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", "截图.png", "image/png",
                                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}))
                        .characterEncoding(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.replaceAll(".*\"name\":\"([^\"]+)\".*", "$1");
    }

    @AfterEach
    void cleanDataDir() throws Exception {
        Path data = tempDir.resolve("data");
        if (!Files.exists(data)) {
            return;
        }
        try (Stream<Path> files = Files.list(data)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .forEach(p -> p.toFile().delete());
        }
        Path images = imagesDir();
        if (Files.exists(images)) {
            try (Stream<Path> files = Files.list(images)) {
                files.forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void 上传图片_落盘并返回文件名() throws Exception {
        String name = uploadPng();
        assertTrue(name.endsWith(".png"));
        assertTrue(Files.exists(imagesDir().resolve(name)));
    }

    @Test
    void 读取图片_按扩展名返回类型() throws Exception {
        String name = uploadPng();
        mvc.perform(get("/api/images/" + name))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result
                        .MockMvcResultMatchers.content().contentType("image/png"));
    }

    @Test
    void 上传不支持的扩展名_400() throws Exception {
        mvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", "文档.pdf", "application/pdf", new byte[]{1})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 上传超过10MB_400() throws Exception {
        mvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", "big.png", "image/png", new byte[10 * 1024 * 1024 + 1])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 读取非法文件名_400() throws Exception {
        // 路径穿越类文件名必须被文件名白名单挡下
        mvc.perform(get("/api/images/..%2F..%2Fsecret.png"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 读取不存在的图片_404() throws Exception {
        mvc.perform(get("/api/images/00000000-0000-0000-0000-000000000000.png"))
                .andExpect(status().isNotFound());
    }

    /** 先创建客户再更新：update 接口不允许新建（不存在即 404） */
    private void createEnv(String name) throws Exception {
        mvc.perform(post("/api/environments")
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk());
    }

    /** 把图片的修改时间拨回两天前，模拟超过豁免期（24h）的文件 */
    private void ageBeyondGrace(String name) throws Exception {
        Files.setLastModifiedTime(imagesDir().resolve(name),
                FileTime.fromMillis(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000));
    }

    @Test
    void 保存环境时清理未被引用的图片() throws Exception {
        String kept = uploadPng();    // 会被环境引用，应保留
        String orphan = uploadPng();  // 无任何引用且超过豁免期，应被清理
        ageBeyondGrace(orphan);

        createEnv("客户A");
        mvc.perform(put("/api/environments/客户A")
                        .contentType("application/json")
                        .content("{\"name\":\"客户A\",\"environments\":{\"test\":{\"vpn\":"
                                + "{\"how\":\"深信服\",\"images\":[\"" + kept + "\"]}}}}"))
                .andExpect(status().isOk());

        assertTrue(Files.exists(imagesDir().resolve(kept)), "被引用的图片不应被清理");
        assertFalse(Files.exists(imagesDir().resolve(orphan)), "超过豁免期且无引用的图片应被清理");

        // 刚上传、还没随环境保存的图片处于豁免期内，即使暂时无引用也不能被清理
        String fresh = uploadPng();
        mvc.perform(put("/api/environments/客户A")
                        .contentType("application/json")
                        .content("{\"name\":\"客户A\",\"environments\":{\"test\":{\"vpn\":"
                                + "{\"how\":\"深信服\",\"images\":[\"" + kept + "\"]}}}}"))
                .andExpect(status().isOk());
        assertTrue(Files.exists(imagesDir().resolve(fresh)), "豁免期内的新图片不应被清理");
    }

    @Test
    void 删除环境后其独占图片被清理_共享引用的保留() throws Exception {
        // 先建客户再上传：create 也会触发清理，避免上传的老图在建立引用前被回收
        createEnv("客户A");
        createEnv("客户B");
        String shared = uploadPng();
        String exclusive = uploadPng();
        ageBeyondGrace(shared);
        ageBeyondGrace(exclusive);

        // 两个客户都引用 shared；客户A 独占 exclusive
        mvc.perform(put("/api/environments/客户A")
                        .contentType("application/json")
                        .content("{\"name\":\"客户A\",\"environments\":{\"test\":{\"bastion\":"
                                + "{\"type\":\"JumpServer\",\"images\":[\"" + shared + "\",\"" + exclusive + "\"]}}}}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/environments/客户B")
                        .contentType("application/json")
                        .content("{\"name\":\"客户B\",\"environments\":{\"test\":{\"sunlogin\":"
                                + "{\"type\":\"向日葵\",\"images\":[\"" + shared + "\"]}}}}"))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/environments/客户A"))
                .andExpect(status().isOk());

        assertTrue(Files.exists(imagesDir().resolve(shared)), "被其他客户共享引用的图片应保留");
        assertFalse(Files.exists(imagesDir().resolve(exclusive)), "删除客户独占的图片应被清理");
        try (Stream<Path> files = Files.list(imagesDir())) {
            assertEquals(1, files.count());
        }
    }
}
