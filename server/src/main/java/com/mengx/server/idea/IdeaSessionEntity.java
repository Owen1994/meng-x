package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 创意会话：一位用户的一场主对话访谈（"新开一场"即新建会话）。
 * 昵称取自用户账号，卡片署名来源于此。
 */
@Entity
@Table(name = "idea_sessions")
public class IdeaSessionEntity {

    /** 主会话：主对话访谈（历史面板/场次列表只列此类） */
    public static final String KIND_MAIN = "MAIN";
    /** 造物线会话：身外身，一工单一条，是任务面板不是聊天历史（方案 §8.2） */
    public static final String KIND_FORGE = "FORGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 客人昵称（取自用户账号，冗余存储供卡片署名） */
    @Column(nullable = false, length = 30)
    private String nickname;

    /**
     * 需求澄清循环开关（orchestrator 状态）：轮次评估识别到功能愿望信号（WISH）时开启，
     * 之后每轮回复前主对话代理先请双官推演状态注入；规格定稿或转闲聊时关闭。
     * 用户全程只感知主对话代理一人，内部 agent 不可见。（DB 列名沿用 design_mode，兼容存量数据）
     */
    // 不用 columnDefinition 带 default：Hibernate 6.6 update 模式会把整串当列类型，
    // 对 PG 生成非法的「set data type boolean not null default false」。
    // 存量表加列时的 default false 由一次性 DDL 保证，新插入靠 Java 字段初始化。
    @Column(name = "design_mode", nullable = false)
    private boolean discoveryActive = false;

    /**
     * 人设模式（同一主对话代理的换挡，不分裂会话）：host=本色陪聊（默认）/ law=法学模式。
     * 通道裁决结果随消息落会话；法学问答不触发规格定稿链路。
     */
    @Column(nullable = false, length = 16)
    private String mode = "host";

    /**
     * 会话种类：MAIN=主会话 / FORGE=造物线会话（身外身）。
     * 历史面板只列 MAIN；FORGE 会话随工单 CANCELLED 归档，不手动删除。
     */
    // 不用 columnDefinition 带 default（原因同 discoveryActive）；存量加列的 default 由一次性 DDL 保证
    @Column(nullable = false, length = 10)
    private String kind = KIND_MAIN;

    /** FORGE 会话绑定的功能卡 id（MAIN 为 null） */
    @Column
    private Long cardId;

    /** FORGE 会话绑定的工单 id（MAIN 为 null） */
    @Column
    private Long forgeJobId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdeaSessionEntity() {}

    public IdeaSessionEntity(Long userId, String nickname) {
        this.userId = userId;
        this.nickname = nickname;
    }

    /** 造物线会话（身外身）：绑定工单与卡片，历史面板不列出 */
    public static IdeaSessionEntity forgeOf(Long userId, String nickname, Long cardId, Long forgeJobId) {
        IdeaSessionEntity session = new IdeaSessionEntity(userId, nickname);
        session.kind = KIND_FORGE;
        session.cardId = cardId;
        session.forgeJobId = forgeJobId;
        return session;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }

    public void setDiscoveryActive(boolean discoveryActive) {
        this.discoveryActive = discoveryActive;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getKind() {
        return kind == null ? KIND_MAIN : kind;
    }

    public Long getCardId() {
        return cardId;
    }

    public Long getForgeJobId() {
        return forgeJobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
