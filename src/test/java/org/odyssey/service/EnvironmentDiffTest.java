package org.odyssey.service;

import org.junit.jupiter.api.Test;
import org.odyssey.model.Environment;
import org.odyssey.model.FileInfo;
import org.odyssey.model.EnvBlock;
import org.odyssey.model.Sunlogin;
import org.odyssey.service.EnvironmentDiff.Change;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户信息变更对比测试：逐字段产出变更记录，动作/信息类型/字段/值齐全。
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

    private static List<Change> changes(Environment before, Environment after) {
        return EnvironmentDiff.changes(before, after);
    }

    @Test
    void 整条新增_逐字段产出_带信息类型与值() {
        List<Change> result = changes(null, envWithProdSunlogin("p1"));

        // name / id / password 三条
        assertEquals(3, result.size());
        Change pwd = result.stream().filter(c -> "密码".equals(c.getField())).findFirst().orElseThrow();
        assertEquals("新增", pwd.getAction());
        assertEquals("生产→远程方式→密码", pwd.getModule());
        assertEquals("p1", pwd.getNewValue()); // 按用户要求，密码值明文记录
        Change name = result.stream().filter(c -> "客户名".equals(c.getField())).findFirst().orElseThrow();
        assertEquals("基本信息→客户名", name.getModule());
    }

    @Test
    void 整条删除_逐字段产出_保留原值() {
        List<Change> result = changes(envWithProdSunlogin("p1"), null);

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(c -> "删除".equals(c.getAction())));
        Change pwd = result.stream().filter(c -> "密码".equals(c.getField())).findFirst().orElseThrow();
        assertEquals("p1", pwd.getOldValue());
    }

    @Test
    void 修改_新旧值齐全_动作独立() {
        Environment before = envWithProdSunlogin("old-pass");
        Environment after = envWithProdSunlogin("new-pass");
        after.getEnvironments().get("prod").setQuirks("新增的备注");

        List<Change> result = changes(before, after);
        assertEquals(2, result.size());

        Change pwd = result.stream().filter(c -> "密码".equals(c.getField())).findFirst().orElseThrow();
        assertEquals("修改", pwd.getAction());
        assertEquals("生产→远程方式→密码", pwd.getModule());
        assertEquals("old-pass", pwd.getOldValue());
        assertEquals("new-pass", pwd.getNewValue());

        Change quirks = result.stream().filter(c -> "备注".equals(c.getField())).findFirst().orElseThrow();
        assertEquals("新增", quirks.getAction());
        assertEquals("生产→备注", quirks.getModule());
        assertEquals("新增的备注", quirks.getNewValue());
    }

    @Test
    void 删除的动作独立成条_不再是修改() {
        Environment before = envWithProdSunlogin("p1");
        before.getEnvironments().get("prod").setSunlogin(null);
        // 补一个会被删的测试环境块字段
        Map<String, EnvBlock> envs = new HashMap<>();
        EnvBlock test = new EnvBlock();
        test.setQuirks("测试备注");
        envs.put("test", test);
        before.setEnvironments(new HashMap<>(before.getEnvironments()));
        before.getEnvironments().put("test", test);

        List<Change> result = changes(before, envWithProdSunlogin("p1"));
        // test.quirks 一条：动作=删除、字段=备注、信息类型=测试→备注
        Change quirks = result.stream().filter(c -> "备注".equals(c.getField())).findFirst().orElseThrow();
        assertEquals("删除", quirks.getAction());
        assertEquals("测试→备注", quirks.getModule());
        assertEquals("测试备注", quirks.getOldValue());
    }

    @Test
    void 系统维护字段的时间变化不算变更() {
        Environment before = envWithProdSunlogin("p1");
        before.setCreatedAt("2026-01-01 10:00");
        before.setUpdatedAt("2026-01-01 10:00");
        Environment after = envWithProdSunlogin("p1");
        after.setCreatedAt("2026-01-01 10:00");
        after.setUpdatedAt("2026-01-02 11:00");

        assertTrue(changes(before, after).isEmpty(), "仅 updated_at 变化时无记录");
    }

    @Test
    void 标量数组_一条记录带全量值() {
        Environment before = envWithProdSunlogin("p1");
        before.setAlias(Arrays.asList("别名一", "别名二"));
        Environment after = envWithProdSunlogin("p1");
        after.setAlias(Arrays.asList("别名一", "改名"));

        List<Change> result = changes(before, after);
        assertEquals(1, result.size());
        Change alias = result.get(0);
        assertEquals("修改", alias.getAction());
        assertEquals("基本信息→别名", alias.getModule());
        assertEquals("别名一、别名二", alias.getOldValue());
        assertEquals("别名一、改名", alias.getNewValue());
    }

    @Test
    void 对象数组_整元素增删下钻到字段() {
        Environment before = envWithProdSunlogin("p1");
        afterAddTag(before, "重点客户");
        Environment after = envWithProdSunlogin("p1");
        afterAddTag(after, "重点客户");
        afterAddTag(after, "新客户");

        List<Change> result = changes(before, after);
        assertEquals(2, result.size()); // 新客户的 name + color
        assertTrue(result.stream().allMatch(c -> "新增".equals(c.getAction())));
        assertTrue(result.stream().anyMatch(c -> "名称".equals(c.getField()) && "新客户".equals(c.getNewValue())));
    }

    @Test
    void 删除描述_附件数() {
        Environment env = envWithProdSunlogin("p1");
        FileInfo f1 = new FileInfo();
        f1.setName("a.txt");
        FileInfo f2 = new FileInfo();
        f2.setName("b.txt");
        env.setFiles(new ArrayList<>(Arrays.asList(f1, f2)));

        // 整条删除时附件逐文件下钻：每个文件含 name 元数据字段
        List<Change> result = changes(env, null);
        assertTrue(result.size() >= 3);
        assertTrue(result.stream().anyMatch(c -> "客户名".equals(c.getField()) && "XX银行".equals(c.getOldValue())));
    }

    private static void afterAddTag(Environment env, String tagName) {
        org.odyssey.model.Tag tag = new org.odyssey.model.Tag();
        tag.setName(tagName);
        tag.setColor("#e74c3c");
        if (env.getTags() == null) {
            env.setTags(new ArrayList<>());
        }
        env.getTags().add(tag);
    }
}
