package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 创意会话：一位用户的一场主理人访谈（"新开一场"即新建会话）。
 * 昵称取自用户账号，卡片署名来源于此。
 */
@Entity
@Table(name = "idea_sessions")
public class IdeaSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 创意合伙人昵称（取自用户账号，冗余存储供卡片署名） */
    @Column(nullable = false, length = 30)
    private String nickname;

    /**
     * 设计模式（orchestrator 状态）：提炼器识别到功能愿望信号时开启，
     * 之后每轮回复前仙官先请仙班出锦囊注入；炼卡或转闲聊时关闭。
     * 用户全程只感知仙官一人，仙班不可见。
     */
    // 带 default false：已存数据的表加列时 PostgreSQL 要求 NOT NULL 列必须有默认值，
    // 否则 Hibernate 的 ddl-auto=update 建列失败、运行时报列不存在
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean designMode = false;

    /**
     * 人设模式（同一仙官的换挡，不分裂会话）：host=仙官本色（默认）/ law=法学备考模式。
     * 前端「法」tab 切换随消息上行，后端落会话；法学问答不触发炼卡链路。
     */
    @Column(nullable = false, columnDefinition = "varchar(16) not null default 'host'")
    private String mode = "host";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdeaSessionEntity() {}

    public IdeaSessionEntity(Long userId, String nickname) {
        this.userId = userId;
        this.nickname = nickname;
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

    public boolean isDesignMode() {
        return designMode;
    }

    public void setDesignMode(boolean designMode) {
        this.designMode = designMode;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
