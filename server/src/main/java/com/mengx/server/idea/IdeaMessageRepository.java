package com.mengx.server.idea;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaMessageRepository extends JpaRepository<IdeaMessageEntity, Long> {

    /** 恢复一场对话（按发生顺序） */
    List<IdeaMessageEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 某场次某角色的全部消息（炼卡时取仙班锦囊 counsel 用） */
    List<IdeaMessageEntity> findBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);

    /** 会话列表预览：每场第一条访客发言 */
    Optional<IdeaMessageEntity> findFirstBySessionIdAndRoleOrderByIdAsc(Long sessionId, String role);

    void deleteBySessionId(Long sessionId);
}
