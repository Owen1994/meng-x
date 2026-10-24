package com.mengx.server.forge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 造场流水线工单（回路 B，方案 §8.1）：一张 scale=应用 且 ACCEPTED 的功能卡一条工单。
 * 状态-线程解耦模型（方案 §7.4）：工单状态只是数据库里的一行——RUNNING 占一个线程分钟级，
 * WAITING_* / 终态零线程占用（挂多久都零资源）；resume 由三触发源事件驱动，无定时任务。
 */
@Entity
@Table(name = "forge_jobs")
public class ForgeJobEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_USER = "WAITING_USER";
    public static final String STATUS_WAITING_OWNER = "WAITING_OWNER";
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源功能卡（一张应用卡至多一条工单，唯一约束兜底开工防重） */
    @Column(nullable = false, unique = true)
    private Long cardId;

    /** 工单状态（8 态，方案 §7.1） */
    @Column(nullable = false, length = 20)
    private String status = STATUS_RUNNING;

    /**
     * 当前岗：analyst / advisor.* / architect / reviewer / format。
     * FAILED 断点重试从该岗重跑（上游黑板段完好，岗任务幂等）。
     */
    @Column(length = 30)
    private String stage;

    /** 设计稿版本：用户决策性回答升版本，挂起点之后的岗整体重跑（方案 §5.4） */
    @Column(nullable = false)
    private Integer designVersion = 1;

    /** 开工时冻结的九格快照 JSON（工部全程只认快照，不追主线后续变更） */
    @Column(nullable = false, columnDefinition = "text")
    private String designSnapshot;

    /** 黑板：施工包信封 JSON（各岗只写自己段，分段唯一写者，方案 §6.1） */
    @Column(columnDefinition = "text")
    private String stagePayload;

    /** 挂起问题数组 JSON（含岗归属/类型/选项/默认值/已答状态——答案归属铁律的数据载体，方案 §5.2） */
    @Column(columnDefinition = "text")
    private String pendingQuestions;

    /** 打回轮次（审校官 REWRITE 硬上限 2 轮，方案 §6.1） */
    @Column(nullable = false)
    private Integer rounds = 0;

    /** 失败/阻塞说明（FAILED 断点重试与 BLOCKED 上看板的处置依据） */
    @Column(length = 500)
    private String errorNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected ForgeJobEntity() {}

    public ForgeJobEntity(Long cardId, String designSnapshot) {
        this.cardId = cardId;
        this.designSnapshot = designSnapshot;
    }

    /** 状态流转（含当前岗与说明；并发防护的条件 UPDATE/CAS 由 Step 3 状态机封装） */
    public void markStatus(String status, String stage, String errorNote) {
        this.status = status;
        if (stage != null) {
            this.stage = stage;
        }
        this.errorNote = errorNote;
        this.updatedAt = Instant.now();
    }

    /** 黑板段写入（先落盘后交棒，方案 §7.5） */
    public void setStagePayload(String stagePayload) {
        this.stagePayload = stagePayload;
        this.updatedAt = Instant.now();
    }

    /** 挂起问题载体写入 */
    public void setPendingQuestions(String pendingQuestions) {
        this.pendingQuestions = pendingQuestions;
        this.updatedAt = Instant.now();
    }

    /** 设计稿升版本（用户决策性回答触发，挂起点之后的岗重跑） */
    public void bumpDesignVersion() {
        this.designVersion = this.designVersion + 1;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCardId() {
        return cardId;
    }

    public String getStatus() {
        return status;
    }

    public String getStage() {
        return stage;
    }

    public Integer getDesignVersion() {
        return designVersion;
    }

    public String getDesignSnapshot() {
        return designSnapshot;
    }

    public String getStagePayload() {
        return stagePayload;
    }

    public String getPendingQuestions() {
        return pendingQuestions;
    }

    public Integer getRounds() {
        return rounds;
    }

    public void setRounds(Integer rounds) {
        this.rounds = rounds;
    }

    public String getErrorNote() {
        return errorNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
