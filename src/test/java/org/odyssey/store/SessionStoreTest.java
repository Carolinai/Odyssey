package org.odyssey.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 会话存储测试：核心保证是「服务器重启不丢登录态」——
 * 同一数据目录下新建存储实例（等价于重启）后，原令牌依然有效。
 */
class SessionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 重启后令牌仍有效() {
        SessionStore first = new SessionStore(tempDir);
        String token = first.create("tester", "测试用户", "secret-token");

        // 模拟重启：全新实例读同一份 sessions.json
        SessionStore restarted = new SessionStore(tempDir);
        SessionStore.SessionInfo info = restarted.get(token);
        assertEquals("tester", info.getUser());
        assertEquals("测试用户", info.getUserName());
        assertEquals("secret-token", info.getCredential());
    }

    @Test
    void 注销后令牌失效_且对重启后的实例也失效() {
        SessionStore first = new SessionStore(tempDir);
        String token = first.create("tester", "测试用户", "t");

        first.remove(token);

        assertNull(first.get(token));
        assertNull(new SessionStore(tempDir).get(token));
    }

    @Test
    void 未知或空令牌返回空() {
        SessionStore store = new SessionStore(tempDir);
        assertNull(store.get("no-such-token"));
        assertNull(store.get(null));
        assertNull(store.get(""));
    }

    @Test
    void 空或损坏的会话文件按无会话处理() throws Exception {
        java.nio.file.Files.write(tempDir.resolve("sessions.json"), new byte[0]);

        // 不抛异常，等同未登录；且仍能正常创建新会话
        SessionStore store = new SessionStore(tempDir);
        assertNull(store.get("any-token"));
        String token = store.create("tester", "测试用户", "t");
        assertEquals("tester", store.get(token).getUser());
    }
}
