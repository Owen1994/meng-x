package com.mengx.server.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** LLM 用量日志（可观测性 P1）：每次模型调用一行，成本核算与延迟分析直接 SQL 查账 */
@Entity
@Table(name = "llm_usage_log")
public class LlmUsageLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 调用场景：chat（主聊流式）/ route（路由）/ extractor（提炼器）/ counsel（双星）/ quiz_gen（出题）/ sync */
    @Column(length = 32)
    private String scene;

    @Column(length = 64)
    private String model;

    @Column(length = 16)
    private String status;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer latencyMs;

    public LlmUsageLogEntity() {}

    public LlmUsageLogEntity(String scene, String model, Integer promptTokens,
            Integer completionTokens, Integer latencyMs, String status) {
        this.scene = scene;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getScene() {
        return scene;
    }

    public String getModel() {
        return model;
    }

    public String getStatus() {
        return status;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }
}
