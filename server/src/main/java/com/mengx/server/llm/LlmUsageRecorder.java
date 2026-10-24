package com.mengx.server.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用量监听者：注册进 LlmUsage 静态枢纽，把每次模型调用双写两处——
 * llm_usage_log 落库（SQL 查账口径）+ Micrometer 指标（/actuator/prometheus 实时口径）：
 * llm.calls{scene,model,status} 调用量、llm.latency{scene,model} 延迟、llm.tokens{scene,model,direction} 消耗。
 * 双写各自 try/catch：观测失败静默，永不影响主流程。
 */
@Component
public class LlmUsageRecorder implements LlmUsage.Listener {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);

    private final LlmUsageLogRepository repository;
    private final MeterRegistry meters;

    public LlmUsageRecorder(LlmUsageLogRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

    @PostConstruct
    void register() {
        LlmUsage.register(this);
    }

    @Override
    public void onUsage(String scene, String model, int promptTokens, int completionTokens,
            long latencyMs, String status) {
        try {
            repository.save(new LlmUsageLogEntity(scene, model, promptTokens,
                    completionTokens, (int) latencyMs, status));
        } catch (Exception e) {
            log.debug("用量落库失败（忽略）：{}", e.getMessage());
        }
        try {
            String s = scene == null ? "unknown" : scene;
            String m = model == null ? "unknown" : model;
            Counter.builder("llm.calls")
                    .tag("scene", s).tag("model", m).tag("status", status)
                    .register(meters).increment();
            Timer.builder("llm.latency")
                    .tag("scene", s).tag("model", m)
                    .register(meters).record(Duration.ofMillis(latencyMs));
            if (promptTokens > 0) {
                Counter.builder("llm.tokens").tag("scene", s).tag("model", m)
                        .tag("direction", "prompt").register(meters).increment(promptTokens);
            }
            if (completionTokens > 0) {
                Counter.builder("llm.tokens").tag("scene", s).tag("model", m)
                        .tag("direction", "completion").register(meters).increment(completionTokens);
            }
        } catch (Exception ignore) {
            // 指标打点失败不影响主流程
        }
    }
}
