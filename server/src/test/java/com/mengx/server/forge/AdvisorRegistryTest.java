package com.mengx.server.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 领域顾问注册表单测（零依赖，照 ChannelRegistryTest 模式）：advisor 是岗链中唯一插拔槽位，
 * 选型规则（信号词优先、全不命中兜底 default）是回路 B 的关键路由，此处锁住回归点。
 */
class AdvisorRegistryTest {

    /** 测试用桩顾问：不碰模型，只验注册表选型逻辑 */
    private record StubAdvisor(String name, List<String> signals) implements AdvisorAgent {
        @Override
        public List<String> domainSignals() {
            return signals;
        }

        @Override
        public List<String> reads() {
            return List.of();
        }

        @Override
        public List<String> owns() {
            return List.of("domainDesign");
        }

        @Override
        public AgentResult run(ForgeContext ctx) {
            return AgentResult.cont(); // 注册表测试不执行岗任务
        }
    }

    private final AdvisorAgent legal = new StubAdvisor("advisor.legal", List.of("法考", "法条", "判例"));
    private final AdvisorAgent finance = new StubAdvisor("advisor.finance", List.of("股票", "基金"));
    private final AdvisorAgent fallback = new StubAdvisor("advisor.default", List.of());

    @Test
    void 信号词命中选对应顾问() {
        AdvisorRegistry registry = new AdvisorRegistry(List.of(legal, fallback));
        assertEquals(legal, registry.choose("法考冲刺打卡道场"));
    }

    @Test
    void 多顾问按注册顺序首个信号命中者选中() {
        AdvisorRegistry registry = new AdvisorRegistry(List.of(legal, finance, fallback));
        assertEquals(finance, registry.choose("看盘盯股票的道场"));
        assertEquals(legal, registry.choose("法条背诵道场"));
    }

    @Test
    void 全不命中兜底default顾问() {
        AdvisorRegistry registry = new AdvisorRegistry(List.of(legal, fallback));
        assertEquals(fallback, registry.choose("酸儒骂醒道场"));
    }

    @Test
    void null文本视作空兜底default() {
        AdvisorRegistry registry = new AdvisorRegistry(List.of(legal, fallback));
        assertEquals(fallback, registry.choose(null));
    }

    @Test
    void 无default注册时返回null_编排器按未上线处理() {
        AdvisorRegistry registry = new AdvisorRegistry(List.of(legal));
        assertNull(registry.choose("酸儒骂醒道场"));
    }

    @Test
    void anyRegistered反映槽位是否上线() {
        assertFalse(new AdvisorRegistry(List.of()).anyRegistered());
        assertTrue(new AdvisorRegistry(List.of(fallback)).anyRegistered());
    }

    @Test
    void 空白与null信号词跳过不参与匹配() {
        // Arrays.asList 容纳 null 元素（List.of 不允许），验证注册表对破损信号词的容错
        AdvisorAgent broken = new StubAdvisor("advisor.broken", java.util.Arrays.asList(null, "  "));
        AdvisorRegistry registry = new AdvisorRegistry(List.of(broken, fallback));
        // 破损信号词不抛异常、不误命中，兜底 default
        assertEquals(fallback, registry.choose("随便什么文本"));
    }
}
