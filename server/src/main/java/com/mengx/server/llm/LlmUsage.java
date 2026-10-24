package com.mengx.server.llm;

/**
 * LLM 用量观测的静态枢纽（可观测性 P1）：
 * scene 经 ThreadLocal 标记（调用方 try/finally 设置与清理），未标记时按模型+流式推断兜底；
 * 各模型 Bean 注册的 ZhipuUsageListener（langchain4j ChatModelListener）每次调用结束
 * （成功/失败）经 report 上报，LlmUsageRecorder 注册为监听者落库。
 * 监听者异常静默吞掉——观测永不影响主流程。
 */
public final class LlmUsage {

    private static final ThreadLocal<String> SCENE = new ThreadLocal<>();

    private static volatile Listener listener;

    /** 用量上报监听（由 Spring 侧 LlmUsageRecorder 注册实现） */
    public interface Listener {
        void onUsage(String scene, String model, int promptTokens, int completionTokens,
                long latencyMs, String status);
    }

    private LlmUsage() {}

    public static void set(String scene) {
        SCENE.set(scene);
    }

    public static void clear() {
        SCENE.remove();
    }

    /** 场景优先取 ThreadLocal 显式标记，缺省按模型与调用形态推断（flash=路由、流式=主聊、其余=同步调用） */
    static String sceneOf(String model, boolean stream) {
        String explicit = SCENE.get();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (stream) {
            return "chat";
        }
        return model != null && model.contains("flash") ? "route" : "sync";
    }

    public static void register(Listener l) {
        listener = l;
    }

    static void report(String scene, String model, int promptTokens, int completionTokens,
            long latencyMs, String status) {
        Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onUsage(scene, model, promptTokens, completionTokens, latencyMs, status);
        } catch (Exception ignore) {
            // 观测失败不影响主流程
        }
    }
}
