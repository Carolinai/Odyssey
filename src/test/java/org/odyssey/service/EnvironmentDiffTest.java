package org.odyssey.service;

import org.junit.jupiter.api.Test;
import org.odyssey.model.Environment;
import org.odyssey.model.FileInfo;
import org.odyssey.model.EnvBlock;
import org.odyssey.model.Sunlogin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户信息变更对比测试：新增/删除描述、细粒度修改路径、敏感值不入描述。
 */
class EnvironmentDiffTest {

    private static Environment envWithProdSunlogin(String password) {
        Environment env = new Environment();
        env.setName("XX银行");
        Sunlogin sunlogin = new Sunlogin();
        sunlogin.setId("123456");
        sunlogin.setPassword(password);
        EnvBlock prod = new EnvBlock();
        prod.setSunlogin(sunlogin);
        Map<String, EnvBlock> envs = new HashMap<>();
        envs.put("prod", prod);
        env.setEnvironments(envs);
        return env;
    }

    @Test
    void 新增描述_列出顶层内容() {
        Environment env = envWithProdSunlogin("p");
        env.setAlias(Arrays.asList("某行"));

        assertEquals("创建客户「XX银行」：别名、生产环境", EnvironmentDiff.describeCreate(env));
    }

    @Test
    void 删除描述_含附件数() {
        Environment env = envWithProdSunlogin("p");
        FileInfo f1 = new FileInfo();
        f1.setName("a.txt");
        FileInfo f2 = new FileInfo();
        f2.setName("b.txt");
        env.setFiles(new java.util.ArrayList<>(Arrays.asList(f1, f2)));

        assertEquals("删除客户「XX银行」（含 2 个附件）", EnvironmentDiff.describeDelete(env));
    }

    @Test
    void 修改描述_嵌套路径翻译成中文() {
        Environment before = envWithProdSunlogin("old-pass");
        Environment after = envWithProdSunlogin("new-pass");
        after.getEnvironments().get("prod").setQuirks("新增的备注");

        String description = EnvironmentDiff.describeChanges(before, after);
        assertTrue(description.startsWith("修改："), "描述应以「修改：」开头");
        assertTrue(description.contains("生产→向日葵→密码"), "应含密码字段路径：" + description);
        assertTrue(description.contains("生产→备注（新增）"), "应含新增字段路径：" + description);
    }

    @Test
    void 修改描述_不含字段值_密码不泄露() {
        Environment before = envWithProdSunlogin("old-pass");
        Environment after = envWithProdSunlogin("new-pass");

        String description = EnvironmentDiff.describeChanges(before, after);
        assertFalse(description.contains("old-pass"), "旧值不应入日志");
        assertFalse(description.contains("new-pass"), "新值不应入日志");
    }

    @Test
    void 系统维护字段的时间变化不算修改() {
        Environment before = envWithProdSunlogin("p");
        before.setCreatedAt("2026-01-01 10:00");
        before.setUpdatedAt("2026-01-01 10:00");
        Environment after = envWithProdSunlogin("p");
        after.setCreatedAt("2026-01-01 10:00");
        after.setUpdatedAt("2026-01-02 11:00");

        assertNull(EnvironmentDiff.describeChanges(before, after), "仅 updated_at 变化时无差异");
    }

    @Test
    void 标量数组整体变化_只记路径不带下标() {
        Environment before = envWithProdSunlogin("p");
        before.setAlias(Arrays.asList("别名一", "别名二"));
        Environment after = envWithProdSunlogin("p");
        after.setAlias(Arrays.asList("别名一", "改名"));

        assertEquals("修改：别名", EnvironmentDiff.describeChanges(before, after));
    }

    @Test
    void 对象数组_条目增删有方向() {
        Environment before = envWithProdSunlogin("p");
        before.setTags(new java.util.ArrayList<>(Collections.singletonList(
                tag("重点客户"))));
        Environment after = envWithProdSunlogin("p");
        after.setTags(new java.util.ArrayList<>(Arrays.asList(
                tag("重点客户"), tag("新客户"))));

        assertEquals("修改：标签（新增条目）", EnvironmentDiff.describeChanges(before, after));
    }

    private static org.odyssey.model.Tag tag(String name) {
        org.odyssey.model.Tag t = new org.odyssey.model.Tag();
        t.setName(name);
        t.setColor("#e74c3c");
        return t;
    }
}
