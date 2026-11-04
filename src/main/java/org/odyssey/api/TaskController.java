package org.odyssey.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.store.TaskStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台任务轮询接口（spec D12：页面轮询进度）：
 * - GET /api/tasks       最近任务列表（默认 50 条）
 * - GET /api/tasks/{id}  单任务状态
 *
 * 任务由报告/经验提炼等功能创建（手动触发在其各自接口层记审计日志）。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskStore taskStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskController(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(200, limit));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tasks", taskStore.list(n));
        return m;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> task = taskStore.get(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在：" + id);
        }
        // params_json 解析成对象返回，前端免二次解析；坏 JSON 原样带出
        Object params = task.get("params_json");
        if (params instanceof String && !((String) params).isEmpty()) {
            try {
                task.put("params", mapper.readValue((String) params, Map.class));
            } catch (Exception ignored) {
                task.put("params", params);
            }
        }
        return task;
    }
}
