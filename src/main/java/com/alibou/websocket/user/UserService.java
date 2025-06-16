package com.alibou.websocket.user;

import com.alibou.websocket.chatroom.ChatInactivityService;
import com.alibou.websocket.chatroom.ChatRoomService;
import com.alibou.websocket.exception.NickAlreadyOnlineException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final OnlineUserStore        store;
    private final ChatRoomService        chatRoomService;
    private final ChatInactivityService  inactivity;
    private final SimpMessagingTemplate  messagingTemplate;

    /* =======================================================================
                                 LOGIN
       ======================================================================= */

    /** Пользователь заходит */
    public void saveUser(User user, String sessionId) {
        /* 1) пытаемся добавить в OnlineUserStore */
        if (!store.addIfAbsent(user.getNickName(), sessionId, user)) {
            throw new NickAlreadyOnlineException(
                    "Ник «" + user.getNickName() + "» уже используется");
        }

        /* 2) помечаем ONLINE и запускаем «личный» таймер */
        user.setStatus(Status.ONLINE);
        if (user.getRole() == UserRole.REGULAR) {
            inactivity.touchRegular(user.getNickName());
        }

        /* 3) логируем */
        log.info("ONLINE  ⇢ {}@{} role={}", user.getNickName(), sessionId, user.getRole());
    }

    /* =======================================================================
                                 FORCE KICK
       ======================================================================= */

    public void forceDisconnect(String nick) {
        /* 1) определяем роль (если ещё в Store) */
        Optional<User> opt  = store.get(nick);
        UserRole       role = opt.map(User::getRole).orElse(UserRole.REGULAR);

        /* 2) собираем отладочную инфу до отмены таймеров */
        Map<String, Long> tLeft = inactivity.timersFor(nick);
        List<String>      rooms = chatRoomService.activeRoomsFor(nick);

        /* 3) отменяем «личный» таймер */
        if (role == UserRole.ENGINEER) inactivity.cancelEngineer(nick);
        else                           inactivity.cancelRegular(nick);

        /* 4) удаляем из OnlineUserStore */
        store.forceRemove(nick);

        /* 5) деактивируем все чаты */
        chatRoomService.deactivateChatsForUser(nick);

        /* 6) лог + OFFLINE всем */
        log.info("OFFLINE ⇢ {}@- role={} timers={} rooms={}", nick, role, tLeft, rooms);
        messagingTemplate.convertAndSend("/topic/public",
                new User(nick, Status.OFFLINE, role));
    }

    /* =======================================================================
                               USER DISCONNECT
       ======================================================================= */

    /** Добровольный logout или SessionDisconnectEvent */
    public void disconnect(String nick, String sessionId) {
        Optional<User> opt  = store.get(nick);
        UserRole       role = opt.map(User::getRole).orElse(UserRole.REGULAR);

        /* 1) отладочная инфа (до отмены таймеров) */
        Map<String, Long> tLeft = inactivity.timersFor(nick);
        List<String>      rooms = chatRoomService.activeRoomsFor(nick);

        /* 2) отменяем «личный» таймер */
        if (role == UserRole.ENGINEER) inactivity.cancelEngineer(nick);
        else                           inactivity.cancelRegular(nick);

        /* 3) удаляем из Store */
        store.remove(nick, sessionId);

        /* 4) деактивируем все его комнаты */
        chatRoomService.deactivateChatsForUser(nick);

        /* 5) лог + OFFLINE всем */
        log.info("OFFLINE ⇢ {}@{} role={} timers={} rooms={}",
                nick, sessionId, role, tLeft, rooms);
        messagingTemplate.convertAndSend("/topic/public",
                new User(nick, Status.OFFLINE, role));
    }

    /* =======================================================================
                           QUERIES ДЛЯ CONTROLLER-ОВ
       ======================================================================= */

    /** «Свободные» REGULAR-ы (видно инженеру) */
    public List<User> findConnectedUsersForEngineer() {
        return store.all().stream()
                .filter(u -> u.getStatus() == Status.ONLINE)
                .filter(u -> u.getRole() == UserRole.REGULAR)
                .filter(u -> !chatRoomService.isUserInActiveChatWithEngineer(u.getNickName()))
                .toList();
    }

    /** Все ONLINE, кто не «занят» чатом с инженером */
    public List<User> findConnectedUsers() {
        return store.all().stream()
                .filter(u -> u.getStatus() == Status.ONLINE)
                .filter(u -> !chatRoomService.isUserInActiveChatWithEngineer(u.getNickName()))
                .toList();
    }
}
