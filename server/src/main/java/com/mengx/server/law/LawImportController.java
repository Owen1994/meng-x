package com.mengx.server.law;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 法学 RAG 数据导入接口（JWT 鉴权，手动触发）：
 * POST /api/law/import {type, law} 单部 或 {type, laws:[...]} 批量：
 * type=statute（法条）/ interpretation（司法解释）/ question（真题，W2）/ note（教材，W2）。
 * 批量按清单依次导入（单失败不影响其余），异步执行立即返回。
 * GET /api/law/import/status：查询进度（lawIndex/laws/total/imported/skipped/failed）。
 */
@RestController
@RequestMapping("/api/law")
public class LawImportController {

    public record ImportRequest(String type, String law, List<String> laws) {}

    private final LawImportService importService;

    public LawImportController(LawImportService importService) {
        this.importService = importService;
    }

    /** 触发数据导入（幂等，重复触发只处理增量） */
    @PostMapping("/import")
    public ResponseEntity<?> trigger(@RequestBody ImportRequest req) {
        if (req == null || req.type() == null || req.type().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "type 必填（statute/interpretation/question/note）"));
        }
        List<String> laws = req.laws() == null || req.laws().isEmpty()
                ? (req.law() == null ? List.of() : List.of(req.law()))
                : req.laws();
        if (laws.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "law 或 laws 必填（如 {\"type\":\"statute\",\"law\":\"民法典\"} 或 {\"type\":\"statute\",\"laws\":[\"民法典\",\"刑法\"]}）"));
        }
        try {
            return ResponseEntity.ok(importService.trigger(req.type().trim(), laws));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 当前/最近一次导入任务进度 */
    @GetMapping("/import/status")
    public LawImportService.Progress status() {
        return importService.progress();
    }
}
