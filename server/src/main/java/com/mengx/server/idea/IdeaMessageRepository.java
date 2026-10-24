package com.mengx.server.idea;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaMessageRepository extends JpaRepository<IdeaMessageEntity, Long> {

    /** 恢复一场对话（按发生顺序） */
    List<IdeaMessageEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 某场次某角色的全部消息（取设计简报存档 counsel 用） */
    List<IdeaMessageEntity> findBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);

    /** 某场次指定角色集合的全部消息（托管对话窗口取 user/assistant，过滤 card/counsel 行） */
    List<IdeaMessageEntity> findBySessionIdAndRoleInOrderByIdAsc(Long sessionId, Collection<String> roles);

    /** 某时点之后某角色新增的消息数（提案静默归档阈值判定用） */
    long countBySessionIdAndRoleAndCreatedAtAfter(Long sessionId, String role, Instant after);

    /** 会话列表预览：每场第一条访客发言 */
    Optional<IdeaMessageEntity> findFirstBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);

    /** 一轮对话是否已完整落库（以 assistant 行带同一幂等键为准）；null 幂等键不查 */
    boolean existsBySessionIdAndClientRoundIdAndRole(Long sessionId, String clientRoundId, String role);

    void deleteBySessionId(Long sessionId);
}
