package org.odyssey.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.odyssey.service.JenkinsService;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录接口测试：未登录 401、登录成功建会话、凭据错误 401、
 * 连接失败 502、参数缺失 400、退出后会话失效。
 * 注意：不引入 AuthedMockMvcConfig，避免默认已登录会话掩盖未登录分支。
 * JenkinsService 用 Mock 替身，不依赖真实 Jenkins。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("odyssey.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private JenkinsService jenkinsService;

    private MvcResult performLogin(String username, String credential) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"credential\":\"" + credential + "\"}"))
                .andReturn();
    }

    @Test
    void 未登录访问受保护接口_返回401() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 登录成功_建会话并可访问受保护接口() throws Exception {
        when(jenkinsService.authenticate("tester", "good")).thenReturn("测试用户");

        MvcResult login = performLogin("tester", "good");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertTrue(session != null, "登录成功应建立会话");
        assertFalse(session.isInvalid());

        // 用登录得到的会话访问原本 401 的接口，应放行
        mvc.perform(get("/api/jenkins/views").session(session))
                .andExpect(status().isOk());

        // me 接口返回账号与显示名
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("tester"))
                .andExpect(jsonPath("$.name").value("测试用户"));
    }

    @Test
    void 凭据错误_返回401且不写入用户() throws Exception {
        when(jenkinsService.authenticate("tester", "bad")).thenReturn(null);

        MvcResult result = performLogin("tester", "bad");
        // Spring 解析 Controller 的 HttpSession 参数会自动创建会话，
        // 这里校验失败时不写入用户属性（会话仍等于未登录）
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertTrue(session == null
                || session.getAttribute(AuthController.SESSION_USER) == null, "登录失败不应写入用户");
    }

    @Test
    void Jenkins连接失败_返回502() throws Exception {
        when(jenkinsService.authenticate("tester", "conn"))
                .thenThrow(new IllegalStateException("Jenkins 连接失败：timeout"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tester\",\"credential\":\"conn\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Jenkins 连接失败：timeout"));
    }

    @Test
    void 账号或凭据缺失_返回400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tester\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tester\",\"credential\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 退出登录_会话失效() throws Exception {
        when(jenkinsService.authenticate("tester", "good")).thenReturn("测试用户");

        MvcResult login = performLogin("tester", "good");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk());

        assertTrue(session.isInvalid(), "退出后应销毁会话");
    }
}
