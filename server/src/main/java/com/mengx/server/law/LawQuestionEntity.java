package com.mengx.server.law;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 题库（RAG 数据表二）：法考历年真题 + LLM 生成题，source 区分。
 * 一题 = 一个向量块；content_hash 唯一锚点防重复导入。
 */
@Entity
@Table(name = "law_questions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_law_question_hash", columnNames = {"content_hash"})
})
public class LawQuestionEntity {

    public static final String SOURCE_ORIGIN = "origin";
    public static final String SOURCE_GENERATED = "generated";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** origin 真题 / generated 生成题 */
    @Column(nullable = false, length = 20)
    private String source;

    /** 真题年份（生成题为空） */
    @Column
    private Integer year;

    /** 科目（民法/刑法/民诉/刑诉/行政法/商经/三国/理论法） */
    @Column(nullable = false, length = 50)
    private String subject;

    /** 题型：单选/多选/不定项/主观 */
    @Column(nullable = false, length = 20)
    private String questionType;

    /** 难度 1~5 */
    @Column
    private Integer difficulty;

    /** 考点标签（PG text[]） */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] knowledgePoints;

    /** 题干 */
    @Column(nullable = false, columnDefinition = "text")
    private String stem;

    /** 选项（jsonb：单选/多选存 [{"key":"A","text":"…"}]，主观题为空） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String options;

    /** 采分点（jsonb：主观题专用 [{"point":"…","score":3}]，客观题为空） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String scoringPoints;

    /** 参考答案 */
    @Column(columnDefinition = "text")
    private String answer;

    /** 解析 */
    @Column(columnDefinition = "text")
    private String analysis;

    /** 内容幂等哈希 SHA-256（题干+选项规范化） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 语义向量（题干+选项拼接，1024 维） */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected LawQuestionEntity() {}

    public LawQuestionEntity(
            String source, Integer year, String subject, String questionType, Integer difficulty,
            String[] knowledgePoints, String stem, String options, String answer, String analysis,
            String contentHash) {
        this.source = source;
        this.year = year;
        this.subject = subject;
        this.questionType = questionType;
        this.difficulty = difficulty;
        this.knowledgePoints = knowledgePoints;
        this.stem = stem;
        this.options = options;
        this.answer = answer;
        this.analysis = analysis;
        this.contentHash = contentHash;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public void setScoringPoints(String scoringPoints) {
        this.scoringPoints = scoringPoints;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public Integer getYear() { return year; }
    public String getSubject() { return subject; }
    public String getQuestionType() { return questionType; }
    public Integer getDifficulty() { return difficulty; }
    public String[] getKnowledgePoints() { return knowledgePoints; }
    public String getStem() { return stem; }
    public String getOptions() { return options; }
    public String getScoringPoints() { return scoringPoints; }
    public String getAnswer() { return answer; }
    public String getAnalysis() { return analysis; }
    public String getContentHash() { return contentHash; }
    public float[] getEmbedding() { return embedding; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
