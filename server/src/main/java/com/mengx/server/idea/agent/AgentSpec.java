package com.mengx.server.idea.agent;

import java.util.List;

/**
 * 应用级规格快照（应用规格书九维 + 自定义维度；「待聊」= 还没聊到）。
 * 共创推演官每轮产出的全量快照，随轮累积演进；
 * extraDimensions 承载客人临时提出的九维之外的新维度（以客人原话命名）。
 */
public record AgentSpec(String positioning, String persona, String entry,
                        List<String> capabilities, List<String> agents, List<String> tools,
                        String flow, String boundaries, String phases, String acceptance,
                        List<ExtraDimension> extraDimensions) {

    /** 自定义维度：name=维度名（客人原话），content=维度内容 */
    public record ExtraDimension(String name, String content) {}
}
