package com.mengx.server.forge;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工部专用执行宿主（方案 §7.3）：独立线程池，与主线 SSE 的 boundedElastic、请求线程完全隔离。
 * 参数：core/max = 2/4（单机、单工单串行语义，压并发是 1302 限流的既有教训）；
 * 有界队列 200 + CallerRuns（提交点只有答案到达/手动重试/开机恢复三种，任务绝不能丢，
 * 队列满时由提交线程代跑，表现为该次聊天流确认变慢，可接受）；岗任务 10 分钟超时。
 *
 * 执行纪律（方案 §7.5）：线程池里不存在任何等待——不 sleep 轮询、不 wait 用户、不 hold 长事务；
 * 「等人」只存在于数据库行（WAITING_* 零线程占用）。
 */
@Component
public class ForgeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ForgeExecutor.class);

    /** 岗任务整体超时（防网络半开等僵死；模型调用自身另有官方客户端 callTimeout 兜底） */
    static final Duration STAGE_TIMEOUT = Duration.ofMinutes(10);

    private final ForgeJobRepository jobRepository;
    private final TransactionTemplate tx;
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService watchdog;
    private final AtomicInteger threadSeq = new AtomicInteger();

    public ForgeExecutor(ForgeJobRepository jobRepository, PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                r -> new Thread(r, "forge-executor-" + threadSeq.incrementAndGet()),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "forge-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 提交一个岗执行任务（一次提交 = 一个岗，分钟级完成即归还线程）。
     * 异常与超时统一兜底标 FAILED（CAS：任务已自行转移状态则不误标；断点 stage 保留，可手动重试）。
     */
    public void submitStage(Long jobId, Runnable stageTask) {
        Future<?> future = pool.submit(() -> {
            try {
                stageTask.run();
            } catch (Throwable e) {
                log.warn("工单 {} 岗任务异常（标 FAILED，断点保留）：{}", jobId, e.getMessage());
                failIfRunning(jobId, "岗执行异常：" + abbreviate(e.toString()));
            }
        });
        watchdog.schedule(() -> {
            if (future.cancel(true)) { // 已完成的任务 cancel 返回 false，不会误标
                log.warn("工单 {} 岗任务超时 {} 分钟（标 FAILED，断点保留）", jobId, STAGE_TIMEOUT.toMinutes());
                failIfRunning(jobId, "岗任务超时（" + STAGE_TIMEOUT.toMinutes() + " 分钟），已强制中断");
            }
        }, STAGE_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
    }

    /** RUNNING → FAILED（带处置说明；事务内 CAS，工单已被岗任务自行转移则跳过） */
    private void failIfRunning(Long jobId, String note) {
        try {
            int updated = tx.execute(status -> jobRepository.casFail(
                    jobId, abbreviate(note), Instant.now()));
            if (updated == 0) {
                log.info("工单 {} 已被岗任务自行转移，跳过 FAILED 兜底", jobId);
            }
        } catch (Exception e) {
            log.error("工单 {} FAILED 兜底落库失败：{}", jobId, e.getMessage());
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 490 ? s.substring(0, 490) : s;
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
