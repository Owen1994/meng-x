package com.mengx.server.features;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaFortuneDrawRepository extends JpaRepository<IdeaFortuneDrawEntity, Long> {

    Optional<IdeaFortuneDrawEntity> findByUserIdAndDrawDate(Long userId, LocalDate drawDate);
}
