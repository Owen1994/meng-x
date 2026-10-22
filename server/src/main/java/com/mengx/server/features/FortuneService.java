package com.mengx.server.features;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Random;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 每日一签：每人每天一支，当日重复抽取返回同一签（幂等）。
 */
@Service
public class FortuneService {

    public record FortuneDto(
            int no, String grade, String title, String text, String interpret,
            String drawDate, boolean newDraw) {}

    public record TodayResult(boolean drawn, FortuneDto fortune) {}

    private final IdeaFortuneDrawRepository drawRepository;
    private final Random random = new Random();

    public FortuneService(IdeaFortuneDrawRepository drawRepository) {
        this.drawRepository = drawRepository;
    }

    /** 今日签（未抽则 drawn=false） */
    public TodayResult today(Long userId) {
        Optional<IdeaFortuneDrawEntity> draw =
                drawRepository.findByUserIdAndDrawDate(userId, LocalDate.now());
        return new TodayResult(draw.isPresent(), draw.map(d -> toDto(d, false)).orElse(null));
    }

    /** 抽今日签：首抽随机并落库；当日已抽返回原签 */
    public FortuneDto drawToday(Long userId) {
        LocalDate date = LocalDate.now();
        Optional<IdeaFortuneDrawEntity> existing =
                drawRepository.findByUserIdAndDrawDate(userId, date);
        if (existing.isPresent()) {
            return toDto(existing.get(), false);
        }
        int no = random.nextInt(FortuneDeck.size()) + 1;
        try {
            IdeaFortuneDrawEntity saved =
                    drawRepository.saveAndFlush(new IdeaFortuneDrawEntity(userId, date, no));
            return toDto(saved, true);
        } catch (DataIntegrityViolationException e) {
            // 并发重复插入兜底：今日其实已抽
            return drawRepository.findByUserIdAndDrawDate(userId, date)
                    .map(d -> toDto(d, false))
                    .orElseThrow(() -> e);
        }
    }

    private FortuneDto toDto(IdeaFortuneDrawEntity d, boolean newDraw) {
        FortuneDeck.Fortune f = FortuneDeck.byNo(d.getFortuneNo());
        return new FortuneDto(
                f.no(), f.grade(), f.title(), f.text(), f.interpret(),
                d.getDrawDate().toString(), newDraw);
    }
}
