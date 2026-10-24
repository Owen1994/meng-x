package com.mengx.server.idea;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 会话代际（epoch）守卫：每来一轮用户消息 +1，用于作废跨轮次的异步副作用。
 * 收口预取的设计简报落库前、旁路观察的 EXIT 判定应用前，都必须校验代际未变——
 * 期间用户已发新一轮，则旧上下文的结论一律丢弃，防止营造图回退或误关发现模式。
 * 仅作进程内竞态防护，重启丢失可接受（最坏回到旧有的双落库行为）。
 */
@Component
public class DiscoveryEpochGuard {

    private final Map<Long, Integer> epochs = new ConcurrentHashMap<>();

    /** 新一轮用户消息到达：作废旧一轮在飞的异步结论，返回当前代际 */
    public int bump(Long sessionId) {
        return epochs.merge(sessionId, 1, Integer::sum);
    }

    /** 代际是否仍是观察发起时的那一轮（会话从未登记过视为过期） */
    public boolean isCurrent(Long sessionId, int epoch) {
        Integer current = epochs.get(sessionId);
        return current != null && current == epoch;
    }
}
