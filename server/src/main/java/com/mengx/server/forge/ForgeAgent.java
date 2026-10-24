package com.mengx.server.forge;

import java.util.List;

/**
 * 工部岗抽象（方案 §6.2）：一个岗 = 读黑板上游段 → 模型调用 → 只写自己负责的段 → 返回四态指令。
 * 编排（岗序、状态机、CAS 转移）归 ForgeOrchestrator（代码，不是 agent）；
 * LLM 调用走 forge.agent 包的 AiServices 接口（prompt 进、POJO 出，JSON 绑定由框架负责）。
 * agent 之间禁止直接对话，黑板分段唯一写者（owns）。
 */
public interface ForgeAgent {

    /** 岗名：analyst / advisor.* / architect / reviewer（编排器按名路由） */
    String name();

    /** 黑板读段（文档化，供编排器断言与审计；快照/卡片等信封外输入不算黑板段） */
    List<String> reads();

    /** 黑板写段（唯一写者铁律，§6.1；编排器运行时可据此断言越权） */
    List<String> owns();

    /** 执行一次岗任务：读 ctx（工单/黑板/卡片/挂起问题与客人答案），把自己的段写进 ctx.board()，返回四态指令 */
    AgentResult run(ForgeContext ctx);
}
