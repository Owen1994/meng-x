package com.mengx.server.idea;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import java.util.ArrayList;
import java.util.List;

/**
 * 主对话代理托管对话窗口（适配 langchain4j 1.x GA 的 ChatMemory 语义）。
 *
 * 背景：GA 版 MessageWindowChatMemory 以 ChatMemoryStore 为唯一真源——add() 每次从 store
 * 现读列表、追加后整窗写回。主对话代理的业务消息表 idea_messages 是 append-only 留痕，不能被框架
 * 整窗覆写（旧 no-op store 组合下，框架 add(systemMessage) 的写回被丢弃，messages() 再读时
 * 人设消失；本轮 user 也会与业务提前落库的行重复一遍）。故直接实现 ChatMemory：
 *
 * - 人设（@SystemMessage 动态模板渲染结果）只存实例字段，每轮 add(SystemMessage) 替换，
 *   不进业务表；ChatMemoryService 按 sessionId 缓存实例，跨轮复用安全。
 * - 对话窗口直读业务表 user/assistant 行（card 占位、counsel 设计简报行不属于对话），
 *   尾部悬空 user（本轮发言已由业务侧提前落库，AiServices 还会把 @UserMessage 再追加一次）
 *   排除，避免同一条在请求里出现两遍；取最后 {@link #WINDOW_MESSAGES} 条滑窗。
 * - add(user/assistant) 不入库：消息留痕由业务收口负责（本轮 user 先落库、assistant 收流后落库）。
 */
public final class IdeaHostChatMemory implements ChatMemory {

    /** 与 IdeaAgentFactory.HOST_MEMORY_WINDOW_MESSAGES 对齐：约 20 轮来回（不含人设行） */
    static final int WINDOW_MESSAGES = 40;

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final Long sessionId;
    private final IdeaMessageRepository messageRepository;

    /** 本轮人设：每轮由 AiServices 经 add(SystemMessage) 刷新（收藏架/设计简报等动态内容随之更新） */
    private SystemMessage systemMessage;

    public IdeaHostChatMemory(Long sessionId, IdeaMessageRepository messageRepository) {
        this.sessionId = sessionId;
        this.messageRepository = messageRepository;
    }

    @Override
    public Object id() {
        return sessionId;
    }

    @Override
    public List<ChatMessage> messages() {
        List<IdeaMessageEntity> rows = messageRepository.findBySessionIdAndRoleInOrderByIdAsc(
                sessionId, List.of(ROLE_USER, ROLE_ASSISTANT));
        // 尾部悬空 user = 已提前落库的本轮发言：排除之，框架随后会把本轮 @UserMessage 追加到请求尾部
        int end = rows.size();
        if (end > 0 && ROLE_USER.equals(rows.get(end - 1).getRole())) {
            end--;
        }
        int from = Math.max(0, end - WINDOW_MESSAGES);
        List<ChatMessage> messages = new ArrayList<>(end - from + 1);
        if (systemMessage != null) {
            messages.add(systemMessage);
        }
        for (IdeaMessageEntity row : rows.subList(from, end)) {
            messages.add(ROLE_ASSISTANT.equals(row.getRole())
                    ? AiMessage.from(row.getContent())
                    : UserMessage.from(row.getContent()));
        }
        return messages;
    }

    @Override
    public void add(ChatMessage message) {
        // 人设每轮随动态模板重渲染，替换即可；user/assistant 的落库归业务收口（见类注释）
        if (message instanceof SystemMessage sm) {
            this.systemMessage = sm;
        }
    }

    @Override
    public void clear() {
        this.systemMessage = null;
    }
}
