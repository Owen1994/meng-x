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
 * 知识点笔记（RAG 数据表三）：教材/讲义按小节 ~500 字切块入库。
 * content_hash 唯一锚点防重复导入。
 */
@Entity
@Table(name = "law_notes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_law_note_hash", columnNames = {"content_hash"})
})
public class LawNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 科目（民法/刑法/…） */
    @Column(nullable = false, length = 50)
    private String subject;

    /** 小节主题（如「善意取得的构成要件」） */
    @Column(nullable = false, length = 200)
    private String topic;

    /** 小节正文 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 内容幂等哈希 SHA-256 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 语义向量（1024 维） */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected LawNoteEntity() {}

    public LawNoteEntity(String subject, String topic, String content, String contentHash) {
        this.subject = subject;
        this.topic = topic;
        this.content = content;
        this.contentHash = contentHash;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public String getTopic() { return topic; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public float[] getEmbedding() { return embedding; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
