package com.mengx.server.idea.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 卡片装配器（Card Composer）：主对话代理宣布定稿（话术同时含「功能卡」与「整理/炼成」，
 * 由代码级宣布检测判定）后异步调用，把这场需求澄清访谈的成果装配成功能卡 JSON。
 * 本接口只负责装配并返回原文；标题查重、卡数上限、落库等业务闸门在编排层。
 */
public interface CardComposerAgent {

    @SystemMessage("""
            你是应用提案的定稿装配器。主持人已向用户宣布"把这个想法整理成一张功能卡"，\
            你的任务是把这场需求澄清访谈的成果装配成一张结构化卡片。\
            只输出一个 JSON 对象（禁止 markdown 代码块、禁止解释文字）。
            """)
    @UserMessage("""
            装配规则：
            - 字段值从【最近访谈记录】与【设计简报】提取；两边都没覆盖的细节填「待聊」，不要编造；
              主持人说过但用户没认可的内容同样填「待聊」——缺聊的要素不算错，卡上带「待聊」位子。
            - fallback 只记边界情况（出错/极端场景的处理），不记市面替代方案。
            - scale 固定为"应用"；priority 从【优先级建议】取（只能是 高/中/低），为空填"中"。
            - design 从【设计简报】的规格快照（应用规格书九维）誊抄；分号分隔的多项拆回数组；\
              维度为「待聊」的原样写「待聊」；简报缺失时 design 从对话直接整理，聊不到的维度填「待聊」。
            - design.extraDimensions 誊抄简报里九维之外的自定义维度（name=维度名，content=内容）；\
              简报没有自定义维度时填空数组。
            - creativity 是新颖度评分，1~5 的整数。

            输出严格 JSON：
            {"title":"动宾短语标题","summary":"一句话功能说明，说人话","scale":"应用","targetUser":"谁在用","triggerScene":"什么时机","reaction":"要什么反应","fallback":"边界情况","creativity":3,"priority":"高|中|低","design":{"kind":"agent-app","positioning":"替谁解决什么","persona":"分身人设","entry":"怎么进应用：触发/唤起/开场白基调","capabilities":["能力：做什么+做到什么程度"],"agents":["当值分工"],"tools":["工具与数据来源"],"flow":"核心交互流程","boundaries":"边界与兜底","phases":"第一期先做什么","acceptance":"第一期验收标准","extraDimensions":[{"name":"维度名","content":"维度内容"}]}}

            【优先级建议】：{{priority}}

            【设计简报】（内部工作文档）：
            {{briefing}}

            【最近访谈记录】（客人=用户，主持=主对话代理）：
            {{dialog}}
            """)
    String compose(@V("priority") String priority,
                   @V("briefing") String briefing,
                   @V("dialog") String dialog);
}
