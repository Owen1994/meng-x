package com.mengx.server.idea;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaSessionRepository extends JpaRepository<IdeaSessionEntity, Long> {

    /** 校验会话属于当前用户 */
    Optional<IdeaSessionEntity> findByIdAndUserId(Long id, Long userId);

    /** 按种类过滤的场次列表（新→旧）：历史面板只取 MAIN，FORGE 会话是任务面板不混入 */
    List<IdeaSessionEntity> findByUserIdAndKindOrderByCreatedAtDesc(Long userId, String kind);

    /** 工单 → 造物线会话（一工单一条 FORGE 会话；工单列表/详情关联用） */
    List<IdeaSessionEntity> findByForgeJobIdIn(Collection<Long> forgeJobIds);
}
