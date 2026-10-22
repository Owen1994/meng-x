package com.mengx.server.idea;

import java.time.Instant;

/** 功能卡对外结构（看板/确认/导出共用），字段与 §4.5 对齐：刻意没有技术字段 */
public record IdeaCardDto(
        Long id,
        Long sessionId,
        String nickname,
        String title,
        String summary,
        String targetUser,
        String triggerScene,
        String reaction,
        String fallback,
        Integer creativity,
        String status,
        String declineReason,
        Instant createdAt,
        Instant updatedAt,
        String priority,
        String scale,
        String designJson,
        String prdStatus,
        String prdJson,
        long likeCount,
        boolean liked) {

    public static IdeaCardDto from(IdeaCardEntity c) {
        return from(c, 0L, false);
    }

    /** 带点赞信息组装（看板/创意榜用；聊天页等不关心点赞的场景走 from(c)） */
    public static IdeaCardDto from(IdeaCardEntity c, long likeCount, boolean liked) {
        return new IdeaCardDto(
                c.getId(),
                c.getSessionId(),
                c.getNickname(),
                c.getTitle(),
                c.getSummary(),
                c.getTargetUser(),
                c.getTriggerScene(),
                c.getReaction(),
                c.getFallback(),
                c.getCreativity(),
                c.getStatus(),
                c.getDeclineReason(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getPriority(),
                c.getScale(),
                c.getDesignJson(),
                c.getPrdStatus(),
                c.getPrdJson(),
                likeCount,
                liked);
    }
}
