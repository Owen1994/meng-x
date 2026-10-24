package com.mengx.server.forge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * 挂起问题（方案 §5.2）：岗判定需要客人拍板的产品/口味/合规取向问题。
 * 载体为 forge_jobs.pending_questions 的 JSON 数组（一岗一轮只挂一个，新问题整体替换旧的），
 * 带岗归属字段——答案永远路由回抛出岗（答案归属铁律的数据载体）。
 * id 以设计稿版本编号（决策性回答升 designVersion，id 天然区分轮次）。
 */
public record PendingQuestion(
        String id,
        String stage,
        String type,
        String question,
        List<String> options,
        String defaultValue,
        boolean answered) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 封装为待决数组 JSON（无问题传 null，落库为空数组——清空即「已答」幂等闸的另一半） */
    public static String envelope(PendingQuestion q) {
        try {
            return MAPPER.writeValueAsString(q == null ? List.of() : List.of(q));
        } catch (Exception e) {
            throw new IllegalStateException("挂起问题序列化失败", e);
        }
    }

    /** 待决数组 JSON → 问题列表（空/损坏返回空列表，详情接口尽力而为） */
    public static List<PendingQuestion> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PendingQuestion> list = MAPPER.readValue(json, new TypeReference<List<PendingQuestion>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
