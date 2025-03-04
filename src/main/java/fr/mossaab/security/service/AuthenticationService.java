package fr.mossaab.security.service;


import com.fasterxml.jackson.annotation.JsonProperty;
import fr.mossaab.security.enums.Role;
import fr.mossaab.security.enums.TokenType;
import fr.mossaab.security.entities.User;
import fr.mossaab.security.repository.UserRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.util.*;


@Service
@Transactional
@RequiredArgsConstructor
public class AuthenticationService {
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);

    public AuthenticationResponse register(RegisterRequest request)  {
        // Проверка существования пользователя с таким же email и activationCode == null
        var existingUserByEmail = userRepository.findByEmail(request.getEmail());

        //public AuthenticationResponse register(RegisterRequest request, MultipartFile image) throws IOException {
        var user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();
        user = userRepository.save(user);
        var jwt = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.createRefreshToken(user.getId());

        var roles = user.getRole().getAuthorities()
                .stream()
                .map(SimpleGrantedAuthority::getAuthority)
                .toList();
        return AuthenticationResponse.builder()
                .accessToken(jwt)
                .email(user.getEmail())
                .id(user.getId())
                .refreshToken(refreshToken.getToken())
                .roles(roles)
                .tokenType(TokenType.BEARER.name())
                .build();
    }

    public ResponseEntity<Void> refreshTokenUsingCookie(HttpServletRequest request) {
        String refreshToken = refreshTokenService.getRefreshTokenFromCookies(request);
        RefreshTokenResponse refreshTokenResponse = refreshTokenService
                .generateNewToken(new RefreshTokenRequest(refreshToken));
        ResponseCookie newJwtCookie = jwtService.generateJwtCookie(refreshTokenResponse.getAccessToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, newJwtCookie.toString())
                .build();
    }

    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String refreshToken = refreshTokenService.getRefreshTokenFromCookies(request);
        if (refreshToken != null) {
            refreshTokenService.deleteByToken(refreshToken);
        }
        ResponseCookie jwtCookie = jwtService.getCleanJwtCookie();
        ResponseCookie refreshTokenCookie = refreshTokenService.getCleanRefreshTokenCookie();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .build();
    }


    /**
     * Аутентифицирует пользователя.
     *
     * @param request Запрос на аутентификацию.
     * @return Ответ с данными пользователя и токенами.
     */
    public AuthenticationResponse authenticate(AuthenticationRequest request) {
        logger.debug("Step 1");
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        logger.debug("Step 2");
        var user = userRepository.findByEmail(request.getEmail()).orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));
        logger.debug("User authenticated: {}, Role: {}", user.getEmail(), user.getRole().name());
        var roles = user.getRole().getAuthorities()
                .stream()
                .map(SimpleGrantedAuthority::getAuthority)
                .toList();
        var jwt = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.createRefreshToken(user.getId());
        ResponseCookie jwtCookie = jwtService.generateJwtCookie(jwt);
        ResponseCookie refreshTokenCookie = refreshTokenService.generateRefreshTokenCookie(refreshToken.getToken());

        return AuthenticationResponse.builder()
                .accessToken(jwt)
                .roles(roles)
                .email(user.getEmail())
                .id(user.getId())
                .refreshToken(refreshToken.getToken())
                .tokenType(TokenType.BEARER.name())
                .jwtCookie(jwtCookie.toString())
                .refreshTokenCookie(refreshTokenCookie.toString())
                .build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefreshTokenRequest {

        /**
         * Токен обновления.
         */
        private String refreshToken;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthenticationResponse {

        /**
         * Уникальный идентификатор пользователя.
         */
        private Long id;

        /**
         * Электронная почта пользователя.
         */
        private String email;

        /**
         * Список ролей пользователя.
         */
        private List<String> roles;

        /**
         * Токен доступа.
         */
        @JsonProperty("access_token")
        private String accessToken;

        /**
         * Токен обновления.
         */
        @JsonProperty("refresh_token")
        private String refreshToken;

        /**
         * Тип токена.
         */
        @JsonProperty("token_type")
        private String tokenType;

        private String jwtCookie;
        private String refreshTokenCookie;
    }
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {

        /**
         * Фамилия пользователя.
         */
        @Schema(description = "Никнейм пользователя", example = "АмурскийТигр1995")
        private String nickname;

        /**
         * Электронная почта пользователя.
         */

        @Schema(description = "Почтовый адрес пользователя", example = "example@gmail.ru")
        private String email;

        /**
         * Пароль пользователя.
         */
        @NotBlank(message = "Пароль не должен быть пустой.")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@$!%*?&#])[A-Za-z\\d@$!%*?&#]{7,50}$",
                message = "Пароль должен быть длиной от 8 до 50 символов, содержать хотя бы одну заглавную букву, одну строчную букву, одну цифру и один специальный символ."
        )
        @Schema(description = "Пароль пользователя", example = "Sasha123!")
        private String password;

    }
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefreshTokenResponse {

        /**
         * Токен доступа.
         */
        @JsonProperty("access_token")
        private String accessToken;

        /**
         * Токен обновления.
         */
        @JsonProperty("refresh_token")
        private String refreshToken;

        /**
         * Тип токена.
         */
        @JsonProperty("token_type")
        private String tokenType;

    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResetPasswordRequest {
        private String code;
        private String newPassword;

        // Геттеры и сеттеры
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthenticationRequest {

        /**
         * Электронная почта пользователя.
         */
        @Schema(example = "Vlad72229@yandex.ru")
        private String email;

        /**
         * Пароль пользователя.
         */
        @Schema(example = "Vlad!123")
        private String password;
    }
}
