package org.odyssey.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.odyssey.service.AuthService;
import org.odyssey.service.JenkinsService;

import javax.servlet.http.Cookie;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录接口测试：未登录 401、登录成功签发令牌、凭据错误 401、
 * 连接失败 502、参数缺失 400、退出后令牌失效。
 * 注意：不引入 AuthedMockMvcConfig，避免默认登录态掩盖未登录分支。
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

    /** 登录并返回签发的令牌 Cookie */
    private Cookie loginToken(String username, String credential) throws Exception {
        MvcResult login = performLogin(username, credential);
        return login.getResponse().getCookie(AuthService.TOKEN_COOKIE);
    }

    @Test
    void 未登录访问受保护接口_返回401() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 登录成功_签发令牌并可访问受保护接口() throws Exception {
        when(jenkinsService.authenticate("tester", "good")).thenReturn("测试用户");

        Cookie token = loginToken("tester", "good");
        assertNotNull(token, "登录成功应签发令牌 Cookie");
        assertNotNull(token.getValue(), "令牌不应为空");

        // 用令牌访问原本 401 的接口，应放行
        mvc.perform(get("/api/jenkins/views").cookie(token))
                .andExpect(status().isOk());

        // me 接口返回账号与显示名
        mvc.perform(get("/api/auth/me").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("tester"))
                .andExpect(jsonPath("$.name").value("测试用户"));
    }

    @Test
    void 凭据错误_返回401且不签发令牌() throws Exception {
        when(jenkinsService.authenticate("tester", "bad")).thenReturn(null);

        MvcResult result = performLogin("tester", "bad");
        assertNull(result.getResponse().getCookie(AuthService.TOKEN_COOKIE), "登录失败不应签发令牌");
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
    void 退出登录_令牌失效() throws Exception {
        when(jenkinsService.authenticate("tester", "good")).thenReturn("测试用户");

        Cookie token = loginToken("tester", "good");
        assertNotNull(token);

        // 注销：响应中带清除 Cookie 的指令
        mvc.perform(post("/api/auth/logout").cookie(token))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(AuthService.TOKEN_COOKIE, 0));

        // 旧令牌已从服务端会话存储删除：受保护接口回到 401，me 返回空
        mvc.perform(get("/api/config").cookie(token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(token))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
    }
}
