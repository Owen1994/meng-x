package com.mengx.server.idea.agent;

/**
 * 许愿池提炼官产出的功能卡初稿：只依据愿望原文提炼，
 * 说不清的字段填「待聊」，与在架点子相似不算重复（未开发可再创作）。
 */
public record WishDraft(
        String title,
        String summary,
        String targetUser,
        String triggerScene,
        String reaction,
        String fallback) {
}
