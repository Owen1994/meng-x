package com.mengx.server.forge;

import java.util.List;

/**
 * 领域顾问岗（方案 §6.2/§6.3）：岗链中唯一的插拔槽位，bean 名以 "advisor." 开头按道场类型注册
 * （advisor.legal / advisor.default…），新类型 = 加一个 bean，编排器零改动（照 ChannelRegistry 模式，
 * 选型统一收口在 AdvisorRegistry）。读产品段、写领域设计段（domainDesign 自由结构，§6.5）。
 */
public interface AdvisorAgent extends ForgeAgent {

    /**
     * 道场类型适配信号词：命中工单选型文本（卡标题/一句话/产品段）即选中本岗。
     * 只放「定位级」高置信词（如 法考/法条/判例）——不放内容级词汇，
     * 防止酸儒类道场因语料含「罪」字被误判成法学道场（方案 §1.2 教训）。
     * 兜底顾问（advisor.default）返回空列表。
     */
    List<String> domainSignals();
}
