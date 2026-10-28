package org.odyssey.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** EnvironmentStore 单元测试：临时目录，不依赖 Spring 上下文 */
class EnvironmentStoreTest {

    @TempDir
    Path tempDir;

    private EnvironmentStore store;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @BeforeEach
    void setUp() {
        store = new EnvironmentStore(tempDir.resolve("data"));
    }

    /** 构造一个全字段样例客户（客户级信息 + 测试/生产两个环境块） */
    private Environment sampleCustomer() {
        Environment env = new Environment();
        env.setName("某某银行");
        env.setAlias(Arrays.asList("XX行", "某行生产"));
        env.setTags(Arrays.asList(new Tag("重点客户", "#e74c3c"), new Tag("有VPN", "#2980b9")));

        Map<String, EnvBlock> envs = new LinkedHashMap<>();

        EnvBlock test = new EnvBlock();
        Vpn vpn = new Vpn();
        vpn.setHow("深信服 SSL VPN");
        vpn.setEntry("vpn.x.com:443");
        Account vpnAccount = new Account();
        vpnAccount.setUser("xxx");
        vpnAccount.setPassword("xxx");
        vpnAccount.setOtp("需要找张三要动态码");
        vpn.setAccount(vpnAccount);
        vpn.setNotes("仅公司内网可拨");
        test.setVpn(vpn);
        test.setQuirks("测试环境部署在 /data/app");
        envs.put("test", test);

        EnvBlock prod = new EnvBlock();
        Bastion bastion = new Bastion();
        bastion.setType("JumpServer");
        bastion.setEntry("https://x.x.x.x");
        Account bastionAccount = new Account();
        bastionAccount.setUser("xxx");
        bastionAccount.setPassword("xxx");
        bastion.setAccount(bastionAccount);
        prod.setBastion(bastion);

        Sunlogin sunlogin = new Sunlogin();
        sunlogin.setId("123456789");
        sunlogin.setPassword("xxx");
        prod.setSunlogin(sunlogin);

        ClientInfo app = new ClientInfo();
        app.setName("app");
        app.setIp("10.0.1.10");
        Account appAccount = new Account();
        appAccount.setUser("root");
        appAccount.setPassword("xxx");
        app.setAccount(appAccount);
        ClientInfo docService = new ClientInfo();
        docService.setName("文档服务");
        docService.setIp("10.0.1.12");
        prod.setClients(Arrays.asList(app, docService));

        AccessEntry ssh = new AccessEntry();
        ssh.setMethod("ssh");
        ssh.setTarget("应用服务器 10.0.1.10");
        ssh.setVia("先 ssh 10.0.1.2 再跳 10.0.1.10");
        Account sshAccount = new Account();
        sshAccount.setUser("root");
        sshAccount.setPassword("xxx");
        sshAccount.setKey("~/.ssh/id_rsa_xx");
        ssh.setAccount(sshAccount);
        prod.setAccess(Arrays.asList(ssh));

        AdminAccount admin = new AdminAccount();
        admin.setUser("admin");
        admin.setPassword("xxx");
        Recovery recovery = new Recovery();
        recovery.setLookup("sql@10.0.1.10");
        recovery.setSql("select password from t_admin where name='admin'");
        recovery.setKeyFile("keys/某某银行.pem");
        admin.setRecovery(recovery);
        prod.setAdminAccount(admin);

        CodeEntry code = new CodeEntry();
        code.setProject("vp_controller");
        code.setBranch("release/v2.3");
        prod.setCode(Arrays.asList(code));

        prod.setQuirks("重启要用特定脚本");
        envs.put("prod", prod);

        env.setEnvironments(envs);

        // 客户级附件（VPN 软件、使用文档等）
        FileInfo file = new FileInfo();
        file.setName("VPN客户端.exe");
        file.setStoredName("uuid-1");
        file.setSize(123);
        file.setUploadedAt("2026-09-28 10:00");
        file.setNote("VPN 软件");
        env.setFiles(Arrays.asList(file));
        return env;
    }

    @Test
    void 序列化往返_全字段嵌套结构无损() throws Exception {
        Environment env = sampleCustomer();
        store.create(env);

        Environment loaded = store.get("某某银行");

        // 通过 YAML 树对比，避免为 POJO 实现 equals；
        // created_at/updated_at 由存储层在 create 时生成并回填到 env（同一引用），对比前两侧都剔除
        JsonNode before = yamlMapper.readTree(yamlMapper.writeValueAsString(env));
        JsonNode after = yamlMapper.readTree(yamlMapper.writeValueAsString(loaded));
        ((ObjectNode) before).remove("created_at");
        ((ObjectNode) before).remove("updated_at");
        ((ObjectNode) after).remove("created_at");
        ((ObjectNode) after).remove("updated_at");
        assertEquals(before, after);
    }

    @Test
    void 省略的段落_序列化时不输出() throws Exception {
        Environment env = new Environment();
        env.setName("极简客户");

        store.create(env);
        String yaml = new String(
                Files.readAllBytes(tempDir.resolve("data").resolve("极简客户.yaml")),
                "UTF-8");

        assertFalse(yaml.contains("alias"));
        assertFalse(yaml.contains("tags"));
        assertFalse(yaml.contains("environments"));
        assertTrue(yaml.contains("name: 极简客户"));
    }

    @Test
    void 中文蛇形字段名正确映射() throws Exception {
        store.create(sampleCustomer());
        String yaml = new String(
                Files.readAllBytes(tempDir.resolve("data").resolve("某某银行.yaml")),
                "UTF-8");

        assertTrue(yaml.contains("environments:"));
        assertTrue(yaml.contains("key_file: keys/某某银行.pem"));
        assertTrue(yaml.contains("clients:"));
        assertTrue(yaml.contains("文档服务"));
        // 只配置了 test/prod，validation 不落盘
        assertFalse(yaml.contains("validation"));
    }

    @Test
    void 新建重名客户_拒绝() {
        store.create(sampleCustomer());

        assertThrows(DuplicateEnvironmentException.class, () -> store.create(sampleCustomer()));
    }

    @Test
    void 读取不存在的客户_抛异常() {
        assertThrows(EnvironmentNotFoundException.class, () -> store.get("不存在"));
    }

    @Test
    void 更新客户_内容覆盖写回() {
        store.create(sampleCustomer());

        Environment updated = sampleCustomer();
        updated.getEnvironments().get("prod").setQuirks("新的坑说明");
        store.update(updated);

        assertEquals("新的坑说明", store.get("某某银行").getEnvironments().get("prod").getQuirks());
    }

    @Test
    void 更新不存在的客户_抛异常() {
        assertThrows(EnvironmentNotFoundException.class, () -> store.update(sampleCustomer()));
    }

    @Test
    void 删除客户_文件移除后再读取报错() {
        store.create(sampleCustomer());
        assertTrue(store.exists("某某银行"));

        store.delete("某某银行");

        assertFalse(store.exists("某某银行"));
        assertThrows(EnvironmentNotFoundException.class, () -> store.get("某某银行"));
    }

    @Test
    void 列表_返回全部客户且按更新时间倒序() throws Exception {
        Environment a = new Environment();
        a.setName("B银行");
        Environment b = sampleCustomer(); // 某某银行
        store.create(a);
        store.create(b);

        // 直接改写 B银行 的 YAML，把更新时间调晚，列表应排最前
        Path file = tempDir.resolve("data").resolve("B银行.yaml");
        Environment saved = yamlMapper.readValue(file.toFile(), Environment.class);
        saved.setUpdatedAt("2099-01-01 00:00");
        yamlMapper.writeValue(file.toFile(), saved);

        assertEquals(2, store.list().size());
        assertEquals("B银行", store.list().get(0).getName());
        assertEquals("某某银行", store.list().get(1).getName());
    }

    @Test
    void 新建客户_自动写入创建与更新时间() {
        Environment env = new Environment();
        env.setName("某新客户");
        store.create(env);

        Environment saved = store.get("某新客户");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void 更新客户_更新时间被刷新且创建时间保留() {
        Environment env = new Environment();
        env.setName("某新客户");
        store.create(env);
        String createdAt = store.get("某新客户").getCreatedAt();

        Environment changed = new Environment();
        changed.setName("某新客户");
        store.update(changed);

        Environment saved = store.get("某新客户");
        assertEquals(createdAt, saved.getCreatedAt()); // 创建时间不变
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void 回填_给缺时间戳的旧文件补创建与更新时间() throws Exception {
        // 手工放一个不带时间戳的旧格式文件（模拟历史数据）；data 目录可能尚未创建，先建好
        Environment old = new Environment();
        old.setName("老客户");
        Files.createDirectories(tempDir.resolve("data"));
        yamlMapper.writeValue(tempDir.resolve("data").resolve("老客户.yaml").toFile(), old);

        store.backfillMissingTimestamps();

        Environment saved = store.get("老客户");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void 非法客户名_拒绝创建() {
        Environment env = new Environment();
        env.setName("a/b"); // 含路径分隔符

        assertThrows(IllegalArgumentException.class, () -> store.create(env));
    }
}
