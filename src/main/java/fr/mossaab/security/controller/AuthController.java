package fr.mossaab.security.controller;

import fr.mossaab.security.service.AuthenticationService;
import fr.mossaab.security.service.RefreshTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.text.ParseException;
import java.util.Map;


@Tag(name = "Аутентификация", description = "API для работы с аутентификацией пользователей")
@RestController
@RequestMapping("/authentication")
@SecurityRequirements()
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationService authenticationService;
    private final RefreshTokenService refreshTokenService;


    @Operation(summary = "Регистрация пользователя", description = "Позволяет новому пользователю зарегистрироваться в системе.")
    @PostMapping(value = "/register")
    public ResponseEntity<Object> register(@RequestBody AuthenticationService.RegisterRequest request) throws IOException{
        authenticationService.register(request);
        return ResponseEntity.ok().body("Код активации для активации аккаунта успешно отправлен на почтовый адрес");
    }

    @Operation(summary = "Вход пользователя", description = "Этот endpoint позволяет пользователю войти в систему.")
    @PostMapping("/login")
    public ResponseEntity<Object> authenticate(@RequestBody AuthenticationService.AuthenticationRequest request) {
        AuthenticationService.AuthenticationResponse authenticationResponse = authenticationService.authenticate(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authenticationResponse.getJwtCookie())
                .header(HttpHeaders.SET_COOKIE, authenticationResponse.getRefreshTokenCookie())
                .body("Вход в систему пользователя успешно совершен");
    }


    @Operation(summary = "Обновление токена", description = "Этот endpoint позволяет обновить токен.")
    @PostMapping("/refresh-token")
    public ResponseEntity<AuthenticationService.RefreshTokenResponse> refreshToken(@RequestBody AuthenticationService.RefreshTokenRequest request) {
        return ResponseEntity.ok(refreshTokenService.generateNewToken(request));
    }

    @Operation(summary = "Обновление токена через куки", description = "Этот endpoint позволяет обновить токен с использованием куки.")
    @PostMapping("/refresh-token-cookie")
    public ResponseEntity<Void> refreshTokenCookie(HttpServletRequest request) {
        return authenticationService.refreshTokenUsingCookie(request);
    }


    @Operation(summary = "Выход из системы", description = "Этот endpoint позволяет пользователю выйти из системы.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        return authenticationService.logout(request);
    }


}
