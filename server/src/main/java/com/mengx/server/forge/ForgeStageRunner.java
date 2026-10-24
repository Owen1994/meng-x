package com.mengx.server.forge;

/**
 * 岗执行入口（方案 §6.2/§7.3）：以岗为单位推进工单——读黑板上游段 → 模型调用 → 写本岗段
 * → 状态转移 → 触发下一岗提交。实现方为编排器（Step 4 落位）。
 *
 * 岗任务幂等：只认黑板输入重写本岗段；FAILED 断点重试与开机恢复都以「从 stage 重跑」调用本入口。
 */
public interface ForgeStageRunner {

    /**
     * 从工单当前断点岗继续执行。调用方（ForgeExecutor）保证单工单串行，
     * 实现内部自行完成读黑板 → 执行 → 落盘 → 状态转移 → 交棒下一岗。
     */
    void runStage(ForgeJobEntity job);
}
