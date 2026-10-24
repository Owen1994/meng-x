package com.mengx.server.llm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * LLM 用量埋点枢纽单测（零依赖）：scene 打标是 forge.* / route / chat 全链路分账的依据
 * （方案 §13 scene 级用量入 llm_usage_log），ThreadLocal 语义与推断兜底一旦破坏会导致
 * 用量账目错分，此处锁住回归点。
 */
class LlmUsageTest {

    @AfterEach
    void 清理线程标记() {
        LlmUsage.clear();
        LlmUsage.register(null); // 静态监听者不外泄到其他测试
    }

    @Test
    void 显式打标优先于一切推断() {
        try {
            LlmUsage.set("forge.formatter");
            assertEquals("forge.formatter", LlmUsage.sceneOf("glm-4-flash", true));
            assertEquals("forge.formatter", LlmUsage.sceneOf("glm-4.7", false));
        } finally {
            LlmUsage.clear();
        }
    }

    @Test
    void 未打标时按模型与调用形态推断() {
        assertEquals("chat", LlmUsage.sceneOf("glm-4.7", true));
        assertEquals("route", LlmUsage.sceneOf("glm-4-flash", false));
        assertEquals("sync", LlmUsage.sceneOf("glm-4.7", false));
    }

    @Test
    void 空白打标视作未打标走推断() {
        try {
            LlmUsage.set("   ");
            assertEquals("sync", LlmUsage.sceneOf("glm-4.7", false));
        } finally {
            LlmUsage.clear();
        }
    }

    @Test
    void clear后ThreadLocal不留残留() {
        LlmUsage.set("forge.analyst");
        LlmUsage.clear();
        assertEquals("sync", LlmUsage.sceneOf("glm-4.7", false));
    }

    @Test
    void 监听者异常静默吞掉_观测不影响主流程() {
        LlmUsage.register((scene, model, promptTokens, completionTokens, latencyMs, status) -> {
            throw new RuntimeException("监听者炸了");
        });
        assertDoesNotThrow(() -> LlmUsage.report("forge.reviewer", "glm-4.7", 100, 200, 50, "ok"));
    }

    @Test
    void 无监听者时上报为空操作() {
        assertDoesNotThrow(() -> LlmUsage.report("chat", "glm-4.7", 1, 1, 1, "ok"));
    }

    @Test
    void 监听者正常收到上报参数() {
        String[] received = new String[2];
        LlmUsage.register((scene, model, promptTokens, completionTokens, latencyMs, status) -> {
            received[0] = scene;
            received[1] = status;
        });
        LlmUsage.report("forge.herald", "glm-4.7", 10, 20, 30, "ok");
        assertEquals("forge.herald", received[0]);
        assertEquals("ok", received[1]);
    }
}
