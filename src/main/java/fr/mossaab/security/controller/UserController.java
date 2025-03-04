package fr.mossaab.security.controller;

import fr.mossaab.security.entities.*;
import fr.mossaab.security.repository.*;
import fr.mossaab.security.service.MailSender;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Пользователь", description = "Контроллер предоставляет базовые методы доступные пользователю с ролью user")
@RestController
@RequestMapping("/user")
@SecurityRequirements()
@RequiredArgsConstructor
public class UserController {
    private final UserRepository userRepository;
    private final MailSender mailSender; // Добавили

    @Operation(summary = "Получить профиль", description = "Этот эндпоинт возвращает профиль пользователя на основе предоставленного куки.")
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(HttpServletRequest request) {
        // Получаем email пользователя из контекста безопасности
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        // Ищем пользователя в базе данных
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        // Создаем ответ с профилем пользователя
        UserProfileResponse profileResponse = UserProfileResponse.builder()
                .email(user.getEmail())
                .build();

        // Создаем API-ответ с метаинформацией
        ApiResponse<UserProfileResponse> apiResponse = ApiResponse.<UserProfileResponse>builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.OK.value())
                .message("Профиль успешно получен")
                .path(request.getRequestURI())
                .method(request.getMethod())
                .user(user.getEmail())
                .data(profileResponse)
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    // DTO для ответа с профилем пользователя
    @Data
    @Builder
    public static class UserProfileResponse {
        private String nickname;
        private String email;
        private Integer pears;
    }

}
