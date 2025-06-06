package com.alibou.websocket.user;

import com.alibou.websocket.chatroom.ChatInactivityService;
import com.alibou.websocket.chatroom.ChatRoomService;
import com.alibou.websocket.exception.NickAlreadyOnlineException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final OnlineUserStore store;
    private final ChatRoomService chatRoomService;
    private final ChatInactivityService inactivity;
    private final SimpMessagingTemplate messagingTemplate;

    /** Пользователь заходит */
    public void saveUser(User user, String sessionId) {
        // 1) пытаемся добавить в OnlineUserStore
        if (!store.addIfAbsent(user.getNickName(), sessionId, user)) {
            throw new NickAlreadyOnlineException(
                    "Ник «" + user.getNickName() + "» уже используется");
        }
        // 2) помечаем ONLINE и запускаем «личный» таймер
        user.setStatus(Status.ONLINE);
        if (user.getRole() == UserRole.REGULAR) {
            inactivity.touchRegular(user.getNickName());
        }
        if (user.getRole() == UserRole.ENGINEER) {
//            inactivity.touchEngineer(user.getNickName());
        }
        log.info("Пользователь {} ONLINE (роль {})", user.getNickName(), user.getRole());
    }

    public void forceDisconnect(String nick) {
        // 1) Если есть, узнаём роль, чтобы в OFFLINE послать правильную роль
        Optional<User> opt = store.get(nick);
        UserRole role = opt.map(User::getRole).orElse(UserRole.REGULAR);

        // 2) Отменяем «личный» таймер (engineer или regular)
        if (role == UserRole.ENGINEER) {
            inactivity.cancelEngineer(nick);
        }
        else if (role == UserRole.REGULAR) {
            inactivity.cancelRegular(nick);
        }

        // 3) Удаляем его из OnlineUserStore
        store.forceRemove(nick);

        // 4) Деактивируем все чаты
        chatRoomService.deactivateChatsForUser(nick);

        // 5) Шлём всем OFFLINE
        messagingTemplate.convertAndSend(
                "/topic/public",
                new User(nick, Status.OFFLINE, role)
        );
        log.info("Пользователь {} кикнут администратором", nick);
    }

    /** Пользователь покидает систему (logout или автоматический disconnect) */
    public void disconnect(String nick, String sessionId) {
        // 1) Если есть, узнаём роль пользователя
        Optional<User> opt = store.get(nick);
        UserRole role = opt.map(User::getRole).orElse(UserRole.REGULAR);

        // 2) Отменяем «личный» таймер
        if (role == UserRole.ENGINEER) {
            inactivity.cancelEngineer(nick);
        }
        else if (role == UserRole.REGULAR) {
            inactivity.cancelRegular(nick);
        }

        // 3) Удаляем из OnlineUserStore
        store.remove(nick, sessionId);

        // 4) Деактивируем все чаты, связанные с ним
        chatRoomService.deactivateChatsForUser(nick);

        // 5) Шлём всем OFFLINE
        messagingTemplate.convertAndSend(
                "/topic/public",
                new User(nick, Status.OFFLINE, role)
        );
        log.info("Пользователь {} OFFLINE (sess={})", nick, sessionId);
    }

    /** Список «свободных» REGULAR-ов (для инженера) */
    public List<User> findConnectedUsersForEngineer() {
        return store.all().stream()
                .filter(u -> u.getStatus() == Status.ONLINE)
                .filter(u -> u.getRole() == UserRole.REGULAR)
                .filter(u -> !chatRoomService.isUserInActiveChatWithEngineer(u.getNickName()))
                .toList();
    }

    /** Список всех ONLINE, кто не «занят» чатом с инженером */
    public List<User> findConnectedUsers() {
        return store.all().stream()
                .filter(u -> u.getStatus() == Status.ONLINE)
                .filter(u -> !chatRoomService.isUserInActiveChatWithEngineer(u.getNickName()))
                .toList();
    }
}
