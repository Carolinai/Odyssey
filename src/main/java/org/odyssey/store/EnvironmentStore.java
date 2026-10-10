package org.odyssey.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.odyssey.model.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 环境数据存储层：一个环境一个 YAML 文件（文件名 = name + .yaml）。
 * name 是搜索主键且唯一；目录不存在时自动创建。
 */
public class EnvironmentStore {

    /** name 作为文件名，禁止文件系统特殊字符与路径分隔符 */
    private static final Pattern INVALID_NAME = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    private static final String EXTENSION = ".yaml";

    private final Path dataDir;
    private final ObjectMapper mapper;

    public EnvironmentStore(Path dataDir) {
        this.dataDir = dataDir;
        // MINIMIZE_QUOTES：字符串不加引号，生成的 YAML 更适合人工阅读与 git diff；
        // 看似数字/布尔的字符串 Jackson 会自动加引号保证读回仍是字符串
        this.mapper = new ObjectMapper(
                new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    }

    /** 列出全部环境（按文件名排序，保证稳定顺序） */
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
        result.sort((a, b) -> String.valueOf(a.getName()).compareTo(String.valueOf(b.getName())));
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

    /** 新建环境；name 已存在时抛 DuplicateEnvironmentException */
    public void create(Environment env) {
        validate(env);
        if (exists(env.getName())) {
            throw new DuplicateEnvironmentException(env.getName());
        }
        write(env);
    }

    /** 更新环境；不存在时抛 EnvironmentNotFoundException（name 不可改，改 name 需删除重建） */
    public void update(Environment env) {
        validate(env);
        if (!exists(env.getName())) {
            throw new EnvironmentNotFoundException(env.getName());
        }
        write(env);
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
