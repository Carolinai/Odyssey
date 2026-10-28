package org.odyssey.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.odyssey.model.ClientInfo;
import org.odyssey.model.EnvBlock;
import org.odyssey.model.Environment;
import org.odyssey.model.Sunlogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 环境数据存储层：一个环境一个 YAML 文件（文件名 = name + .yaml）。
 * name 是搜索主键且唯一；目录不存在时自动创建。
 */
public class EnvironmentStore {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentStore.class);

    /** name 作为文件名，禁止文件系统特殊字符与路径分隔符 */
    private static final Pattern INVALID_NAME = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    private static final String EXTENSION = ".yaml";

    /** 孤儿图片豁免期：前端是「选中即上传、随保存提交引用」，上传与保存之间存在时间差，
     *  期间文件暂时无引用属正常状态；仅清理超过该时长仍无引用的文件，避免误删刚上传的图 */
    private static final long IMAGE_GRACE_MILLIS = 24 * 60 * 60 * 1000L;

    private final Path dataDir;
    private final ObjectMapper mapper;

    public EnvironmentStore(Path dataDir) {
        this.dataDir = dataDir;
        // MINIMIZE_QUOTES：字符串不加引号，生成的 YAML 更适合人工阅读与 git diff；
        // 看似数字/布尔的字符串 Jackson 会自动加引号保证读回仍是字符串
        this.mapper = new ObjectMapper(
                new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    }

    /** 列出全部环境（按更新时间倒序；无 updated_at 的记录视为最早排在最后，同刻再按文件名排） */
    public List<Environment> list() {
        ensureDataDir();
        List<Environment> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*" + EXTENSION)) {
            for (Path file : stream) {
                result.add(mapper.readValue(file.toFile(), Environment.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取环境目录失败：" + dataDir, e);
        }
        // 时间格式为 yyyy-MM-dd HH:mm，字符串比较即时间比较；nullsFirst 升序后倒转 → 无时间的记录排最后
        result.sort(Comparator
                .comparing(Environment::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Environment::getName, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        return result;
    }

    /** 按 name 读取，不存在时抛 EnvironmentNotFoundException */
    public Environment get(String name) {
        Path file = fileOf(name);
        if (!Files.exists(file)) {
            throw new EnvironmentNotFoundException(name);
        }
        try {
            return mapper.readValue(file.toFile(), Environment.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取环境文件失败：" + file, e);
        }
    }

    public boolean exists(String name) {
        return Files.exists(fileOf(name));
    }

    /** 新建环境；name 已存在时抛 DuplicateEnvironmentException。创建/更新时间在此统一生成 */
    public void create(Environment env) {
        validate(env);
        if (exists(env.getName())) {
            throw new DuplicateEnvironmentException(env.getName());
        }
        if (isBlank(env.getCreatedAt())) {
            env.setCreatedAt(now());
        }
        env.setUpdatedAt(now());
        write(env);
        cleanOrphanImages();
    }

    /** 更新环境；不存在时抛 EnvironmentNotFoundException（name 不可改，改 name 需删除重建）。
     *  createdAt 保持原值；updatedAt 每次保存刷新 */
    public void update(Environment env) {
        validate(env);
        if (!exists(env.getName())) {
            throw new EnvironmentNotFoundException(env.getName());
        }
        if (isBlank(env.getCreatedAt())) {
            // 兼容缺 created_at 的旧记录：从库中读回原值，避免被清掉
            try {
                env.setCreatedAt(mapper.readValue(fileOf(env.getName()).toFile(), Environment.class).getCreatedAt());
            } catch (IOException e) {
                throw new UncheckedIOException("读取环境文件失败：" + env.getName(), e);
            }
        }
        env.setUpdatedAt(now());
        write(env);
        cleanOrphanImages();
    }

    /** 启动回填：给缺 created_at / updated_at 的旧 YAML 补时间。
     *  created_at 取文件系统的创建时间（Windows NTFS 记录真实创建时刻）；updated_at 暂与 created_at 相同 */
    public void backfillMissingTimestamps() {
        ensureDataDir();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        int fixed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*" + EXTENSION)) {
            for (Path file : stream) {
                Environment env = mapper.readValue(file.toFile(), Environment.class);
                if (!isBlank(env.getCreatedAt()) && !isBlank(env.getUpdatedAt())) {
                    continue;
                }
                // 必须在重写文件前取创建时间：write 用临时文件替换，会重置文件的创建时间
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                String created = fmt.format(LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault()));
                if (isBlank(env.getCreatedAt())) {
                    env.setCreatedAt(created);
                }
                if (isBlank(env.getUpdatedAt())) {
                    env.setUpdatedAt(env.getCreatedAt());
                }
                write(env);
                fixed++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("回填时间戳失败：" + dataDir, e);
        }
        if (fixed > 0) {
            log.info("已为 {} 个旧环境文件回填创建/更新时间", fixed);
        }
    }

    /** 启动清洗：移除只有远程方式（type）、没有实际连接信息的 sunlogin 数据。
     *  早期前端默认填充方式字段遗留的脏数据；连接码/密码/备注全空即视为脏，整个 sunlogin 节点移除 */
    public void cleanupRemoteTypeOnlySunlogin() {
        ensureDataDir();
        int cleaned = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*" + EXTENSION)) {
            for (Path file : stream) {
                Environment env = mapper.readValue(file.toFile(), Environment.class);
                if (env.getEnvironments() == null) {
                    continue;
                }
                boolean changed = false;
                for (EnvBlock block : env.getEnvironments().values()) {
                    Sunlogin sl = block.getSunlogin();
                    if (sl == null) {
                        continue;
                    }
                    if (isBlank(sl.getId()) && isBlank(sl.getPassword()) && isBlank(sl.getNotes())) {
                        block.setSunlogin(null); // NON_NULL 序列化，节点整体消失
                        changed = true;
                    }
                }
                if (changed) {
                    write(env); // 不刷新 updated_at，清洗不改变用户的更新时间
                    cleaned++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清洗远程方式脏数据失败：" + dataDir, e);
        }
        if (cleaned > 0) {
            log.info("已清除 {} 个环境文件中仅有远程方式、无实际连接信息的 sunlogin 数据", cleaned);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String now() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
    }

    /** 删除环境文件；不存在时抛 EnvironmentNotFoundException */
    public void delete(String name) {
        Path file = fileOf(name);
        if (!Files.exists(file)) {
            throw new EnvironmentNotFoundException(name);
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException("删除环境文件失败：" + file, e);
        }
        cleanOrphanImages();
    }

    /**
     * 清理孤儿图片：模块截图按 UUID 平铺在 <dataDir>/images/ 下，不绑定环境，
     * 可能被多个环境共享引用（「从其他环境复制配置」会把引用一起深拷贝过去）。
     * 每次保存/删除环境后，把不再被任何环境 YAML 引用的图片文件删除。
     */
    public void cleanOrphanImages() {
        Path dir = dataDir.resolve("images");
        if (!Files.exists(dir)) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*" + EXTENSION)) {
            for (Path file : stream) {
                collectImageRefs(mapper.readValue(file.toFile(), Environment.class), referenced);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("收集图片引用失败：" + dataDir, e);
        }
        int removed = 0;
        long graceDeadline = System.currentTimeMillis() - IMAGE_GRACE_MILLIS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (referenced.contains(file.getFileName().toString())) {
                    continue;
                }
                // 豁免期内的新文件可能是刚上传、还没随环境保存的，不能判定为孤儿
                if (file.toFile().lastModified() > graceDeadline) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException e) {
                    throw new UncheckedIOException("删除孤儿图片失败：" + file, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描图片目录失败：" + dir, e);
        }
        if (removed > 0) {
            log.info("已清理 {} 个未被引用的图片文件", removed);
        }
    }

    /** 收集一个环境引用的全部图片文件名（四个支持图片的模块：远程方式/VPN/堡垒机/服务器清单） */
    private static void collectImageRefs(Environment env, Set<String> refs) {
        if (env.getEnvironments() == null) {
            return;
        }
        for (EnvBlock block : env.getEnvironments().values()) {
            addImageRefs(block.getSunlogin() == null ? null : block.getSunlogin().getImages(), refs);
            addImageRefs(block.getVpn() == null ? null : block.getVpn().getImages(), refs);
            addImageRefs(block.getBastion() == null ? null : block.getBastion().getImages(), refs);
            if (block.getClients() != null) {
                for (ClientInfo client : block.getClients()) {
                    addImageRefs(client == null ? null : client.getImages(), refs);
                }
            }
        }
    }

    private static void addImageRefs(List<String> images, Set<String> refs) {
        if (images != null) {
            refs.addAll(images);
        }
    }

    private void write(Environment env) {
        ensureDataDir();
        try {
            // 临时文件 + 原子移动，避免写一半被读到脏数据
            Path target = fileOf(env.getName());
            Path tmp = Files.createTempFile(dataDir, "odyssey-", ".tmp");
            mapper.writer().writeValue(tmp.toFile(), env);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入环境文件失败：" + env.getName(), e);
        }
    }

    private void validate(Environment env) {
        if (env == null || env.getName() == null || env.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("环境名（name）不能为空");
        }
        if (INVALID_NAME.matcher(env.getName()).find()) {
            throw new IllegalArgumentException("环境名含有非法字符：" + env.getName());
        }
    }

    private Path fileOf(String name) {
        return dataDir.resolve(name + EXTENSION);
    }

    private void ensureDataDir() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录：" + dataDir, e);
        }
    }
}
