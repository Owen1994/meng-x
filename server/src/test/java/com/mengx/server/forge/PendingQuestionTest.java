package com.mengx.server.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 挂起问题信封单测（零依赖）：pending_questions 列是 resume 链路（挂起→回答→归属岗重跑）
 * 的数据契约（方案 §5.2 答案归属铁律的载体），信封读写契约破坏会导致答案路由错岗。
 */
class PendingQuestionTest {

    @Test
    void 无问题时封装为空数组_清空即已答闸() {
        assertEquals("[]", PendingQuestion.envelope(null));
    }

    @Test
    void 信封往返_字段不丢() {
        PendingQuestion q = new PendingQuestion("q1", "analyst", "产品",
                "语料用谁的？", List.of("官话", "白话"), "白话", false);
        List<PendingQuestion> parsed = PendingQuestion.parse(PendingQuestion.envelope(q));
        assertEquals(1, parsed.size());
        assertEquals(q, parsed.get(0));
    }

    @Test
    void 空白与null信封解析为空列表() {
        assertTrue(PendingQuestion.parse(null).isEmpty());
        assertTrue(PendingQuestion.parse("").isEmpty());
        assertTrue(PendingQuestion.parse("   ").isEmpty());
    }

    @Test
    void 损坏信封尽力而为返回空列表() {
        assertTrue(PendingQuestion.parse("不是JSON").isEmpty());
    }

    @Test
    void 信封容忍未知字段_向后兼容加字段() {
        String json = "[{\"id\":\"q1\",\"stage\":\"analyst\",\"type\":\"产品\","
                + "\"question\":\"语料？\",\"options\":[\"A\"],\"defaultValue\":\"A\",\"answered\":false,"
                + "\"futureField\":true}]";
        List<PendingQuestion> parsed = PendingQuestion.parse(json);
        assertEquals(1, parsed.size());
        assertEquals("q1", parsed.get(0).id());
        assertEquals("analyst", parsed.get(0).stage());
    }
}
