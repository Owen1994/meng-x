package com.mengx.server.forge;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ForgeJobRepository extends JpaRepository<ForgeJobEntity, Long> {

    /** 开工查重：一张应用卡至多一条工单（card_id 唯一约束兜底并发防重） */
    Optional<ForgeJobEntity> findByCardId(Long cardId);

    /**
     * CAS 条件转移（方案 §7.4 防重入核心）：仅当工单当前状态恰为 from 时才转到 to。
     * 返回 0 说明已被其他触发源拿走或已入终态，调用方直接丢弃本次推进——
     * 同一工单任一时刻最多一个岗在跑，无需分布式锁。
     * 调用方需自带事务（Service @Transactional 或 TransactionTemplate）。
     */
    @Modifying
    @Query("update ForgeJobEntity j set j.status = :to, j.updatedAt = :now "
            + "where j.id = :id and j.status = :from")
    int casTransition(@Param("id") Long id,
                      @Param("from") String from,
                      @Param("to") String to,
                      @Param("now") Instant now);

    /** RUNNING → FAILED 兜底（带处置说明，断点 stage 保留）：编排器/执行器异常与超时统一走此口 */
    @Modifying
    @Query("update ForgeJobEntity j set j.status = 'FAILED', j.errorNote = :note, j.updatedAt = :now "
            + "where j.id = :id and j.status = 'RUNNING'")
    int casFail(@Param("id") Long id, @Param("note") String note, @Param("now") Instant now);

    /** 开机恢复扫描：RUNNING 且超时未动的工单（进程被杀残留，从 stage 断点重跑） */
    List<ForgeJobEntity> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    /**
     * 岗结果落盘一体化 CAS（RUNNING 期间的唯一合法转移口）：黑板信封 + 挂起问题 + 状态 + 当前岗
     * + 打回轮次 + 说明一次原子落库——「先落盘后交棒」（§7.5）与防重入（§7.4）合一，
     * affected=0 说明已被其他触发源推进，本方丢弃。调用方需自带事务。
     */
    @Modifying
    @Query("update ForgeJobEntity j set j.stagePayload = :payload, j.pendingQuestions = :questions, "
            + "j.status = :to, j.stage = :stage, j.rounds = :rounds, j.errorNote = :note, j.updatedAt = :now "
            + "where j.id = :id and j.status = 'RUNNING'")
    int casStageResult(@Param("id") Long id,
                      @Param("payload") String payload,
                      @Param("questions") String questions,
                      @Param("to") String to,
                      @Param("stage") String stage,
                      @Param("rounds") int rounds,
                      @Param("note") String note,
                      @Param("now") Instant now);

    /** 在制工单列表：按来源卡批量查（归属经 card→session 链路解析，状态过滤在服务层做） */
    List<ForgeJobEntity> findByCardIdInOrderByUpdatedAtDesc(Collection<Long> cardIds);

    /**
     * resume 防重入 CAS（方案 §7.4 触发源①）：客人答案到达时 WAITING_USER → RUNNING，
     * 挂起岗 stage 不变（答案交抛出岗带答重跑）；设计稿升版本（决策性回答，§5.4）；
     * 挂起问题清空即「已答」幂等闸的落库形态（§7.5，已答再收按自由发言三分类）。
     * affected=0 说明已被其他触发源拿走（并发已答/已手动重试），本方丢弃。
     */
    @Modifying
    @Query("update ForgeJobEntity j set j.status = 'RUNNING', "
            + "j.designVersion = j.designVersion + 1, j.pendingQuestions = null, j.updatedAt = :now "
            + "where j.id = :id and j.status = 'WAITING_USER'")
    int casResume(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 调度 CAS（终审三动作与断点重试共用，§7.2）：仅当工单仍处 from 态时派发到 stage 岗。
     * approve：REVIEW → RUNNING(formatter)；reject：REVIEW → RUNNING(打回岗，rounds 归零重新走岗链)；
     * retry：FAILED/BLOCKED → RUNNING(断点岗保留)。affected=0 说明已被其他操作处理，本方丢弃。
     */
    @Modifying
    @Query("update ForgeJobEntity j set j.status = 'RUNNING', j.stage = :stage, "
            + "j.rounds = :rounds, j.errorNote = :note, j.updatedAt = :now "
            + "where j.id = :id and j.status = :from")
    int casDispatch(@Param("id") Long id,
                    @Param("from") String from,
                    @Param("stage") String stage,
                    @Param("rounds") int rounds,
                    @Param("note") String note,
                    @Param("now") Instant now);
}
