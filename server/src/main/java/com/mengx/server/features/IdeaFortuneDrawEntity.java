package com.mengx.server.features;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 每日一签的抽取记录：每位用户每天最多一支（user_id + draw_date 唯一，幂等重抽返回同一签）。
 */
@Entity
@Table(name = "idea_fortune_draws",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "draw_date"}))
public class IdeaFortuneDrawEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate drawDate;

    /** 签号（1-20，对应 FortuneDeck） */
    @Column(nullable = false)
    private Integer fortuneNo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdeaFortuneDrawEntity() {}

    public IdeaFortuneDrawEntity(Long userId, LocalDate drawDate, Integer fortuneNo) {
        this.userId = userId;
        this.drawDate = drawDate;
        this.fortuneNo = fortuneNo;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getDrawDate() {
        return drawDate;
    }

    public Integer getFortuneNo() {
        return fortuneNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
