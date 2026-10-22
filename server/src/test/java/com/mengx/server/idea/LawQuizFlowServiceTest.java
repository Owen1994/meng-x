package com.mengx.server.idea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 刷题流纯函数单测（零依赖，不触模型不触库）：
 * parseSubmission 是交卷判分的地基——前端拼装「交卷：1B 2AD 3C」与用户手打变体都必须解析正确；
 * isExamIntent / isQuizTrigger 是意图判定的地基——误伤率直接决定路由体验。
 */
class LawQuizFlowServiceTest {

    // ---------- parseSubmission：交卷作答解析 ----------

    @Test
    void 标准格式_全组作答() {
        Map<Integer, String> answers = LawQuizFlowService.parseSubmission("交卷：1B 2AD 3C");
        assertEquals(Map.of(1, "B", 2, "AD", 3, "C"), answers);
    }

    @Test
    void 部分补交() {
        Map<Integer, String> answers = LawQuizFlowService.parseSubmission("交卷：2A 3C");
        assertEquals(Map.of(2, "A", 3, "C"), answers);
    }

    @Test
    void 小写归一() {
        Map<Integer, String> answers = LawQuizFlowService.parseSubmission("交卷：1b 2ad");
        assertEquals(Map.of(1, "B", 2, "AD"), answers);
    }

    @Test
    void 乱序作答_内容等价() {
        Map<Integer, String> answers = LawQuizFlowService.parseSubmission("交卷 3C 1A");
        assertEquals(Map.of(1, "A", 3, "C"), answers);
    }

    @Test
    void 中文冒号与空格容错() {
        assertEquals(Map.of(1, "B"), LawQuizFlowService.parseSubmission("交卷：1：B"));
        assertEquals(Map.of(1, "B"), LawQuizFlowService.parseSubmission("交卷: 1 : B"));
    }

    @Test
    void 无空格连写也能切分() {
        assertEquals(Map.of(1, "B", 2, "AD", 3, "C"),
                LawQuizFlowService.parseSubmission("交卷：1B2AD3C"));
    }

    @Test
    void 同题号后答覆盖前答() {
        assertEquals(Map.of(1, "B"), LawQuizFlowService.parseSubmission("交卷：1A 1B"));
    }

    @Test
    void 无作答内容返回空() {
        assertTrue(LawQuizFlowService.parseSubmission("交卷").isEmpty());
        assertTrue(LawQuizFlowService.parseSubmission("全选C").isEmpty());
        assertTrue(LawQuizFlowService.parseSubmission("").isEmpty());
        assertTrue(LawQuizFlowService.parseSubmission(null).isEmpty());
    }

    // ---------- isExamIntent：备考意图信号（真题检索开关） ----------

    @Test
    void 备考语境命中() {
        assertTrue(LawQuizFlowService.isExamIntent("这道真题怎么解"));
        assertTrue(LawQuizFlowService.isExamIntent("我要开始刷题备考"));
        assertTrue(LawQuizFlowService.isExamIntent("押题命中率高的考点有哪些"));
        assertTrue(LawQuizFlowService.isExamIntent("历年题型怎么分布"));
    }

    @Test
    void 日常咨询不误伤() {
        assertFalse(LawQuizFlowService.isExamIntent("押金不退该怎么办"));
        assertFalse(LawQuizFlowService.isExamIntent("民法典第143条讲的是什么"));
        assertFalse(LawQuizFlowService.isExamIntent(null));
        assertFalse(LawQuizFlowService.isExamIntent(""));
    }

    // ---------- isQuizTrigger：出题触发词 ----------

    @Test
    void 出题触发命中() {
        assertTrue(LawQuizFlowService.isQuizTrigger("给我出几道题练练")); // 插字变体（不含连续"出题"）
        assertTrue(LawQuizFlowService.isQuizTrigger("继续出"));
        assertTrue(LawQuizFlowService.isQuizTrigger("考考我"));
        assertTrue(LawQuizFlowService.isQuizTrigger("来一组刑法的"));
        assertTrue(LawQuizFlowService.isQuizTrigger("下一题"));
    }

    @Test
    void 普通消息不触发() {
        assertFalse(LawQuizFlowService.isQuizTrigger("随便聊聊"));
        assertFalse(LawQuizFlowService.isQuizTrigger("法条解释一下"));
        assertFalse(LawQuizFlowService.isQuizTrigger(null));
    }

    // ---------- isSubjectiveTrigger：主观出题意图（触发词+动作字组合） ----------

    @Test
    void 主观触发命中() {
        assertTrue(LawQuizFlowService.isSubjectiveTrigger("来道案例分析"));
        assertTrue(LawQuizFlowService.isSubjectiveTrigger("出个主观题"));
        assertTrue(LawQuizFlowService.isSubjectiveTrigger("考我一道简答"));
        assertTrue(LawQuizFlowService.isSubjectiveTrigger("想练一道论述"));
    }

    @Test
    void 咨询语境不误伤() {
        assertFalse(LawQuizFlowService.isSubjectiveTrigger("这个案例分析下"));
        assertFalse(LawQuizFlowService.isSubjectiveTrigger("押金怎么退"));
        assertFalse(LawQuizFlowService.isSubjectiveTrigger(null));
    }

    @Test
    void 主观意图优先于客观触发词() {
        // 「考我一道案例分析」含客观触发词「考我」，但应判主观（handle 中主观先判）
        assertTrue(LawQuizFlowService.isSubjectiveTrigger("考我一道案例分析"));
        assertTrue(LawQuizFlowService.isQuizTrigger("考我一道案例分析"));
    }

    // ---------- extractSubjectiveAnswer：主观交卷作答提取 ----------

    @Test
    void 主观交卷_标准提取() {
        assertEquals("甲构成盗窃罪，因秘密窃取他人财物",
                LawQuizFlowService.extractSubjectiveAnswer("交卷：甲构成盗窃罪，因秘密窃取他人财物"));
    }

    @Test
    void 主观交卷_半角冒号与空白容错() {
        assertEquals("abc", LawQuizFlowService.extractSubjectiveAnswer("交卷:  abc"));
        assertEquals("答案", LawQuizFlowService.extractSubjectiveAnswer("交卷答案"));
    }

    @Test
    void 主观交卷_空作答() {
        assertEquals("", LawQuizFlowService.extractSubjectiveAnswer("交卷："));
        assertEquals("", LawQuizFlowService.extractSubjectiveAnswer("交卷"));
        assertEquals("", LawQuizFlowService.extractSubjectiveAnswer(null));
    }
}
