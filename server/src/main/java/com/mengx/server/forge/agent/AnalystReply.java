package com.mengx.server.forge.agent;

import com.mengx.server.forge.ProductSegment;
import java.util.List;

/**
 * 需求分析师模型原始输出：四态指令（本岗只用 CONTINUE/WAIT/BLOCK）
 * + 待问问题 + 产品深化段 + 待决清单。AiServices 按本 schema 直接反序列化。
 */
public record AnalystReply(
        String instruction,
        String note,
        QuestionToAsk question,
        ProductSegment product,
        List<String> openQuestions) {
}
