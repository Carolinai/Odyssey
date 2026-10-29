package org.odyssey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.model.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户信息变更对比：把改动前后两份 Environment 序列化成 JSON 树递归对比，
 * 生成中文字段路径描述（如「生产→VPN→密码」）。
 * 只记字段路径，不记字段值——密码等敏感信息不进审计日志。
 */
public final class EnvironmentDiff {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 系统维护字段，不参与对比 */
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("created_at", "updated_at"));

    /** 字段路径 → 中文标签；未收录的键原样保留 */
    private static final Map<String, String> LABELS = new HashMap<>();
    static {
        LABELS.put("name", "客户名");
        LABELS.put("alias", "别名");
        LABELS.put("tags", "标签");
        LABELS.put("files", "附件");
        LABELS.put("test", "测试");
        LABELS.put("validation", "验证");
        LABELS.put("prod", "生产");
        LABELS.put("vpn", "VPN");
        LABELS.put("bastion", "堡垒机");
        LABELS.put("sunlogin", "向日葵");
        LABELS.put("clients", "服务器清单");
        LABELS.put("access", "到达路径");
        LABELS.put("admin_account", "管理员账号");
        LABELS.put("code", "代码分支");
        LABELS.put("quirks", "备注");
        LABELS.put("how", "接入方式");
        LABELS.put("entry", "入口");
        LABELS.put("type", "类型");
        LABELS.put("id", "标识");
        LABELS.put("user", "用户名");
        LABELS.put("account", "账号");
        LABELS.put("password", "密码");
        LABELS.put("otp", "OTP");
        LABELS.put("key", "私钥");
        LABELS.put("key_file", "私钥文件");
        LABELS.put("method", "方式");
        LABELS.put("target", "目标");
        LABELS.put("via", "跳板");
        LABELS.put("ip", "IP");
        LABELS.put("project", "项目");
        LABELS.put("branch", "分支");
        LABELS.put("lookup", "查询方式");
        LABELS.put("sql", "SQL");
        LABELS.put("color", "颜色");
        LABELS.put("note", "说明");
        LABELS.put("notes", "说明");
        LABELS.put("images", "截图");
    }

    private EnvironmentDiff() {
    }

    /** 新增描述：创建客户「XX」：别名、标签、生产环境 */
    public static String describeCreate(Environment env) {
        List<String> parts = new ArrayList<>();
        if (env.getAlias() != null && !env.getAlias().isEmpty()) {
            parts.add("别名");
        }
        if (env.getTags() != null && !env.getTags().isEmpty()) {
            parts.add("标签");
        }
        if (env.getEnvironments() != null && !env.getEnvironments().isEmpty()) {
            List<String> blocks = new ArrayList<>();
            for (String key : env.getEnvironments().keySet()) {
                blocks.add(label(key));
            }
            parts.add(String.join("、", blocks) + "环境");
        }
        if (env.getFiles() != null && !env.getFiles().isEmpty()) {
            parts.add("附件 " + env.getFiles().size() + " 个");
        }
        String head = "创建客户「" + env.getName() + "」";
        return parts.isEmpty() ? head : head + "：" + String.join("、", parts);
    }

    /** 删除描述：删除客户「XX」（含 N 个附件） */
    public static String describeDelete(Environment env) {
        String head = "删除客户「" + env.getName() + "」";
        int files = env.getFiles() == null ? 0 : env.getFiles().size();
        return files > 0 ? head + "（含 " + files + " 个附件）" : head;
    }

    /** 修改描述：修改：别名、生产→VPN→密码。无差异时返回 null */
    public static String describeChanges(Environment before, Environment after) {
        List<String> paths = changedPaths(before, after);
        if (paths.isEmpty()) {
            return null;
        }
        return "修改：" + String.join("、", paths);
    }

    /** 对比两份数据，返回发生变化的中文字段路径列表 */
    static List<String> changedPaths(Environment before, Environment after) {
        JsonNode a = mapper.valueToTree(before);
        JsonNode b = mapper.valueToTree(after);
        List<String> out = new ArrayList<>();
        diff(a, b, new ArrayList<String>(), out);
        return out;
    }

    /** 递归对比：路径进描述，值不进描述 */
    private static void diff(JsonNode a, JsonNode b, List<String> path, List<String> out) {
        if (isNull(a)) {
            if (!isNull(b)) {
                out.add(pathLabel(path) + "（新增）");
            }
            return;
        }
        if (isNull(b)) {
            out.add(pathLabel(path) + "（删除）");
            return;
        }
        if (a.equals(b)) {
            return;
        }
        if (a.isObject() && b.isObject()) {
            // 对象：按字段逐一递归（字段顺序无意义，用并集去重）
            Set<String> fields = new java.util.LinkedHashSet<>();
            a.fieldNames().forEachRemaining(fields::add);
            b.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (IGNORED.contains(field)) {
                    continue;
                }
                path.add(field);
                diff(a.get(field), b.get(field), path, out);
                path.remove(path.size() - 1);
            }
        } else if (a.isArray() && b.isArray()) {
            diffArrays(a, b, path, out);
        } else {
            out.add(pathLabel(path));
        }
    }

    /** 数组对比：标量数组（别名、标签等）整体比较只记路径；对象数组逐元素带下标递归 */
    private static void diffArrays(JsonNode a, JsonNode b, List<String> path, List<String> out) {
        boolean scalar = a.size() > 0 && a.get(0).isValueNode();
        if (scalar) {
            // 标量数组：整体不同记一次路径；条目数变化额外注明方向
            if (!a.equals(b)) {
                out.add(pathLabel(path) + (a.size() == b.size() ? "" : sizeSuffix(a.size(), b.size())));
            }
            return;
        }
        int min = Math.min(a.size(), b.size());
        for (int i = 0; i < min; i++) {
            path.add(String.valueOf(i));
            diff(a.get(i), b.get(i), path, out);
            path.remove(path.size() - 1);
        }
        if (a.size() != b.size()) {
            out.add(pathLabel(path) + sizeSuffix(a.size(), b.size()));
        }
    }

    private static String sizeSuffix(int before, int after) {
        return before < after ? "（新增条目）" : "（删除条目）";
    }

    private static boolean isNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /** 路径 → 中文标签串：去掉 environments 层，其余段查词典，数字段并入上一段作下标 */
    private static String pathLabel(List<String> path) {
        List<String> labels = new ArrayList<>();
        for (String segment : path) {
            if ("environments".equals(segment)) {
                continue;
            }
            if (segment.matches("\\d+")) {
                if (!labels.isEmpty()) {
                    labels.set(labels.size() - 1, labels.get(labels.size() - 1) + "[" + segment + "]");
                }
                continue;
            }
            labels.add(label(segment));
        }
        return String.join("→", labels);
    }

    private static String label(String key) {
        String label = LABELS.get(key);
        return label == null ? key : label;
    }
}
