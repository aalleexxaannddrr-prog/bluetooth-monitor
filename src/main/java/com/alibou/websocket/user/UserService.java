package com.alibou.websocket.user;


import com.alibou.websocket.chatroom.ChatRoomService;
import com.alibou.websocket.exception.NickAlreadyOnlineException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service @RequiredArgsConstructor @Slf4j
public class UserService {

    private final OnlineUserStore   store;
    private final ChatRoomService   chatRoomService;

    /** Пользователь заходит */
    public void saveUser(User user, String sessionId) {
        if (!store.addIfAbsent(user.getNickName(), sessionId, user)) {
            throw new NickAlreadyOnlineException(
                    "Ник «" + user.getNickName() + "» уже используется");
        }
        user.setStatus(Status.ONLINE);
        log.info("Пользователь {} ONLINE (роль {})", user.getNickName(), user.getRole());
    }
    public void forceDisconnect(String nick) {
        store.forceRemove(nick);
        chatRoomService.deactivateChatsForUser(nick);
        log.info("Пользователь {} кикнут администратором", nick);
    }
    /** Пользователь покидает систему (logout или disconnect) */
    public void disconnect(String nick, String sessionId) {
        store.remove(nick, sessionId);
        chatRoomService.deactivateChatsForUser(nick);
        log.info("Пользователь {} OFFLINE (sess={})", nick, sessionId);
    }
    /** Список «свободных» REGULAR-ов (для инженера) */
    public List<User> findConnectedUsersForEngineer() {
        return store.all().stream()
                .filter(u -> u.getStatus()==Status.ONLINE)
                .filter(u -> u.getRole()==UserRole.REGULAR)
                .filter(u -> !chatRoomService.isUserInActiveChatWithEngineer(u.getNickName()))
                .toList();
    }

    /** Список всех ONLINE, кто не «занят» чатом с инженером */
    public List<User> findConnectedUsers() {
        return store.all().stream()
                .filter(u -> u.getStatus()==Status.ONLINE)
                .filter(u -> !chatRoomService.isUserInActiveChatWithEngineer(u.getNickName()))
                .toList();
    }
}

