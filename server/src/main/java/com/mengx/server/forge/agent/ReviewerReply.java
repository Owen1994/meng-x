package com.mengx.server.forge.agent;

/**
 * 审校官模型原始输出：CONTINUE（note=审校意见摘要）
 * 或 REWRITE（rewriteTarget=analyst/advisor/architect，rewriteReason=一句话返工要求）。
 */
public record ReviewerReply(
        String instruction,
        String note,
        String rewriteTarget,
        String rewriteReason) {
}
