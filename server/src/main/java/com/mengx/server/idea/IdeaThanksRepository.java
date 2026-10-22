package com.mengx.server.idea;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaThanksRepository extends JpaRepository<IdeaThanksEntity, Long> {

    /** 未读感谢卡（进聊天页弹层） */
    List<IdeaThanksEntity> findByUserIdAndReadAtIsNullOrderByIdAsc(Long userId);

    Optional<IdeaThanksEntity> findByIdAndUserId(Long id, Long userId);

    /** 一张功能卡只递一张感谢卡 */
    boolean existsByCardId(Long cardId);

    /** 功能卡被删除时同步清理（调用方需有事务） */
    void deleteByCardIdIn(Collection<Long> cardIds);
}
