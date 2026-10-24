package com.mengx.server.forge.agent;

/**
 * 领域顾问（通用兜底 / 法学）模型原始输出：四态指令 + 待问问题
 * + 领域设计段（自由结构，绑定为 Map，原样落黑板 domainDesign）。
 */
public record AdvisorReply(
        String instruction,
        String note,
        QuestionToAsk question,
        Object domainDesign) {
}
