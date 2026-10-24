package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 应用提案（Application Proposal）：机会观察器在旁路判定 PROPOSE 后落库的提案，
 * 等待用户点「聊聊看」（许可）才进入发现模式（需求澄清访谈）。
 * 单提议原则：新提案落库前，既有 PENDING 提案被置为 SUPERSEDED。
 */
@Entity
@Table(name = "idea_proposals")
public class IdeaProposalEntity {

    /** 待用户裁决（前端以邀请卡形式展示） */
    public static final String STATUS_PENDING = "PENDING";
    /** 用户已许可：消费即进入发现模式，一次性状态 */
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    /** 用户主动拒绝，或累计 2 条新用户消息无响应后静默归档 */
    public static final String STATUS_DISMISSED = "DISMISSED";
    /** 被同场更新的提案顶替 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Long userId;

    /** 提案标题（一句话，动宾短语） */
    @Column(nullable = false, length = 100)
    private String suggestTitle;

    /** 用户故事：作为<谁>，想要<能力>，以便<价值> */
    @Column(nullable = false, length = 500)
    private String userStory;

    /** 能力缺口：现有能力栈为何满足不了 */
    @Column(nullable = false, length = 500)
    private String gap;

    /** 意图证据：用户亲口说出的原话片段 */
    @Column(nullable = false, length = 500)
    private String signalQuote;

    /**
     * 状态机：PENDING → ACCEPTED / DISMISSED / SUPERSEDED（均为终态， ACCEPTED 消费即开班）。
     */
    // 不用 columnDefinition 带 default：Hibernate 6.6 update 模式会生成 PG 非法 DDL
    //（详见 IdeaSessionEntity 注释）；默认值由 Java 字段初始化保证。
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    /** 观察发起时的会话代际（诊断用，对应 DiscoveryEpochGuard 的计数） */
    @Column(nullable = false)
    private Integer epoch;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 状态落定时间（PENDING 阶段为 null） */
    @Column
    private Instant resolvedAt;

    protected IdeaProposalEntity() {}

    public IdeaProposalEntity(Long sessionId, Long userId, String suggestTitle, String userStory,
                              String gap, String signalQuote, Integer epoch) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.suggestTitle = suggestTitle;
        this.userStory = userStory;
        this.gap = gap;
        this.signalQuote = signalQuote;
        this.epoch = epoch;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSuggestTitle() {
        return suggestTitle;
    }

    public String getUserStory() {
        return userStory;
    }

    public String getGap() {
        return gap;
    }

    public String getSignalQuote() {
        return signalQuote;
    }

    public String getStatus() {
        return status;
    }

    public Integer getEpoch() {
        return epoch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
