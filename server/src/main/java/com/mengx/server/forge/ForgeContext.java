package com.mengx.server.forge;

import com.mengx.server.idea.IdeaCardEntity;

/**
 * 岗执行上下文：编排器组装，岗只读不改工单行（状态转移归编排器 CAS）。
 * resume 场景（挂起问题被客人回答后重跑）时 pendingQuestion 与 answer 非 null——
 * 答案语义消化是抛出岗的专业职责（§5.2 答案归属铁律），传话工序只做形态路由。
 */
public record ForgeContext(
        ForgeJobEntity job,
        ForgeBlackboard board,
        IdeaCardEntity card,
        PendingQuestion pendingQuestion,
        String answer) {
}
