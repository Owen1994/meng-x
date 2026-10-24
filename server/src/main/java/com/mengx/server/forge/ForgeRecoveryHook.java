package com.mengx.server.forge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 开机恢复钩子（方案 §7.4 resume 触发源③）：启动时一次性扫描 RUNNING 超时未动的工单
 * （进程被杀残留——正常岗任务 10 分钟必被看门狗标 FAILED），从 stage 断点重跑。
 * 岗任务幂等：只认黑板输入重写本岗段，重跑无副作用。无定时任务，仅此一次。
 */
@Component
public class ForgeRecoveryHook implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ForgeRecoveryHook.class);

    /** 超过该时长未动的 RUNNING 工单视为残留（= 岗任务超时上限 10 分钟 × 冗余） */
    private static final Duration STALE_RUNNING = Duration.ofMinutes(30);

    private final ForgeJobRepository jobRepository;
    private final ForgeExecutor executor;
    private final ObjectProvider<ForgeStageRunner> stageRunner;

    public ForgeRecoveryHook(ForgeJobRepository jobRepository,
                            ForgeExecutor executor,
                            ObjectProvider<ForgeStageRunner> stageRunner) {
        this.jobRepository = jobRepository;
        this.executor = executor;
        this.stageRunner = stageRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ForgeJobEntity> stale = jobRepository.findByStatusAndUpdatedAtBefore(
                ForgeJobEntity.STATUS_RUNNING, Instant.now().minus(STALE_RUNNING));
        if (stale.isEmpty()) {
            return;
        }
        ForgeStageRunner runner = stageRunner.getIfAvailable();
        if (runner == null) {
            // Step 4 编排器落位前的壳行为：只扫描记录，不推进
            log.warn("开机恢复：发现 {} 个超时 RUNNING 工单，岗执行编排器未就绪，仅记录不重跑", stale.size());
            return;
        }
        for (ForgeJobEntity job : stale) {
            log.info("开机恢复：工单 {} 从断点岗 {} 重跑（cardId={}）", job.getId(), job.getStage(), job.getCardId());
            executor.submitStage(job.getId(), () -> runner.runStage(job));
        }
    }
}
