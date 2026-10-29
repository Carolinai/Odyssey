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
 * 客户信息变更对比：新旧两份 Environment 序列化成 JSON 树递归对比，
 * 产出「逐字段」的变更记录——每条含动作（新增/修改/删除）、信息类型（如 生产→向日葵）、
 * 字段名与值。值按用户要求明文记录（含密码，公司内部使用）；超长文本截断。
 */
public final class EnvironmentDiff {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 字段值超长截断上限 */
    private static final int VALUE_MAX_LENGTH = 200;

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
        LABELS.put("sunlogin", "远程方式");
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
        LABELS.put("images", "图片");
    }

    /** 父级.字段 → 中文标签：覆盖与顶层同名的嵌套字段（如标签的 name） */
    private static final Map<String, String> CONTEXT_LABELS = new HashMap<>();
    static {
        CONTEXT_LABELS.put("标签.name", "名称");
        CONTEXT_LABELS.put("附件.name", "文件名");
        CONTEXT_LABELS.put("服务器清单.name", "名称");
    }

    private EnvironmentDiff() {
    }

    /** 单个字段的变更记录 */
    public static class Change {
        private final String action;   // 新增 / 修改 / 删除
        private final String module;   // 信息类型（字段所属路径，如 生产→向日葵）
        private final String field;    // 字段中文名
        private final String oldValue; // 修改前/删除时的值
        private final String newValue; // 修改后/新增时的值

        Change(String action, String module, String field, String oldValue, String newValue) {
            this.action = action;
            this.module = module;
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        public String getAction() {
            return action;
        }

        public String getModule() {
            return module;
        }

        public String getField() {
            return field;
        }

        public String getOldValue() {
            return oldValue;
        }

        public String getNewValue() {
            return newValue;
        }
    }

    /** 空缺节点：表示「该字段不存在」（新增/删除对比的另一侧） */
    private static final JsonNode MISSING =
            com.fasterxml.jackson.databind.node.MissingNode.getInstance();

    /** 对比两份数据，产出逐字段的变更记录；before/after 传 null 表示整条新增/删除 */
    public static List<Change> changes(Environment before, Environment after) {
        JsonNode a = before == null ? MISSING : mapper.valueToTree(before);
        JsonNode b = after == null ? MISSING : mapper.valueToTree(after);
        List<Change> out = new ArrayList<>();
        diff(a, b, new ArrayList<String>(), out);
        return out;
    }

    /** 递归对比：对象逐字段下钻，数组逐元素下钻，叶子（标量/标量数组）记一条变更 */
    private static void diff(JsonNode aRaw, JsonNode bRaw, List<String> path, List<Change> out) {
        JsonNode a = isMissing(aRaw) ? MISSING : aRaw;
        JsonNode b = isMissing(bRaw) ? MISSING : bRaw;
        if (a.equals(b)) {
            return;
        }

        // 一侧缺失：另一侧是对象/数组时逐字段下钻，让新增/删除也落到叶子字段
        if (a == MISSING || b == MISSING) {
            JsonNode present = a == MISSING ? b : a;
            boolean adding = a == MISSING; // 新增：缺失侧在 a；删除：缺失侧在 b
            if (present.isObject()) {
                List<String> fields = new ArrayList<>();
                present.fieldNames().forEachRemaining(fields::add);
                java.util.Collections.sort(fields);
                for (String field : fields) {
                    if (IGNORED.contains(field)) {
                        continue;
                    }
                    path.add(field);
                    // 下钻时保持缺失侧在原侧，递归子层才能把动作判定为新增/删除
                    if (adding) {
                        diff(MISSING, present.get(field), path, out);
                    } else {
                        diff(present.get(field), MISSING, path, out);
                    }
                    path.remove(path.size() - 1);
                }
            } else if (present.isArray() && !allScalar(present)) {
                for (int i = 0; i < present.size(); i++) {
                    path.add(String.valueOf(i));
                    if (adding) {
                        diff(MISSING, present.get(i), path, out);
                    } else {
                        diff(present.get(i), MISSING, path, out);
                    }
                    path.remove(path.size() - 1);
                }
            } else {
                out.add(change(adding ? AuditService.EVENT_CREATE : AuditService.EVENT_DELETE, path,
                        adding ? null : present, adding ? present : null));
            }
            return;
        }

        if (a.isObject() && b.isObject()) {
            Set<String> fields = new HashSet<>();
            a.fieldNames().forEachRemaining(fields::add);
            b.fieldNames().forEachRemaining(fields::add);
            List<String> sorted = new ArrayList<>(fields);
            java.util.Collections.sort(sorted);
            for (String field : sorted) {
                if (IGNORED.contains(field)) {
                    continue;
                }
                path.add(field);
                diff(a.get(field), b.get(field), path, out);
                path.remove(path.size() - 1);
            }
        } else if (a.isArray() && b.isArray()) {
            if (allScalar(a) && allScalar(b)) {
                // 标量数组视为一个整体字段（如 别名）：一条记录带全量值
                out.add(change(AuditService.EVENT_UPDATE, path, a, b));
                return;
            }
            int min = Math.min(a.size(), b.size());
            for (int i = 0; i < min; i++) {
                path.add(String.valueOf(i));
                diff(a.get(i), b.get(i), path, out);
                path.remove(path.size() - 1);
            }
            for (int i = min; i < a.size(); i++) { // 删除的尾部元素
                path.add(String.valueOf(i));
                diff(a.get(i), MISSING, path, out);
                path.remove(path.size() - 1);
            }
            for (int i = min; i < b.size(); i++) { // 新增的尾部元素
                path.add(String.valueOf(i));
                diff(MISSING, b.get(i), path, out);
                path.remove(path.size() - 1);
            }
        } else {
            out.add(change(AuditService.EVENT_UPDATE, path, a, b));
        }
    }

    /** 由完整路径构造变更记录：信息类型含字段名（如 测试→远程方式→图片），field 取路径末级中文名 */
    private static Change change(String action, List<String> path, JsonNode oldValue, JsonNode newValue) {
        List<String> labels = pathLabels(path);
        String field = labels.isEmpty() ? "" : labels.get(labels.size() - 1);
        return new Change(action, String.join("→", labels), field,
                render(oldValue), render(newValue));
    }

    /** 完整路径 → 中文标签列表；顶层字段归「基本信息」下 */
    private static List<String> pathLabels(List<String> path) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            if ("environments".equals(segment)) {
                continue;
            }
            if (segment.matches("\\d+")) {
                // 下标附在上一级标签后；末位下标（标量数组整体就是一个字段）不标
                if (!labels.isEmpty() && i < path.size() - 1) {
                    labels.set(labels.size() - 1, labels.get(labels.size() - 1) + "[" + segment + "]");
                }
                continue;
            }
            labels.add(contextLabel(labels.isEmpty() ? null : labels.get(labels.size() - 1), segment));
        }
        if (!labels.isEmpty() && !path.contains("environments")) {
            labels.add(0, "基本信息");
        }
        return labels;
    }

    /** 带父级上下文的字段标签：如标签/附件里的 name 是「名称/文件名」而非「客户名」 */
    private static String contextLabel(String parentLabel, String key) {
        if (parentLabel != null) {
            String parent = parentLabel.replaceAll("\\[\\d+\\]$", "");
            String qualified = CONTEXT_LABELS.get(parent + "." + key);
            if (qualified != null) {
                return qualified;
            }
        }
        return label(key);
    }

    /** 值渲染：标量取文本；标量数组顿号连接；复杂结构压成单行 JSON；超长截断 */
    private static String render(JsonNode node) {
        if (isMissing(node)) {
            return "";
        }
        String text;
        if (node.isValueNode()) {
            text = node.asText();
        } else if (node.isArray() && allScalar(node)) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(item.asText());
            }
            text = sb.toString();
        } else {
            text = node.toString(); // Jackson toString 即紧凑 JSON
        }
        if (text.length() > VALUE_MAX_LENGTH) {
            text = text.substring(0, VALUE_MAX_LENGTH) + "…（截断）";
        }
        return text;
    }

    private static boolean allScalar(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isValueNode()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static String label(String key) {
        String label = LABELS.get(key);
        return label == null ? key : label;
    }
}
