package com.mengx.server.forge;

import java.util.List;

/**
 * 施工包「产品深化段」契约（方案 §6.5 product 结构）：需求分析师唯一写者。
 * 全部字段可被模型输出直出反序列化（Spring ObjectMapper 容忍未知字段）。
 */
public record ProductSegment(
        String positioning,
        List<String> nonGoals,
        Persona persona,
        Flow flow,
        Boundaries boundaries,
        List<DataNeed> dataNeeds,
        List<String> acceptance,
        SuccessMetrics successMetrics,
        String complianceNotes) {

    /** 分身人设：风格 + 正反例话术 + 绝不碰的话题（内容安全一票否决项） */
    public record Persona(
            String style,
            List<String> positiveExamples,
            List<String> negativeExamples,
            List<String> bannedTopics) {}

    /** 交互流程：主线 + 异常分支（各自带兜底走向）+ 退出路径 + 跨轮状态 */
    public record Flow(
            String happyPath,
            List<String> exceptionBranches,
            String exitPath,
            String crossTurnState) {}

    /** 边界四类拆分：答不了兜底 / 误触发退场 / 合规拒答 / 隐私处理 */
    public record Boundaries(
            String fallback,
            String misrouteExit,
            String complianceRefusal,
            String privacy) {}

    /** 数据需求五要素：要什么 / 来源（空头支票要写明缺口）/ 冷启动 / 更新 / 成本谁担 */
    public record DataNeed(
            String name,
            String source,
            String bootstrap,
            String refresh,
            String costOwner) {}

    /** 成败度量：成功长什么样 / 什么情况该关停 */
    public record SuccessMetrics(
            String success,
            String sunsetCriteria) {}
}
