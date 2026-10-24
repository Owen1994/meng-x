package com.mengx.server.law.agent;

import java.util.List;

/** 出题官客观题输出：单题（type 随模型输出带回，落库以调用方指定题型为准） */
public record GenQuestion(
        String stem,
        String type,
        List<GenOption> options,
        String answer,
        String analysis,
        List<String> knowledgePoints,
        Integer difficulty) {

    public record GenOption(String key, String text) {}
}
