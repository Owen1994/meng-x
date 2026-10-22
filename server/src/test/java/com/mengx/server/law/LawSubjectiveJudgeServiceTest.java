package com.mengx.server.law;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 判卷官纯函数单测：verdict 归一化是讲评展示容错的地基（模型输出异常值不许漏到前端） */
class LawSubjectiveJudgeServiceTest {

    @Test
    void verdict归一_合法值原样保留() {
        assertEquals("hit", LawSubjectiveJudgeService.normalizeVerdict("hit"));
        assertEquals("partial", LawSubjectiveJudgeService.normalizeVerdict("partial"));
        assertEquals("miss", LawSubjectiveJudgeService.normalizeVerdict("miss"));
    }

    @Test
    void verdict归一_异常值归miss() {
        assertEquals("miss", LawSubjectiveJudgeService.normalizeVerdict("HIT"));
        assertEquals("miss", LawSubjectiveJudgeService.normalizeVerdict("good"));
        assertEquals("miss", LawSubjectiveJudgeService.normalizeVerdict(""));
        assertEquals("miss", LawSubjectiveJudgeService.normalizeVerdict(null));
    }
}
