package org.odyssey.api;

import org.odyssey.config.OdysseyProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 模块截图接口：远程方式 / VPN / 堡垒机 / 服务器信息的图片上传与读取。
 * 文件按 UUID 平铺存放在 <dataDir>/images/ 下，不绑定环境；
 * 环境数据（YAML）里只保存文件名引用，从其他环境复制配置时引用随数据一起深拷贝，
 * 多个环境共享同一图片文件；不再被引用的文件由 EnvironmentStore 的孤儿清理回收。
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    /** 单张图片大小上限：截图场景 10MB 足够 */
    private static final long MAX_SIZE = 10 * 1024 * 1024;

    /** 允许的图片扩展名与返回给浏览器的 Content-Type（项目为 Java 8，不能用 Map.of） */
    private static final Map<String, MediaType> ALLOWED_TYPES;
    static {
        Map<String, MediaType> types = new HashMap<>();
        types.put("jpg", MediaType.IMAGE_JPEG);
        types.put("jpeg", MediaType.IMAGE_JPEG);
        types.put("png", MediaType.IMAGE_PNG);
        types.put("webp", MediaType.valueOf("image/webp"));
        types.put("gif", MediaType.IMAGE_GIF);
        ALLOWED_TYPES = Collections.unmodifiableMap(types);
    }

    /** 合法文件名：UUID + 白名单扩展名，杜绝路径穿越与任意文件读取 */
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|webp|gif)");

    private final Path imagesRoot;

    public ImageController(OdysseyProperties properties) {
        this.imagesRoot = Paths.get(properties.getDataDir(), "data", "images");
    }

    /** 上传图片：校验扩展名与大小，按 UUID 落盘，返回文件名供前端写进环境数据 */
    @PostMapping
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }
        String original = file.getOriginalFilename();
        int dot = original == null ? -1 : original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.containsKey(ext)) {
            throw new IllegalArgumentException("仅支持 jpg/png/webp/gif 图片");
        }

        Files.createDirectories(imagesRoot);
        String stored = UUID.randomUUID() + "." + ext;
        // transferTo 对相对路径会解析到临时目录，这里转绝对路径
        file.transferTo(imagesRoot.resolve(stored).toAbsolutePath().toFile());
        Map<String, String> result = new HashMap<>();
        result.put("name", stored);
        result.put("url", "/api/images/" + stored);
        return result;
    }

    /** 读取图片：按扩展名返回对应 Content-Type，浏览器可直接作为 <img> 来源 */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> get(@PathVariable String name) throws IOException {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("非法的图片文件名");
        }
        Path path = imagesRoot.resolve(name);
        if (!Files.exists(path)) {
            throw new ImageNotFoundException(name);
        }
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return ResponseEntity.ok()
                .contentType(ALLOWED_TYPES.get(ext))
                .body(Files.readAllBytes(path));
    }

    /** 图片不存在（与非法文件名区分开，避免前端把 400 当成数据问题） */
    static class ImageNotFoundException extends RuntimeException {
        ImageNotFoundException(String name) {
            super("图片不存在：" + name);
        }
    }
}
