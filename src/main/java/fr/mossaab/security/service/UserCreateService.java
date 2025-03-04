package fr.mossaab.security.service;
import fr.mossaab.security.entities.User;
import fr.mossaab.security.enums.Role;
import fr.mossaab.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
@Service
public class UserCreateService {

    @Autowired
    private UserRepository userRepository;
    private static final Logger logger = LoggerFactory.getLogger(UserCreateService.class);

    public void createUsers() {
        if (userRepository.count() == 0) {
            createUser(501L, "Vlad72229@yandex.ru",
                    "$2a$10$QGl4Wtd20zVUu3BRYqBs5uGCsWDE0rvabE2I/XBWxQl0/NOdGwILS",null,Role.ADMIN);
            logger.debug("User create: ", Role.ADMIN);
        }
    }

    private void createUser(Long id,String email,
                            String password, String activationCode, Role role) {
        try {

            User user = User.builder()
                    .id(id) // Предполагается, что id уже задан
                    .email(email)
                    .password(password) // Пароль уже зашифрован
                    .role(role)
                    .build();
            logger.debug("User create: ", role);
            logger.debug("User create: ", user.getRole());
            userRepository.save(user);
        } catch (Exception e) {
            // Обработка ошибок (например, вывод в лог)
            e.printStackTrace();
        }
    }
}
