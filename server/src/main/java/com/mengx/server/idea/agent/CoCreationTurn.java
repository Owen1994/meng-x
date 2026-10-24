package com.mengx.server.idea.agent;

/**
 * 共创推演官（CoCreationAgent）的单轮推演结论：
 * 当轮聚焦维度、建议问法、建议方案、缺口、纯确认标记 + 更新后的完整规格快照。
 * 落库前由 DiscoveryService 映射为 DiscoveryState 信封（product/craft 键，存量兼容）。
 */
public record CoCreationTurn(String agenda, String question, String proposal,
                             String missed, Boolean confirmed, String risk,
                             String priority, String decompose,
                             String scale, String approach, AgentSpec spec) {
}
