package com.mengx.server.idea;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaLikeRepository extends JpaRepository<IdeaLikeEntity, Long> {

    boolean existsByCardIdAndUserId(Long cardId, Long userId);

    /** 撤赞（toggle），调用方需有事务 */
    void deleteByCardIdAndUserId(Long cardId, Long userId);

    long countByCardId(Long cardId);

    /** 批量取点赞（看板列表计数用） */
    List<IdeaLikeEntity> findByCardIdIn(Collection<Long> cardIds);

    /** 批量取某人点过的卡（列表标记 liked 用） */
    List<IdeaLikeEntity> findByUserIdAndCardIdIn(Long userId, Collection<Long> cardIds);

    /** 功能卡被删除时同步清理（调用方需有事务） */
    void deleteByCardIdIn(Collection<Long> cardIds);
}
