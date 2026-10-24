package com.mengx.server.forge;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 造物线接口（方案 §9，全部 JWT 鉴权——SecurityConfig 的 anyRequest().authenticated() 默认覆盖）。
 * 造物线聊天走独立接口与主回路物理隔离（/api/idea/chat/stream 只服务 MAIN 会话，零回归）；
 * 消息下发为「后台只写库不推、前端轮询」：chat/stream 一次请求完成形态路由+分身确认即关流（方案 A）。
 */
@RestController
@RequestMapping("/api/forge")
public class ForgeController {

    private final ForgeService forgeService;
    private final ForgeChatService chatService;

    public ForgeController(ForgeService forgeService, ForgeChatService chatService) {
        this.forgeService = forgeService;
        this.chatService = chatService;
    }

    /** 当前用户在制工单列表（卡片标题/状态/当前岗/待决问题数；任务面板与图标角标用） */
    @GetMapping("/jobs")
    public List<ForgeService.JobDto> listJobs(Authentication auth) {
        return forgeService.listJobs((Long) auth.getPrincipal());
    }

    /** 工单详情：黑板摘要 + 状态 + 挂起问题 */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> jobDetail(@PathVariable Long jobId) {
        return forgeService.jobDetail(jobId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "工单不存在")));
    }

    /** 造物线消息（role 白名单过滤：只渲染 user/assistant，backlog 等内部消息不出气泡流） */
    @GetMapping("/jobs/{jobId}/messages")
    public ResponseEntity<?> messages(@PathVariable Long jobId) {
        return chatService.listMessages(jobId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "工单不存在")));
    }

    /**
     * 造物线聊天（§9 核心口）：body 含消息文本 + 可选挂起问题标记（选项按钮点击携带）；
     * herald 形态路由 → 答案回归属岗 resume / backlog / 挡回主线；分身回复流式输出后关流。
     * SSE 事件（chunk|done|error）与主回路同构，桥接方式同 IdeaController（逐事件 send+自动关流）。
     */
    @PostMapping(value = "/jobs/{jobId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long jobId,
                           @RequestBody ForgeChatService.ForgeChatRequest req,
                           HttpServletResponse response) {
        // SSE 兜底：通知 Nginx 禁用该响应的代理缓冲（与主回路同款）
        response.setHeader("X-Accel-Buffering", "no");
        var flux = chatService.chat(jobId, req);
        SseEmitter emitter = new SseEmitter(-1L);
        flux.subscribe(
                ev -> {
                    try {
                        emitter.send(ev);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete);
        return emitter;
    }

    /** 未决问题轻量轮询（角标数据源）：待客人拍板的工单与问题数 */
    @GetMapping("/pending")
    public List<ForgeService.PendingDto> pending(Authentication auth) {
        return forgeService.pendingList((Long) auth.getPrincipal());
    }

    /**
     * 终审三动作（§9 POST /jobs/{jobId}/review）：approve 批准出稿 / reject 打回指定岗（带原因）
     * / cancel 搁置谢绝。REVIEW 态专用，v1 全员可操作（§5.5 信任圈口径）。
     */
    @PostMapping("/jobs/{jobId}/review")
    public ResponseEntity<?> review(@PathVariable Long jobId,
                                    @RequestBody ForgeService.ReviewRequest req) {
        ForgeService.ReviewResult r = forgeService.review(jobId, req);
        return ResponseEntity.status(r.status()).body(Map.of("message", r.message()));
    }

    /** 断点重试（§9 POST /jobs/{jobId}/retry）：FAILED/BLOCKED → RUNNING，从当前岗重新提交 */
    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<?> retry(@PathVariable Long jobId) {
        ForgeService.ReviewResult r = forgeService.retry(jobId);
        return ResponseEntity.status(r.status()).body(Map.of("message", r.message()));
    }
}
