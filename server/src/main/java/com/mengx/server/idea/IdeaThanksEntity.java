package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 感谢卡（叙事闭环）：点子修成（已实现）后，主理人给提出人的答谢。
 * 用户进聊天页时弹层展示，关闭即标已读；一张功能卡至多递一张。
 */
@Entity
@Table(name = "idea_thanks")
public class IdeaThanksEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cardId;

    /** 收卡人 = 该卡提出人（卡片归属场次的主人） */
    @Column(nullable = false)
    private Long userId;

    /** 仙君口吻的感谢文案 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 已读时间（空 = 未读） */
    @Column
    private Instant readAt;

    protected IdeaThanksEntity() {}

    public IdeaThanksEntity(Long cardId, Long userId, String content) {
        this.cardId = cardId;
        this.userId = userId;
        this.content = content;
    }

    public void markRead() {
        this.readAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCardId() {
        return cardId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
