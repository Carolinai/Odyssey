package org.odyssey.service;

import org.odyssey.store.InsightStore;
import org.odyssey.store.UserConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 解决经验提炼（spec §5.2③ / D11③、D14）：
 * 对已解决且尚无经验的工单，取标题 + 描述 + 评论喂给触发用户的个人 AI，
 * 提炼一条结构化经验（问题现象 / 原因分析 / 解决方式）落 insight 表。
 *
 * 幂等与分批（D14）：按工单粒度——已有经验的工单跳过、单张失败不入库下次重跑再试；
 * 每次任务只提炼 limit 张（默认 200），5k+ 张的全量靠多次触发跑完，任务可随时中断不丢进度。
 * 同步完成后按新增/变更的已解决工单自动增量（TicketSyncService 联动，受个人开关控制）。
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    /** 单次任务提炼工单上限：约 200 次 AI 调用（几十分钟内跑完，剩余下次继续） */
    public static final int DEFAULT_BATCH_LIMIT = 200;
    /** 描述进语料的截断长度 */
    private static final int DESC_MAX = 800;
    /** 单条评论进语料的截断长度 */
    private static final int COMMENT_MAX = 300;
    /** 单次 AI 调用 max_tokens */
    private static final int CHAT_MAX_TOKENS = 2048;

    private static final String SYSTEM_PROMPT =
            "你是质量部门的工单知识管理专家。输入是一张已解决工单的标题、描述与评论记录。"
                    + "输出为简体中文 Markdown 的「解决经验」，固定三个小节："
                    + "「## 问题现象」「## 原因分析」「## 解决方式」。"
                    + "只依据给出的内容提炼，不要编造；信息不足的小节写「记录中没有明确说明」。"
                    + "直接输出 Markdown 正文，不要代码块包裹，不要开场白。";

    private final InsightStore store;
    private final AiGatewayClient ai;
    private final UserConfigStore configStore;

    /** 终止请求（协作式）：任务在下一张工单开始前检查到即提前收尾，当前这张跑完不浪费 */
    private final java.util.Set<Long> stopRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 请求终止指定提炼任务（AI 调用不可中断，实际停在下一张开始前） */
    public void requestStop(long taskId) {
        stopRequests.add(taskId);
    }

    public InsightService(InsightStore store, AiGatewayClient ai, UserConfigStore configStore) {
        this.store = store;
        this.ai = ai;
        this.configStore = configStore;
    }

    /**
     * 提炼任务体（TaskExecutor 串行调度）。
     *
     * @param onlyUuids 非 null 时只提炼这些工单（同步后自动增量），null = 全量取待提炼队列
     * @param limit     本次最多提炼张数，null 用默认（自动增量场景传 uuids.size() 语义上即可，仍受 DEFAULT 上限保护）
     * @return 本次提炼成功条数
     */
    public int run(TaskExecutor.TaskContext ctx, String user,
                   List<String> onlyUuids, Integer limit) {
        int cap = limit == null || limit <= 0 ? DEFAULT_BATCH_LIMIT : Math.min(limit, DEFAULT_BATCH_LIMIT);
        List<Map<String, Object>> tickets = onlyUuids == null
                ? store.pendingTickets(cap)
                : store.pendingForUuids(onlyUuids, cap);
        String model = configStore.get(user).getAiModel();
        if (tickets.isEmpty()) {
            log.info("经验提炼：没有待提炼的已解决工单（user={}）", user);
            return 0;
        }
        int n = tickets.size();
        int ok = 0;
        boolean stopped = false;
        log.info("经验提炼开始：user={}，本批 {} 张（仅指定工单：{}）", user, n, onlyUuids != null);
        for (int i = 0; i < n; i++) {
            if (stopRequests.remove(ctx.taskId())) {
                stopped = true;
                break;
            }
            Map<String, Object> t = tickets.get(i);
            String uuid = (String) t.get("uuid");
            ctx.progress(100 * i / n, "提炼经验 " + (i + 1) + "/" + n + "（#" + t.get("number") + "）");
            try {
                String content = ai.chat(user, SYSTEM_PROMPT,
                        buildTicketText(t), CHAT_MAX_TOKENS);
                store.upsert(uuid, (String) t.get("customer_name"), (String) t.get("products"),
                        content, model);
                ok++;
            } catch (Exception e) {
                // 单张失败不入库（下次重跑再试），不中断整批
                log.warn("经验提炼失败（#{}, {}）：{}", t.get("number"), t.get("name"), e.getMessage());
            }
        }
        stopRequests.remove(ctx.taskId()); // 清残留（终止请求在最后一轮检查之后才到的场景）
        log.info("经验提炼{}：成功 {}/{} 张（user={}）", stopped ? "被终止" : "完成", ok, n, user);
        return ok;
    }

    /** 单张工单的提炼语料：标题 / 客户 / 产品线 / 状态 / 描述 / 评论 */
    private String buildTicketText(Map<String, Object> t) {
        StringBuilder sb = new StringBuilder();
        sb.append("【标题】").append(t.get("name")).append('\n');
        sb.append("【客户】").append(orDash(t.get("customer_name")))
                .append("【产品线】").append(orDash(t.get("products")))
                .append("【最终状态】").append(orDash(t.get("status_name"))).append('\n');
        String desc = t.get("description_text") == null ? "" : String.valueOf(t.get("description_text"));
        desc = desc.replaceAll("\\s+", " ").trim();
        if (desc.length() > DESC_MAX) {
            desc = desc.substring(0, DESC_MAX) + "…";
        }
        sb.append("【描述】").append(desc.isEmpty() ? "（无）" : desc).append('\n');
        List<Map<String, Object>> comments = store.comments(uuid(t));
        if (comments.isEmpty()) {
            sb.append("【评论】（无）");
        } else {
            sb.append("【评论记录】\n");
            for (Map<String, Object> c : comments) {
                String text = c.get("text") == null ? "" : String.valueOf(c.get("text"));
                text = text.replaceAll("\\s+", " ").trim();
                if (text.length() > COMMENT_MAX) {
                    text = text.substring(0, COMMENT_MAX) + "…";
                }
                sb.append("- ").append(orDash(c.get("from_name"))).append("：").append(text).append('\n');
            }
        }
        return sb.toString();
    }

    private static String uuid(Map<String, Object> t) {
        return String.valueOf(t.get("uuid"));
    }

    private static String orDash(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? "-" : String.valueOf(o);
    }
}
