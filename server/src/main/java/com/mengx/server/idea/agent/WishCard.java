package com.mengx.server.idea.agent;

/**
 * 许愿池追问官打磨后的完整功能卡：四要素 + creativity 新颖度（1~5 整数）。
 */
public record WishCard(
        String title,
        String summary,
        String targetUser,
        String triggerScene,
        String reaction,
        String fallback,
        Integer creativity) {
}
