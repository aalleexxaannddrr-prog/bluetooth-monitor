package com.alibou.websocket.user;


import com.alibou.websocket.chatroom.ChatRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository repository;
    private final ChatRoomService chatRoomService;

    public void saveUser(User user) {
        user.setStatus(Status.ONLINE);
        repository.save(user);
        log.info("Пользователь {} ONLINE (роль {})",
                user.getNickName(),
                user.getRole());
    }

    public void disconnect(User user) {
        var storedUser = repository.findById(user.getNickName()).orElse(null);
        if (storedUser != null) {
            storedUser.setStatus(Status.OFFLINE);
            repository.save(storedUser);

            // Отключаем все активные чаты с этим пользователем
            chatRoomService.deactivateChatsForUser(storedUser.getNickName());
            log.info("Пользователь {} OFFLINE, активные чаты закрыты",
                    storedUser.getNickName());
        }
    }
    public List<User> findConnectedUsersForEngineer() {
        // 1. Загружаем всех, кто ONLINE
        List<User> onlineUsers = repository.findAllByStatus(Status.ONLINE);

        // 2. Оставляем только тех, кто REGULAR
        //    и кто не занят в активном чате с инженером
        return onlineUsers.stream()
                .filter(user -> user.getRole() == UserRole.REGULAR)
                .filter(user -> !chatRoomService.isUserInActiveChatWithEngineer(user.getNickName()))
                .toList();
    }


    public List<User> findConnectedUsers() {
        // Ищем всех ONLINE
        List<User> onlineUsers = repository.findAllByStatus(Status.ONLINE);

        // Фильтруем тех, кто не "занят" чатом с инженером
        return onlineUsers.stream()
                .filter(user -> !chatRoomService.isUserInActiveChatWithEngineer(user.getNickName()))
                .toList();
    }
}
