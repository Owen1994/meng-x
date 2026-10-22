package com.mengx.server.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 分层路由单测（零依赖）：路由模型 mock 为不可用（getIfAvailable → null），
 * 可确定性地验证 L0.5 强意图直达 / L1 信号层 / L3 粘性兜底——
 * 这三层本就是零模型设计，不依赖模型输出；L2 细判留待 llm-eval 批次。
 */
class RoutingServiceTest {

    @SuppressWarnings("unchecked")
    private RoutingService routingWithoutModel() {
        ObjectProvider<ChatModel> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return new RoutingService(provider, new ChannelRegistry(), new ObjectMapper());
    }

    @Test
    void 强意图直达_插字变体出题请求() {
        // 「给我出几道题练练」不含连续"出题"二字，靠"出几道"命中；曾因 L2 误判 host
        assertEquals("law", routingWithoutModel().route("给我出几道题练练", null, null));
    }

    @Test
    void 强意图直达_无视通道粘性() {
        assertEquals("law", routingWithoutModel().route("继续出", "host", null));
        assertEquals("law", routingWithoutModel().route("考考我", ChannelRegistry.DEFAULT_CHANNEL, null));
    }

    @Test
    void L1短路_默认通道闲聊直接留仙官() {
        assertEquals("host", routingWithoutModel().route("今天天气怎么样", null, null));
        assertEquals("host", routingWithoutModel().route("你好呀", null, null));
    }

    @Test
    void L1续通道_法学语境追问留在法学() {
        assertEquals("law", routingWithoutModel().route("那诈骗罪怎么判", "law", null));
        assertEquals("law", routingWithoutModel().route("合同这一条说的是什么", "law", null));
    }

    @Test
    void L3兜底_灰区消息无模型时粘住当前通道() {
        // 灰区（无信号命中），模型不可用 → 粘住 last，宁可不切不可切错
        assertEquals("law", routingWithoutModel().route("帮我看看这个思路行不行", "law", null));
    }

    @Test
    void 空消息粘住当前通道() {
        assertEquals("law", routingWithoutModel().route("  ", "law", null));
        assertEquals("host", routingWithoutModel().route(null, null, null));
    }
}
