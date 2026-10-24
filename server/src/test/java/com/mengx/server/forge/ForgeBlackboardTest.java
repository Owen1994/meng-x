package com.mengx.server.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 黑板信封单测（零依赖）：黑板是各岗协作的唯一数据总线（分段唯一写者，方案 §6.1），
 * 信封 load/toJson 契约一旦破坏会导致整条岗链断流，此处锁住关键回归点。
 */
class ForgeBlackboardTest {

    private static ForgeJobEntity jobOf(String payload) {
        ForgeJobEntity job = new ForgeJobEntity(7L, "{\"格\":\"快照\"}");
        job.markStatus(ForgeJobEntity.STATUS_RUNNING, "analyst", null);
        if (payload != null) {
            job.setStagePayload(payload);
        }
        return job;
    }

    @Test
    void 空信封新建并继承工单标识() {
        ForgeBlackboard board = ForgeBlackboard.load(jobOf(null));
        assertEquals(7L, board.getCardId());
        assertNotNull(board.getDesignVersion());
        assertEquals(ForgeBlackboard.VERSION, board.getVersion());
        assertTrue(board.getOpenQuestions().isEmpty());
    }

    @Test
    void 损坏信封显式上抛不静默降级() {
        assertThrows(IllegalStateException.class, () -> ForgeBlackboard.load(jobOf("不是JSON")));
    }

    @Test
    void 信封未知字段容忍_向后兼容加段() {
        // 后续版本往信封加新段（如新岗产物），旧代码反序列化不得报错
        String payload = "{\"version\":1,\"cardId\":7,\"futureField\":{\"x\":1}}";
        ForgeBlackboard board = ForgeBlackboard.load(jobOf(payload));
        assertEquals(7L, board.getCardId());
    }

    @Test
    void 信封缺标识时从工单补齐() {
        String payload = "{\"version\":1,\"openQuestions\":[\"待定\"]}";
        ForgeBlackboard board = ForgeBlackboard.load(jobOf(payload));
        assertEquals(7L, board.getCardId());
        assertNotNull(board.getDesignVersion());
    }

    @Test
    void 信封往返_黑板序列化再加载不丢段() {
        ForgeBlackboard board = new ForgeBlackboard(7L, 1);
        ProductSegment product = new ProductSegment(
                "替修行人找同路道友",
                List.of("不做相亲"),
                new ProductSegment.Persona("温和", List.of("例一"), List.of("反例一"), List.of("政治")),
                new ProductSegment.Flow("主线", List.of("分支一", "分支二"), "退出", "记状态"),
                new ProductSegment.Boundaries("兜底", "误触退场", "违规拒答", "隐私保护"),
                List.of(new ProductSegment.DataNeed("道友数据", "用户自备", "冷启动", "月更", "平台")),
                List.of("验收一", "验收二"),
                new ProductSegment.SuccessMetrics("成功样", "关停线"),
                "无敏感风险");
        board.setProduct(product);
        board.setOpenQuestions(List.of("口味待定"));
        board.setDomainDesign(java.util.Map.of("advisor", "legal"));
        board.setEngineering(java.util.Map.of("tables", 3));

        String json = board.toJson();
        ForgeBlackboard reloaded = ForgeBlackboard.load(jobOf(json));

        assertEquals(product, reloaded.getProduct());
        assertEquals(List.of("口味待定"), reloaded.getOpenQuestions());
        assertNotNull(reloaded.getDomainDesign());
        assertNotNull(reloaded.getEngineering());
        assertEquals(board.getVersion(), reloaded.getVersion());
    }
}
