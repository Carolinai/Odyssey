package org.odyssey.api;

import org.odyssey.config.OdysseyProperties;
import org.odyssey.model.Environment;
import org.odyssey.model.FileInfo;
import org.odyssey.store.EnvironmentNotFoundException;
import org.odyssey.store.EnvironmentStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 环境 REST 接口：搜索/详情/新建/修改/删除/附件。
 */
@RestController
@RequestMapping("/api/environments")
public class EnvironmentController {

    private final EnvironmentStore store;

    /** 附件根目录：<dataDir>/files/<客户名>/ */
    private final Path filesRoot;

    public EnvironmentController(EnvironmentStore store, OdysseyProperties properties) {
        this.store = store;
        this.filesRoot = Paths.get(properties.getDataDir(), "files");
    }

    /**
     * 列表/搜索：kw 对 name、alias 做不区分大小写的模糊匹配，空 kw 列出全部。
     * detail=true 时返回完整数据（主页平铺展示用），否则返回摘要。
     */
    @GetMapping
    public List<?> list(@RequestParam(required = false) String kw,
                        @RequestParam(required = false, defaultValue = "false") boolean detail) {
        List<Environment> all = store.list();
        if (kw != null && !kw.trim().isEmpty()) {
            String needle = kw.trim().toLowerCase(Locale.ROOT);
            all = all.stream().filter(env -> matches(env, needle)).collect(Collectors.toList());
        }
        return all.stream()
                .map(detail ? env -> env : EnvironmentController::toSummary)
                .collect(Collectors.toList());
    }

    @GetMapping("/{name}")
    public Environment get(@PathVariable String name) {
        return store.get(name);
    }

    @PostMapping
    public Environment create(@RequestBody Environment env) {
        store.create(env);
        return env;
    }

    /** name 不可改：以路径参数为准，忽略请求体中的 name */
    @PutMapping("/{name}")
    public Environment update(@PathVariable String name, @RequestBody Environment env) {
        env.setName(name);
        store.update(env);
        return env;
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable String name) throws IOException {
        store.delete(name);
        // 连带清理附件目录
        Path dir = filesRoot.resolve(name);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** 上传附件：文件实体落盘，元数据写回 YAML */
    @PostMapping("/{name}/files")
    public FileInfo uploadFile(@PathVariable String name,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "note", required = false) String note) throws IOException {
        Environment env = store.get(name);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        Path dir = filesRoot.resolve(name);
        Files.createDirectories(dir);
        String stored = UUID.randomUUID().toString();
        // transferTo 对相对路径会解析到临时目录，这里转绝对路径
        file.transferTo(dir.resolve(stored).toAbsolutePath().toFile());

        FileInfo info = new FileInfo();
        info.setName(file.getOriginalFilename());
        info.setStoredName(stored);
        info.setSize(file.getSize());
        info.setUploadedAt(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now()));
        info.setNote(note);
        if (env.getFiles() == null) {
            env.setFiles(new ArrayList<>());
        }
        env.getFiles().add(info);
        store.update(env);
        return info;
    }

    /** 下载附件：以原始文件名（UTF-8）返回 */
    @GetMapping("/{name}/files/{storedName}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String name,
                                               @PathVariable String storedName) throws IOException {
        Environment env = store.get(name);
        FileInfo info = env.getFiles() == null ? null : env.getFiles().stream()
                .filter(f -> storedName.equals(f.getStoredName()))
                .findFirst()
                .orElse(null);
        if (info == null) {
            throw new EnvironmentNotFoundException("附件 " + storedName);
        }

        Path path = filesRoot.resolve(name).resolve(storedName);
        if (!Files.exists(path)) {
            throw new EnvironmentNotFoundException("附件文件缺失：" + info.getName());
        }

        byte[] body = Files.readAllBytes(path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(info.getName() == null ? storedName : info.getName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** 删除附件：删文件实体并从元数据移除 */
    @DeleteMapping("/{name}/files/{storedName}")
    public void deleteFile(@PathVariable String name, @PathVariable String storedName) throws IOException {
        Environment env = store.get(name);
        List<FileInfo> files = env.getFiles();
        boolean removed = files != null && files.removeIf(f -> storedName.equals(f.getStoredName()));
        if (!removed) {
            throw new EnvironmentNotFoundException("附件 " + storedName);
        }
        Files.deleteIfExists(filesRoot.resolve(name).resolve(storedName));
        store.update(env);
    }

    private static boolean matches(Environment env, String needle) {
        if (env.getName() != null && env.getName().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (env.getAlias() != null) {
            for (String alias : env.getAlias()) {
                if (alias != null && alias.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        if (env.getTags() != null) {
            for (org.odyssey.model.Tag tag : env.getTags()) {
                if (tag.getName() != null && tag.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static EnvironmentSummary toSummary(Environment env) {
        return new EnvironmentSummary(env.getName(), env.getAlias(), env.getTags());
    }
}
