package org.odyssey.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.model.AuditLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志存储层：JSONL 追加写（一行一条 JSON），只增不改，
 * 保留全部历史。目录不存在时自动创建。
 */
public class AuditStore {

    private static final String FILE_NAME = "audit.jsonl";

    private final Path auditDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditStore(Path dataRoot) {
        this.auditDir = dataRoot.resolve("audit");
    }

    /** 追加一条审计日志 */
    public synchronized void append(AuditLog entry) {
        try {
            Files.createDirectories(auditDir);
            Path file = auditDir.resolve(FILE_NAME);
            Files.write(file, (mapper.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写入审计日志失败", e);
        }
    }

    /** 读取全部审计日志（按写入顺序，即时间正序） */
    public synchronized List<AuditLog> readAll() {
        Path file = auditDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<AuditLog> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                result.add(mapper.readValue(line, AuditLog.class));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("读取审计日志失败：" + file, e);
        }
    }
}
