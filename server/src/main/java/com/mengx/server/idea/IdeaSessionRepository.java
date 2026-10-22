package com.mengx.server.idea;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaSessionRepository extends JpaRepository<IdeaSessionEntity, Long> {

    /** 校验会话属于当前用户 */
    Optional<IdeaSessionEntity> findByIdAndUserId(Long id, Long userId);

    /** 我的场次列表（新→旧） */
    List<IdeaSessionEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
