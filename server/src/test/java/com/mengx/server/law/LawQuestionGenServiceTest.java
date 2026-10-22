package com.mengx.server.law;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 判分归一化纯函数单测（零依赖，不触模型不触库）：
 * optionLetters 是判分正确性的地基——模型输出"DBA"/"答案是B"等脏格式必须归一为可比对形态。
 */
class LawQuestionGenServiceTest {

    // ---------- optionLetters：脏答案归一化 ----------

    @Test
    void optionLetters_null_与_空白_返回_null() {
        assertNull(LawQuestionGenService.optionLetters(null));
        assertNull(LawQuestionGenService.optionLetters(""));
        assertNull(LawQuestionGenService.optionLetters("  "));
    }

    @Test
    void optionLetters_纯字母原样返回() {
        assertEquals("A", LawQuestionGenService.optionLetters("A"));
        assertEquals("AB", LawQuestionGenService.optionLetters("AB"));
    }

    @Test
    void optionLetters_乱序去重升序() {
        assertEquals("ABD", LawQuestionGenService.optionLetters("DBA"));
        assertEquals("ABCD", LawQuestionGenService.optionLetters("D C B A"));
    }

    @Test
    void optionLetters_容错脏格式() {
        assertEquals("B", LawQuestionGenService.optionLetters("答案是B"));
        assertEquals("ABD", LawQuestionGenService.optionLetters("A、B、D"));
        assertEquals("ABD", LawQuestionGenService.optionLetters("abd"));
        assertEquals("C", LawQuestionGenService.optionLetters("选C吧"));
    }

    @Test
    void optionLetters_无字母返回_null() {
        assertNull(LawQuestionGenService.optionLetters("全选"));
        assertNull(LawQuestionGenService.optionLetters("这道题不会"));
    }

    @Test
    void optionLetters_E_范围外字母忽略() {
        // 选项只有 A-D，模型偶尔吐 E 应被剔除而不是判异常
        assertEquals("ABC", LawQuestionGenService.optionLetters("ABCE"));
    }

    // ---------- isValidAnswer：答案与题型匹配 ----------

    @Test
    void 单选_恰一个字母() {
        assertTrue(LawQuestionGenService.isValidAnswer("A", "单选"));
        assertFalse(LawQuestionGenService.isValidAnswer("AB", "单选"));
        assertFalse(LawQuestionGenService.isValidAnswer(null, "单选"));
        assertFalse(LawQuestionGenService.isValidAnswer("", "单选"));
    }

    @Test
    void 多选与不定项_两到四个字母() {
        assertTrue(LawQuestionGenService.isValidAnswer("AB", "多选"));
        assertTrue(LawQuestionGenService.isValidAnswer("ABD", "多选"));
        assertTrue(LawQuestionGenService.isValidAnswer("ABCD", "不定项"));
        assertFalse(LawQuestionGenService.isValidAnswer("A", "多选"));
        assertFalse(LawQuestionGenService.isValidAnswer("A", "不定项"));
        assertFalse(LawQuestionGenService.isValidAnswer(null, "不定项"));
    }
}
