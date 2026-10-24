package com.mengx.server.idea;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaCardRepository extends JpaRepository<IdeaCardEntity, Long> {

    /** 看板默认排序：创意值优先，再看新（设计方案 §8） */
    List<IdeaCardEntity> findAllByOrderByCreativityDescCreatedAtDesc();

    /** 点子收藏架：只取创意值最高的前 N 张（避免全表加载撑爆 prompt） */
    List<IdeaCardEntity> findTop15ByOrderByCreativityDescCreatedAtDesc();

    /** 近期卡片分页（按 id 倒序） */
    List<IdeaCardEntity> findAllByOrderByIdDesc(org.springframework.data.domain.Pageable pageable);

    /** 我的点子墙：本人历场定稿的卡（新→旧） */
    List<IdeaCardEntity> findBySessionIdInOrderByCreatedAtDesc(Collection<Long> sessionIds);

    List<IdeaCardEntity> findByStatusOrderByCreativityDescCreatedAtDesc(String status);

    /** 恢复本场对话已提炼的卡片（聊天页刷新/回访后还原） */
    List<IdeaCardEntity> findBySessionIdOrderByIdDesc(Long sessionId);

    Optional<IdeaCardEntity> findByIdAndSessionId(Long id, Long sessionId);

    long countBySessionId(Long sessionId);
}
