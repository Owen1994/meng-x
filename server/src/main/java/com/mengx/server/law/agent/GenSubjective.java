package com.mengx.server.law.agent;

import java.util.List;

/** 出题官主观题输出：案例题干 + 参考答案 + 3~5 采分点（总分恰 10） */
public record GenSubjective(
        String stem,
        String answer,
        String analysis,
        List<GenScoringPoint> scoringPoints,
        List<String> knowledgePoints,
        Integer difficulty) {

    public record GenScoringPoint(String point, Integer score) {}
}
