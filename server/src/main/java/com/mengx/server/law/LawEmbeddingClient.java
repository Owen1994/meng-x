package com.mengx.server.law;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RAG 向量客户端：千问 MaaS qwen3.7-text-embedding-flash（OpenAI 兼容 POST /embeddings），
 * 1024 维（mengx.law.embedding-* 配置，与 law_* 表 DDL 的 vector(1024) 一致）。
 * 导入与检索共用；调用策略与内部 agent 一致：16 条/批 + 批间限速 + 失败 2 秒退避重试一次。
 */
@Component
public class LawEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LawEmbeddingClient.class);

    /** 每批向量化的文本条数 */
    private static final int BATCH_SIZE = 16;
    /** 批间隔（ms）：主动限速，压平瞬时调用频率 */
    private static final long BATCH_INTERVAL_MS = 500;
    /** 失败退避（ms）：限流时与 ChatModel 的 maxRetries 重试节奏对齐 */
    private static final long RETRY_BACKOFF_MS = 2000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    /** embedding 指标：批耗时 / 失败数 / 向量化条数（千问 MaaS 调用不走 GLM ChatModel，单独打点） */
    private final Timer batchLatency;
    private final Counter errorCounter;
    private final Counter textsCounter;

    public LawEmbeddingClient(
            @Value("${mengx.law.embedding-base-url:}") String baseUrl,
            @Value("${mengx.law.embedding-api-key:}") String apiKey,
            @Value("${mengx.law.embedding-model:qwen3.7-text-embedding-flash}") String model,
            @Value("${mengx.law.embedding-dimension:1024}") int dimensions,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("mengx.law.embedding-base-url 未配置");
        }
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchLatency = Timer.builder("embedding.batch.latency")
                .description("embedding 单批调用耗时（不含限速等待与退避）").register(meterRegistry);
        this.errorCounter = Counter.builder("embedding.errors")
                .description("embedding 批调用失败次数（含退避重试中的失败）").register(meterRegistry);
        this.textsCounter = Counter.builder("embedding.texts")
                .description("累计向量化文本条数").register(meterRegistry);
        log.info("embedding 客户端就绪：model={}, dimension={}, baseUrl={}", model, dimensions, baseUrl);
    }

    /** 批量向量化：内部自动分批（16/批）+ 限速 + 退避重试，返回与输入顺序一致的向量 */
    public List<float[]> embed(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("mengx.law.embedding-api-key 未配置，embedding 不可用");
        }
        textsCounter.increment(texts.size());
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            if (i > 0) {
                sleep(BATCH_INTERVAL_MS);
            }
            result.addAll(embedBatchWithRetry(batch));
            log.info("embedding 进度：{}/{}", Math.min(i + BATCH_SIZE, texts.size()), texts.size());
        }
        return result;
    }

    /** 单条向量化（检索查询用） */
    public float[] embedOne(String text) {
        return embedBatchWithRetry(List.of(text)).get(0);
    }

    private List<float[]> embedBatchWithRetry(List<String> batch) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                sleep(RETRY_BACKOFF_MS);
            }
            long start = System.currentTimeMillis();
            try {
                List<float[]> vectors = embedBatch(batch);
                batchLatency.record(Duration.ofMillis(System.currentTimeMillis() - start));
                return vectors;
            } catch (Exception e) {
                errorCounter.increment();
                log.warn("embedding 批调用失败（第 {} 次，size={}）：{}", attempt + 1, batch.size(), e.getMessage());
            }
        }
        throw new IllegalStateException("embedding 批调用连续失败（已退避重试一次）");
    }

    private List<float[]> embedBatch(List<String> batch) {
        String body;
        try {
            body = objectMapper.writeValueAsString(new BatchRequest(model, batch, dimensions));
        } catch (Exception e) {
            throw new IllegalStateException("embedding 请求体序列化失败", e);
        }
        String response = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parse(response, batch.size());
    }

    /** 解析响应：data[].embedding，按 index 归位后按输入顺序返回 */
    private List<float[]> parse(String response, int expected) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expected) {
                throw new IllegalStateException("embedding 响应条数不符：expect=" + expected + ", actual=" + data.size());
            }
            float[][] ordered = new float[expected][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= expected) {
                    throw new IllegalStateException("embedding 响应 index 越界：" + index);
                }
                JsonNode vec = item.path("embedding");
                float[] values = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    values[i] = (float) vec.get(i).asDouble();
                }
                if (values.length != dimensions) {
                    throw new IllegalStateException("embedding 维度不符：expect=" + dimensions + ", actual=" + values.length);
                }
                ordered[index] = values;
            }
            return List.of(ordered);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("embedding 响应解析失败", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** /embeddings 请求体 */
    private record BatchRequest(String model, List<String> input, int dimensions) {}
}
