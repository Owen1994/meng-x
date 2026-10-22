package com.mengx.server.features;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日一签接口（JWT）：点子铺长出的第一个功能——仙君好运签。
 */
@RestController
@RequestMapping("/api/fortune")
public class FortuneController {

    private final FortuneService fortuneService;

    public FortuneController(FortuneService fortuneService) {
        this.fortuneService = fortuneService;
    }

    /** 今日签状态（drawn=false 表示今天还没抽） */
    @GetMapping("/today")
    public FortuneService.TodayResult today(Authentication auth) {
        return fortuneService.today((Long) auth.getPrincipal());
    }

    /** 抽今日签：幂等，当日重复调用返回同一签（newDraw=false） */
    @PostMapping("/draw")
    public ResponseEntity<FortuneService.FortuneDto> draw(Authentication auth) {
        return ResponseEntity.ok(fortuneService.drawToday((Long) auth.getPrincipal()));
    }
}
