package com.mengx.server.forge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 领域顾问注册表（方案 §6.2：照 ChannelRegistry 模式；新类型 = 加一个 AdvisorAgent bean，本类零改动）。
 * 选型规则：非兜底顾问的 domainSignals 逐个信号词匹配工单选型文本（卡标题/一句话/产品段 JSON），
 * 命中即选中；全不命中兜底 advisor.default。每次岗执行都重新选型——幂等岗任务语义下安全
 * （顾问只认产品段输入重写领域段，产品段被答案修订后类型判定应重新发生）。
 */
@Component
public class AdvisorRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdvisorRegistry.class);

    /** 顾问岗 bean 名统一前缀（advisor.legal / advisor.default…） */
    public static final String PREFIX = "advisor.";

    /** 岗链里的抽象槽位名（工单 stage 存槽位，执行时经本注册表选具体顾问） */
    public static final String SLOT = "advisor";

    /** 兜底顾问的 bean 名后缀 */
    private static final String DEFAULT_SUFFIX = ".default";

    private final List<AdvisorAgent> advisors;

    public AdvisorRegistry(List<AdvisorAgent> advisors) {
        this.advisors = advisors;
    }

    /** 是否有任一顾问注册（编排器判「岗链该槽位是否已上线」用） */
    public boolean anyRegistered() {
        return !advisors.isEmpty();
    }

    /** 选型：非兜底顾问信号词优先匹配，全不命中兜底 default；无 default 落户时返回 null（编排器按未上线处理） */
    public AdvisorAgent choose(String text) {
        String t = text == null ? "" : text;
        for (AdvisorAgent advisor : advisors) {
            if (isDefault(advisor)) {
                continue;
            }
            for (String signal : advisor.domainSignals()) {
                if (signal != null && !signal.isBlank() && t.contains(signal)) {
                    log.info("顾问选型：命中信号「{}」→ {}", signal, advisor.name());
                    return advisor;
                }
            }
        }
        return advisors.stream().filter(this::isDefault).findFirst().orElse(null);
    }

    private boolean isDefault(AdvisorAgent advisor) {
        return advisor.name().endsWith(DEFAULT_SUFFIX);
    }
}
