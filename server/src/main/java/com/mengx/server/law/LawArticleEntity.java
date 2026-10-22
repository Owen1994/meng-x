package com.mengx.server.law;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 法条 / 司法解释（RAG 数据表一）：一条法条 = 一个向量块（天然边界，不再切分）。
 * law_type 区分 statute（法条）与 interpretation（司法解释）；
 * (law_code, article_no) 为修订 upsert 锚点，content_hash 为幂等锚点。
 */
@Entity
@Table(name = "law_articles", uniqueConstraints = {
        @UniqueConstraint(name = "uk_law_article_anchor", columnNames = {"law_code", "article_no"}),
        @UniqueConstraint(name = "uk_law_article_hash", columnNames = {"content_hash"})
})
public class LawArticleEntity {

    public static final String TYPE_STATUTE = "statute";
    public static final String TYPE_INTERPRETATION = "interpretation";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 法律编码（稳定短码，如 civil_code / criminal_code，司法解释同表区分命名） */
    @Column(name = "law_code", nullable = false, length = 50)
    private String lawCode;

    /** 显示名（中华人民共和国民法典） */
    @Column(nullable = false, length = 100)
    private String lawName;

    /** statute 法条 / interpretation 司法解释 */
    @Column(nullable = false, length = 20)
    private String lawType;

    /** 编（民法典「物权编」等，无编结构的法律留空） */
    @Column(length = 100)
    private String book;

    /** 章节路径（含节，如「第一编 总则 / 第一章 基本规定」） */
    @Column(length = 300)
    private String chapter;

    /** 条号原文（第三百一十一条） */
    @Column(name = "article_no", nullable = false, length = 30)
    private String articleNo;

    /** 条号数值（311），用于「第311条」这类编号检索与排序 */
    @Column(name = "article_no_int")
    private Integer articleNoInt;

    /** 条旨（善意取得；无条旨的留空） */
    @Column(length = 200)
    private String title;

    /** 条文全文 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 内容幂等哈希 SHA-256（law_code + article_no + content） */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 施行日期（可空，开源数据缺时不填） */
    @Column
    private LocalDate effectiveDate;

    /** 语义向量（智谱 embedding-3，1024 维） */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected LawArticleEntity() {}

    public LawArticleEntity(
            String lawCode, String lawName, String lawType, String book, String chapter,
            String articleNo, Integer articleNoInt, String title, String content, String contentHash) {
        this.lawCode = lawCode;
        this.lawName = lawName;
        this.lawType = lawType;
        this.book = book;
        this.chapter = chapter;
        this.articleNo = articleNo;
        this.articleNoInt = articleNoInt;
        this.title = title;
        this.content = content;
        this.contentHash = contentHash;
    }

    /** 修订 upsert：内容变化时整体刷新并标记重嵌入 */
    public void revise(LawArticleEntity fresh) {
        this.lawName = fresh.lawName;
        this.book = fresh.book;
        this.chapter = fresh.chapter;
        this.title = fresh.title;
        this.content = fresh.content;
        this.contentHash = fresh.contentHash;
        this.effectiveDate = fresh.effectiveDate;
        this.embedding = fresh.embedding;
        this.updatedAt = Instant.now();
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public Long getId() { return id; }
    public String getLawCode() { return lawCode; }
    public String getLawName() { return lawName; }
    public String getLawType() { return lawType; }
    public String getBook() { return book; }
    public String getChapter() { return chapter; }
    public String getArticleNo() { return articleNo; }
    public Integer getArticleNoInt() { return articleNoInt; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public float[] getEmbedding() { return embedding; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
