package com.mengx.server.law;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 答题记录（W2 答题）：一答一行。user+question 定位当前判分，聚合数据供 W3 画像使用。
 */
@Entity
@Table(name = "law_answer_records", indexes = {
        @Index(name = "idx_lar_user_question", columnList = "user_id, question_id")
})
public class LawAnswerRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 客人所选选项字母（A-D）；主观题为空串 */
    @Column(name = "user_answer", nullable = false, length = 8)
    private String userAnswer;

    /** 主观题作答全文（客观题为空；user_answer 列宽 8 存不下，另开 text 列） */
    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /** 主观题得分率 0~100（客观题为空；及格口径 ≥60，对齐法考主观题 108/180） */
    @Column(name = "score")
    private Integer score;

    /** 主观题逐采分点判定（jsonb：[{point,verdict,comment}]，客观题为空） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    /** 作答耗时秒（对话式答题暂不采集，预留给后续计时模式） */
    @Column(name = "time_cost")
    private Integer timeCost;

    @Column(name = "answered_at", nullable = false, updatable = false)
    private Instant answeredAt = Instant.now();

    protected LawAnswerRecordEntity() {}

    public LawAnswerRecordEntity(Long userId, Long sessionId, Long questionId,
            String userAnswer, boolean correct) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.userAnswer = userAnswer;
        this.correct = correct;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSessionId() { return sessionId; }
    public Long getQuestionId() { return questionId; }
    public String getUserAnswer() { return userAnswer; }
    public String getAnswerText() { return answerText; }
    public boolean isCorrect() { return correct; }
    public Integer getScore() { return score; }
    public String getDetails() { return details; }
    public Instant getAnsweredAt() { return answeredAt; }

    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public void setScore(Integer score) { this.score = score; }
    public void setDetails(String details) { this.details = details; }
}
