package com.mengx.server.auth;

import com.mengx.server.user.UserEntity;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String phone, String password) {}

    public record LoginResponse(String token, Long userId, String nickname, boolean newAccount) {}

    public record MeResponse(Long userId, String phone, String nickname) {}

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录即注册：账号不存在时自动建号（登录逻辑调整后的唯一入口，不单独开放注册） */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || isBlank(req.phone()) || isBlank(req.password())) {
            return badRequest("手机号和密码不能为空");
        }
        return authService
                .loginOrRegister(req.phone().trim(), req.password())
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(new LoginResponse(
                        authService.issueToken(r.user().getId()),
                        r.user().getId(),
                        r.user().getNickname(),
                        r.created())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "密码不正确（该手机号已注册）")));
    }

    /** 校验 token 并返回当前用户信息，前端用于恢复登录态 */
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return authService
                .findById(userId)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(
                        new MeResponse(u.getId(), u.getPhone(), u.getNickname())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "账号不存在")));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
