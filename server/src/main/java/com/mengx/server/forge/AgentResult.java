package com.mengx.server.forge;

/**
 * 岗执行结果（方案 §6.1 四态指令）：编排器据此推进状态机。
 * 产物本体（黑板段）由岗直接写进 ForgeContext.board()，不随本结果传递。
 */
public record AgentResult(
        String instruction,
        String rewriteTarget,
        String rewriteReason,
        String note,
        PendingQuestion question) {

    /** 继续下一岗（黑板已写好本岗段） */
    public static AgentResult cont() {
        return new AgentResult("CONTINUE", null, null, null, null);
    }

    /** 继续下一岗并带备注（审校官 CONTINUE 时携带审校意见摘要，随 REVIEW 状态落工单备注） */
    public static AgentResult cont(String note) {
        return new AgentResult("CONTINUE", null, null, note, null);
    }

    /** 挂起等客人回答（§5.2：一岗一轮只挂一个问题；答案回归属岗带答案重跑） */
    public static AgentResult wait(PendingQuestion question) {
        return new AgentResult("WAIT", null, null, null, question);
    }

    /** 打回指定岗重跑（审校官职权，≤2 轮硬上限由编排器执行） */
    public static AgentResult rewrite(String target, String reason) {
        return new AgentResult("REWRITE", target, reason, null, null);
    }

    /** 阻塞上看板（打回超限或岗判定无法推进，带处置说明） */
    public static AgentResult block(String note) {
        return new AgentResult("BLOCK", null, null, note, null);
    }
}
