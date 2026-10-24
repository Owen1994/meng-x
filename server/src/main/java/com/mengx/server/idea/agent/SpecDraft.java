package com.mengx.server.idea.agent;

/**
 * 存档信封 craft 段：应用规格书（AgentSpec）九维 + 自定义维度快照。
 * boundary/acceptance 两个顶层字段仅为兼容旧版功能级三字段存档读取，新稿内容在 spec 内。
 */
public record SpecDraft(String scale, String approach, String boundary, String acceptance,
                        AgentSpec spec) {
}
