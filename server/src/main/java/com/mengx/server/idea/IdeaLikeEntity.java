package com.mengx.server.idea;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 功能卡点赞：一人一卡一赞（unique 约束兜底），创意榜按赞数排序。
 */
@Entity
@Table(name = "idea_likes", uniqueConstraints = @UniqueConstraint(columnNames = {"card_id", "user_id"}))
public class IdeaLikeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cardId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdeaLikeEntity() {}

    public IdeaLikeEntity(Long cardId, Long userId) {
        this.cardId = cardId;
        this.userId = userId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
