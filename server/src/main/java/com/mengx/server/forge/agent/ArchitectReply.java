package com.mengx.server.forge.agent;

/**
 * 架构师模型原始输出：指令 + 工程施工段（自由结构，绑定为 Map，原样落黑板 engineering）。
 */
public record ArchitectReply(
        String instruction,
        String note,
        Object engineering) {
}
