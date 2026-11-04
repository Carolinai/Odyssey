package org.odyssey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.store.TicketReportStore;
import org.odyssey.store.TicketStatsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工单分析报告生成（spec §5.2① / D11①②、D13）：
 * 与看板同一套筛选口径取统计与工单明细，分章节调用触发用户的个人 AI 配置生成 Markdown，
 * 相似工单簇对工单「标题+描述」分批聚类（每批一次调用）、簇间二次归并一次调用。
 *
 * 报告行在任务开始时落库（running），成功写正文置 done，失败写原因置 failed；
 * 任务进度/错误经 TaskExecutor 落 task 表，页面轮询 /api/tasks 与 /api/tickets/report。
 */
@Service
public class TicketReportService {

    private static final Logger log = LoggerFactory.getLogger(TicketReportService.class);

    /** 参与聚类的工单上限（防御性：约 15 批 × 100，AI 调用量可控） */
    private static final int MAX_TICKETS = 1500;
    /** 聚类分批大小：单批喂给模型的工单行数（标题+描述截断后单批约 2 万字） */
    private static final int BATCH_SIZE = 100;
    /** 单张工单描述进语料的截断长度 */
    private static final int DESC_MAX = 200;
    /** 送二次归并的簇数上限（按工单数降序取前 N） */
    private static final int MERGE_MAX_CLUSTERS = 30;
    /** 单次 AI 调用的 max_tokens（glm 会先消耗在 thinking 上，需给足） */
    private static final int CHAT_MAX_TOKENS = 4096;

    /** 系统提示词：统一角色与输出约束（各章节复用） */
    private static final String SYSTEM_PROMPT =
            "你是质量部门的工单数据分析专家。输出为简体中文 Markdown 正文："
                    + "直接输出内容本身，不要用代码块包裹，不要客套话和开场白，"
                    + "数据结论必须基于给出的统计，不要编造数据。";

    private final TicketStatsStore statsStore;
    private final TicketReportStore reportStore;
    private final AiGatewayClient ai;
    private static final ObjectMapper mapper = new ObjectMapper();

    public TicketReportService(TicketStatsStore statsStore, TicketReportStore reportStore,
                               AiGatewayClient ai) {
        this.statsStore = statsStore;
        this.reportStore = reportStore;
        this.ai = ai;
    }

    /** 一个相似工单簇（解析 AI 返回后，工单号已校验真实存在） */
    public static class Cluster {
        public final String topic;
        public final List<Integer> numbers = new ArrayList<>();
        public final String common;

        Cluster(String topic, String common) {
            this.topic = topic;
            this.common = common;
        }
    }

    /**
     * 报告生成任务体（由 TaskExecutor 串行调度）：
     * 章节生成 → 相似簇分批聚类归并 → 组装落库。AI 配置不完整等异常会向上抛，
     * 由 TaskExecutor 落 task 失败原因；报告行同步置 failed。
     */
    public void run(TaskExecutor.TaskContext ctx, String user,
                    TicketStatsStore.Filter filter, Map<String, Object> filterParams) {
        long reportId = reportStore.create(writeJson(filterParams), user);
        long startAt = System.currentTimeMillis();
        try {
            String content = generate(ctx, user, filter, filterParams, startAt);
            reportStore.markDone(reportId, content);
            ctx.progress(100, "完成");
            log.info("AI 报告生成完成 reportId={} user={} 耗时 {}ms",
                    reportId, user, System.currentTimeMillis() - startAt);
        } catch (Exception e) {
            reportStore.markFailed(reportId, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
    }

    private String generate(TaskExecutor.TaskContext ctx, String user,
                            TicketStatsStore.Filter filter, Map<String, Object> filterParams,
                            long startAt) {
        // ---- 语料与统计（口径与看板一致）----
        ctx.progress(5, "读取工单与统计");
        Map<String, Object> cards = statsStore.cards(filter);
        List<Map<String, Object>> trend = statsStore.monthlyTrend(filter);
        List<Map<String, Object>> products = statsStore.byProduct(filter, 15);
        List<Map<String, Object>> modules = statsStore.byModule(filter, 15);
        List<Map<String, Object>> customers = statsStore.byCustomer(filter, 15);
        List<Map<String, Object>> assignees = statsStore.byAssignee(filter, 15);
        List<Map<String, Object>> priorities = statsStore.byPriority(filter);
        List<Map<String, Object>> tickets = statsStore.reportTickets(filter, MAX_TICKETS);
        if (tickets.isEmpty()) {
            throw new IllegalArgumentException("筛选范围内没有工单，请调整时间范围或筛选条件后重试");
        }
        String statsText = buildStatsText(cards, trend, products, modules, customers, assignees, priorities);
        log.info("AI 报告语料就绪 user={}：工单 {} 张", user, tickets.size());

        // ---- 章节生成 ----
        ctx.progress(10, "AI 生成：工单情况总结");
        String summary = ai.chat(user, SYSTEM_PROMPT,
                "以下是某时段工单的整体统计（口径与看板一致）：\n\n" + statsText
                        + "\n\n请撰写报告的「工单情况总结」章节：概述总量与状态构成、趋势走向、"
                        + "工单集中在哪些产品线/客户，指出值得注意的变化。"
                        + "只输出该章节 Markdown，以二级标题「## 工单情况总结」开头。", CHAT_MAX_TOKENS);

        ctx.progress(25, "AI 生成：维度分析");
        String dimension = ai.chat(user, SYSTEM_PROMPT,
                "以下是某时段工单的整体统计：\n\n" + statsText
                        + "\n\n请撰写「维度分析」章节：按产品线、功能模块、客户、负责人、优先级分别分析"
                        + "分布特征与不均衡点，点出资源投入与业务量是否匹配。"
                        + "只输出该章节 Markdown，以二级标题「## 维度分析」开头。", CHAT_MAX_TOKENS);

        ctx.progress(40, "AI 生成：优化建议");
        String advice = ai.chat(user, SYSTEM_PROMPT,
                "以下是某时段工单的整体统计：\n\n" + statsText
                        + "\n\n请撰写「优化建议」章节，分两小节："
                        + "「### 项目优化建议」（流程、资源分配、响应机制层面）与"
                        + "「### 业务功能优化建议」（从高频工单反推产品功能可改进点）。每条建议给出依据。"
                        + "只输出该章节 Markdown，以二级标题「## 优化建议」开头。", CHAT_MAX_TOKENS);

        // ---- 相似工单簇：分批聚类 → 簇间归并 ----
        List<Cluster> clusters = clusterTickets(ctx, user, tickets);

        // ---- 组装落库 ----
        ctx.progress(92, "组装报告");
        return assemble(filterParams, cards, tickets.size(), clusters, user, startAt,
                summary, dimension, advice);
    }

    /** 相似工单簇：分批聚类（每批一次 AI 调用）→ 簇间二次归并（一次调用） */
    private List<Cluster> clusterTickets(TaskExecutor.TaskContext ctx, String user,
                                         List<Map<String, Object>> tickets) {
        Set<Long> knownNumbers = new HashSet<>();
        for (Map<String, Object> t : tickets) {
            knownNumbers.add(((Number) t.get("number")).longValue());
        }

        ctx.progress(50, "相似工单簇：分批聚类");
        List<Cluster> raw = new ArrayList<>();
        int batches = (tickets.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        for (int i = 0; i < tickets.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> batch = tickets.subList(i, Math.min(i + BATCH_SIZE, tickets.size()));
            int batchNo = i / BATCH_SIZE + 1;
            ctx.progress(50 + 30 * (batchNo - 1) / batches, "相似工单簇：聚类 " + batchNo + "/" + batches + " 批");
            String resp = ai.chat(user, SYSTEM_PROMPT,
                    "下面是工单列表（每行：工单号 | 客户 | 产品线 | 状态 | 标题 — 描述）：\n\n"
                            + batchLines(batch)
                            + "\n\n请把内容相似的工单归并成簇（主题相同的才归并，宁可少归并不要硬凑）。"
                            + "只输出 JSON 数组（不要代码块、不要解释），每个元素："
                            + "{\"topic\":\"簇主题（15字内）\",\"numbers\":[工单号数组],\"common\":\"共性问题一句话\"}。"
                            + "没有相似簇时输出 []。",
                    CHAT_MAX_TOKENS);
            raw.addAll(parseClusters(resp, knownNumbers));
        }

        // 二次归并：簇太多时按工单数降序截断后送归并
        raw.sort((a, b) -> Integer.compare(b.numbers.size(), a.numbers.size()));
        List<Cluster> toMerge = raw.subList(0, Math.min(raw.size(), MERGE_MAX_CLUSTERS));
        if (toMerge.isEmpty()) {
            return new ArrayList<>();
        }
        // 归并输入转回 Map 形式（与批次输出的 JSON 结构一致）
        List<Map<String, Object>> mergeInput = new ArrayList<>();
        for (Cluster c : toMerge) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", c.topic);
            m.put("numbers", c.numbers);
            m.put("common", c.common);
            mergeInput.add(m);
        }
        ctx.progress(85, "相似工单簇：二次归并");
        String resp = ai.chat(user, SYSTEM_PROMPT,
                "以下是初步归并的工单簇（JSON 数组，numbers 为工单号）：\n\n"
                        + writeJson(mergeInput)
                        + "\n\n请把主题相近的簇归并（合并 numbers 并去重、重写 topic 与 common），"
                        + "无相近簇的原样保留。只输出 JSON 数组，格式与输入一致。",
                CHAT_MAX_TOKENS);
        List<Cluster> merged = parseClusters(resp, knownNumbers);
        merged.sort((a, b) -> Integer.compare(b.numbers.size(), a.numbers.size()));
        return merged;
    }

    /** 聚类批次语料：每行「工单号 | 客户 | 产品线 | 状态 | 标题 — 描述截断」 */
    private static String batchLines(List<Map<String, Object>> tickets) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : tickets) {
            sb.append('#').append(t.get("number"))
                    .append(" | ").append(orDash(t.get("customer_name")))
                    .append(" | ").append(orDash(t.get("products")))
                    .append(" | ").append(orDash(t.get("status_name")))
                    .append(" | ").append(orDash(t.get("name")));
            String desc = t.get("description_text") == null ? "" : String.valueOf(t.get("description_text"));
            desc = desc.replaceAll("\\s+", " ").trim();
            if (desc.length() > DESC_MAX) {
                desc = desc.substring(0, DESC_MAX) + "…";
            }
            if (!desc.isEmpty()) {
                sb.append(" — ").append(desc);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 宽松解析 AI 返回的簇 JSON：剥掉代码块围栏、截取首个 [ 到最后一个 ]，
     * topic/common 取文本，numbers 只保留真实存在的工单号（其余丢弃）。
     */
    private List<Cluster> parseClusters(String resp, Set<Long> knownNumbers) {
        List<Cluster> result = new ArrayList<>();
        String text = resp == null ? "" : resp.trim();
        int l = text.indexOf('[');
        int r = text.lastIndexOf(']');
        if (l < 0 || r <= l) {
            log.warn("AI 簇输出无 JSON 数组，忽略：{}", abbreviate(text));
            return result;
        }
        try {
            JsonNode arr = mapper.readTree(text.substring(l, r + 1));
            if (!arr.isArray()) {
                return result;
            }
            for (JsonNode n : arr) {
                String topic = n.path("topic").asText("").trim();
                String common = n.path("common").asText("").trim();
                Cluster c = new Cluster(topic, common);
                for (JsonNode num : n.path("numbers")) {
                    String s = num.asText("").replace("#", "").trim();
                    try {
                        long v = Long.parseLong(s);
                        if (knownNumbers.contains(v) && !c.numbers.contains((int) v)) {
                            c.numbers.add((int) v);
                        }
                    } catch (NumberFormatException ignored) {
                        // 非工单号的元素直接丢弃
                    }
                }
                if (!c.numbers.isEmpty()) {
                    result.add(c);
                }
            }
        } catch (Exception e) {
            log.warn("AI 簇输出解析失败，忽略：{}（{}）", abbreviate(text), e.getMessage());
        }
        return result;
    }

    /** 组装完整报告 Markdown */
    private String assemble(Map<String, Object> filterParams, Map<String, Object> cards,
                            int ticketCount, List<Cluster> clusters, String user, long startAt,
                            String summary, String dimension, String advice) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 工单分析报告\n\n");
        sb.append("- 时间范围：").append(rangeText(filterParams)).append('\n');
        String conditions = conditionsText(filterParams);
        sb.append("- 筛选条件：").append(conditions.isEmpty() ? "无（全部工单）" : conditions).append('\n');
        sb.append("- 工单量：").append(ticketCount).append(" 张，其中已解决 ")
                .append(cards.get("resolved") == null ? 0 : cards.get("resolved")).append(" 张\n");
        sb.append("- 生成时间：").append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneOffset.ofHours(8)).format(new Date(startAt).toInstant())).append('\n');
        sb.append("- 触发人：").append(user).append("；耗时 ")
                .append(Math.max(1, (System.currentTimeMillis() - startAt) / 1000)).append(" 秒\n");
        sb.append("- 相似工单簇：").append(clusters.size()).append(" 个\n");
        sb.append("\n---\n\n");
        sb.append(summary.trim()).append("\n\n");
        sb.append(dimension.trim()).append("\n\n");
        sb.append(advice.trim()).append("\n\n");
        sb.append(renderClusters(clusters));
        return sb.toString();
    }

    /** 相似工单簇章节渲染 */
    private static String renderClusters(List<Cluster> clusters) {
        StringBuilder sb = new StringBuilder("## 相似工单簇\n\n");
        if (clusters.isEmpty()) {
            sb.append("筛选范围内未发现明显的相似工单聚集。\n");
            return sb.toString();
        }
        sb.append("> 由 AI 对工单标题与描述分批聚类归并得出，工单号均已校验真实存在，供排查与改进参考。\n\n");
        for (Cluster c : clusters) {
            sb.append("### ").append(c.topic.isEmpty() ? "未命名簇" : c.topic)
                    .append("（").append(c.numbers.size()).append(" 单）\n");
            sb.append("- 涉及工单：");
            for (int i = 0; i < c.numbers.size(); i++) {
                if (i > 0) sb.append('、');
                sb.append('#').append(c.numbers.get(i));
            }
            sb.append('\n');
            if (!c.common.isEmpty()) {
                sb.append("- 共性问题：").append(c.common).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 统计摘要文本（喂给 AI 的结构化数据，与看板口径一致） */
    private static String buildStatsText(Map<String, Object> cards, List<Map<String, Object>> trend,
                                         List<Map<String, Object>> products, List<Map<String, Object>> modules,
                                         List<Map<String, Object>> customers, List<Map<String, Object>> assignees,
                                         List<Map<String, Object>> priorities) {
        StringBuilder sb = new StringBuilder();
        sb.append("【统计卡片】").append(writeJson(cards)).append('\n');
        sb.append("【按月新增与解决趋势】(月份, 新增, 解决)\n");
        for (Map<String, Object> r : trend) {
            sb.append(r.get("month")).append(", 新增 ").append(r.get("created"))
                    .append(", 解决 ").append(r.get("resolved")).append('\n');
        }
        appendDist(sb, "产品线分布", products);
        appendDist(sb, "功能模块分布", modules);
        appendDist(sb, "客户分布 Top15", customers);
        appendDist(sb, "负责人分布", assignees);
        appendDist(sb, "优先级分布", priorities);
        return sb.toString();
    }

    private static void appendDist(StringBuilder sb, String title, List<Map<String, Object>> rows) {
        sb.append("【").append(title).append("】");
        boolean first = true;
        for (Map<String, Object> r : rows) {
            if (!first) sb.append("；");
            first = false;
            sb.append(r.get("name")).append(" = ").append(r.get("n"));
        }
        sb.append('\n');
    }

    /** 时间范围人话描述（meta 与审计共用）：未设置时按默认「近六个月」 */
    public static String rangeText(Map<String, Object> params) {
        Long from = asLong(params.get("from"));
        Long to = asLong(params.get("to"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.ofHours(8));
        if (from == null && to == null) {
            return "近六个月";
        }
        String a = from == null ? "（起始不限）" : fmt.format(new Date(from).toInstant());
        String b = to == null ? "（至今）" : fmt.format(new Date(to).toInstant());
        return a + " ~ " + b;
    }

    /** 筛选条件人话描述（meta 与审计共用） */
    public static String conditionsText(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        appendCond(sb, "产品线", params.get("product"));
        appendCond(sb, "客户", params.get("customer"));
        appendCond(sb, "状态", params.get("status_name"));
        return sb.toString();
    }

    private static void appendCond(StringBuilder sb, String label, Object v) {
        if (v != null && !String.valueOf(v).trim().isEmpty()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(label).append("=").append(v);
        }
    }

    private static String orDash(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? "-" : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String abbreviate(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }
}
