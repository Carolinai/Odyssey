package org.odyssey.api;

import javax.servlet.http.HttpServletRequest;
import org.odyssey.model.Feedback;
import org.odyssey.service.AuditService;
import org.odyssey.service.AuthService;
import org.odyssey.store.FeedbackStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建议反馈接口：查询（用户名模糊 / 类型 / 时间范围，无条件默认时间倒序）与提交。
 * 提交人以登录态解析（前端不传用户名），并记录审计日志。
 */
@RestController
@RequestMapping("/api/feedbacks")
public class FeedbackController {

    private static final Set<String> VALID_TYPES = new HashSet<>(Arrays.asList(
            Feedback.TYPE_BUG, Feedback.TYPE_FEATURE, Feedback.TYPE_SUGGESTION));

    private final FeedbackStore store;
    private final AuthService authService;
    private final AuditService auditService;

    public FeedbackController(FeedbackStore store, AuthService authService, AuditService auditService) {
        this.store = store;
        this.authService = authService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Feedback> list(@RequestParam(required = false) String username,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) Long from,
                               @RequestParam(required = false) Long to) {
        return store.query(username, type, from, to);
    }

    /** 提交反馈：body 只需 type 与 content；用户名取登录态的显示名（无则回落账号） */
    @PostMapping
    public Feedback add(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String type = body.get("type");
        String content = body.get("content") == null ? "" : body.get("content").trim();
        if (type == null || !VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("反馈类型不合法");
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }
        String user = authService.currentUser(request);
        String userName = authService.currentUserName(request);
        String username = userName == null || userName.trim().isEmpty() ? user : userName;

        long createdAt = store.insert(username, type, content);
        auditService.record(user, userName, "建议反馈", AuditService.EVENT_CREATE,
                null, "类型：" + type + "；内容：" + content);

        Feedback f = new Feedback();
        f.setUserName(username);
        f.setType(type);
        f.setContent(content);
        f.setCreatedAt(createdAt);
        return f;
    }
}
