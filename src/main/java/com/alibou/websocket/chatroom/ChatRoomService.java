package com.alibou.websocket.chatroom;

import com.alibou.websocket.chat.ChatMessageService;
import com.alibou.websocket.chat.ChatNotification;
import com.alibou.websocket.user.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полностью in-memory сервис комнат.
 * Все данные живут в оперативной памяти и стираются при рестарте приложения.
 */
@Service
@Slf4j
public class ChatRoomService {

    /** thread-safe карта «pairId → ChatRoom» */
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();

    /* ===== сторонние сервисы ===== */
    private final OnlineUserStore       store;
    private final SimpMessagingTemplate messaging;
    private final ChatInactivityService inactivity;
    private final ChatMessageService    messageService;

    public ChatRoomService(OnlineUserStore store,
                           SimpMessagingTemplate messaging,
                           @Lazy ChatInactivityService inactivity,
                           @Lazy ChatMessageService messageService) {
        this.store          = store;
        this.messaging      = messaging;
        this.inactivity     = inactivity;
        this.messageService = messageService;
    }

    /* =======================================================================
                                   Утилиты
       ======================================================================= */

    /** одинаковый id для пары, порядок сторон не важен */
    public static String pairId(String a, String b) {
        return a.compareTo(b) < 0 ? a + '_' + b : b + '_' + a;
    }

    /* =======================================================================
                              PUBLIC API
       ======================================================================= */

    /**
     * Вернуть chatId пары, опционально создав его.
     * Для self-чата (A → A) chatId = "A_A".
     */
    public Optional<String> getChatRoomId(String senderId,
                                          String recipientId,
                                          boolean createIfMissing) {

        /* self-chat */
        if (senderId.equals(recipientId)) {
            return Optional.of(senderId + '_' + recipientId);
        }

        /* engineer ↔ regular */
        String cid = pairId(senderId, recipientId);

        if (rooms.containsKey(cid)) return Optional.of(cid);
        if (!createIfMissing)       return Optional.empty();

        /* создаём комнату, если ни одна нить ещё не успела */
        rooms.computeIfAbsent(cid,
                k -> new ChatRoom(cid, senderId, recipientId, true));
        log.info("Создана новая комната {} ({} ↔ {})", cid, senderId, recipientId);

        return Optional.of(cid);
    }

    /** Пользователь участвует хоть в одном активном чате? */
    public boolean isUserInActiveChat(String nick) {
        return rooms.values().stream()
                .anyMatch(r -> r.isActive() &&
                        (r.getSenderId().equals(nick) || r.getRecipientId().equals(nick)));
    }

    /** Найти собеседника в единственном активном чате (если он ровно один) */
    public Optional<String> findActivePartner(String nick) {
        return rooms.values().stream()
                .filter(r -> r.isActive() &&
                        (r.getSenderId().equals(nick) || r.getRecipientId().equals(nick)))
                .findFirst()
                .map(r -> r.getSenderId().equals(nick)
                        ? r.getRecipientId() : r.getSenderId());
    }

    /** ⇢ **НОВЫЙ**: список ID всех активных комнат пользователя */
    public List<String> activeRoomsFor(String nick) {
        return rooms.values().stream()
                .filter(ChatRoom::isActive)
                .filter(r -> r.getSenderId().equals(nick) || r.getRecipientId().equals(nick))
                .map(ChatRoom::getChatId)
                .toList();
    }

    /** Инженер «берёт» пользователя в работу */
    public String activateChat(String engineerId, String userId) {

        /* ---------- PATCH: удаляем возможный self-chat REGULAR-а ---------- */
        rooms.remove(userId + '_' + userId);

        String cid = pairId(engineerId, userId);
        ChatRoom r = rooms.computeIfAbsent(cid,
                k -> new ChatRoom(cid, engineerId, userId, true));

        boolean stateChanged = !r.isActive();
        r.setActive(true);

        if (stateChanged) {
            log.info("Пользователь {} ЗАНЯТ инженером {}", userId, engineerId);
            messaging.convertAndSend("/topic/user-status",
                    new UserBusyStatus(userId, true));
        }

        inactivity.touch(engineerId, userId);
        inactivity.cancelEngineer(engineerId);

        /* мгновенно уведомляем REGULAR-а, что чат активирован */
        messaging.convertAndSend(
                "/queue/" + userId,
                new ChatNotification(
                        "0",          // id не важен
                        engineerId,   // от инженера
                        userId,       // REGULAR-у
                        ""            // системное пустое сообщение
                )
        );

        return cid;
    }

    /** Инженер «отпускает» пользователя либо пользователь вышел сам */
    public void deactivatePair(String engineerId, String userId) {

        String cid = pairId(engineerId, userId);
        ChatRoom r = rooms.get(cid);

        boolean stateChanged = r != null && r.isActive();
        if (r != null) r.setActive(false);

        if (stateChanged) {
            log.info("Пользователь {} СВОБОДЕН (инженер {})", userId, engineerId);
            messaging.convertAndSend("/topic/user-status",
                    new UserBusyStatus(userId, false));
        }

        inactivity.cancel(engineerId, userId);
        messageService.clearHistory(engineerId, userId);
        rooms.remove(cid);                    // полностью убираем пару

        /* создаём self-chat для REGULAR-а, чтобы мог писать себе */
        if (!userId.equals(engineerId)) {
            rooms.putIfAbsent(userId + '_' + userId,
                    new ChatRoom(userId + '_' + userId, userId, userId, false));
        }
    }

    /** При отключении пользователя – делаем все его комнаты неактивными */
    public void deactivateChatsForUser(String userId) {
        rooms.values().forEach(r -> {
            if (r.getSenderId().equals(userId) || r.getRecipientId().equals(userId)) {
                r.setActive(false);
            }
        });
        log.info("Все комнаты пользователя {} переведены в неактивные", userId);
    }

    /* =======================================================================
                        Методы, нужные другим слоям
       ======================================================================= */

    /** REGULAR «занят» инженером? */
    public boolean isUserInActiveChatWithEngineer(String userId) {
        return rooms.values().stream()
                .filter(ChatRoom::isActive)
                .filter(r -> r.getSenderId().equals(userId)
                        || r.getRecipientId().equals(userId))
                .anyMatch(r -> {
                    String other = r.getSenderId().equals(userId)
                            ? r.getRecipientId() : r.getSenderId();
                    return store.get(other)
                            .map(u -> u.getRole() == UserRole.ENGINEER)
                            .orElse(false);
                });
    }

    /* =======================================================================
                                  TIMEOUT-callback
       ======================================================================= */

    /**
     * Вызывается ChatInactivityService-ом, если REGULAR не получил
     * «касание» от инженера в течение 15 с.
     */
    public void handleInactivity(String engineerId, String userId) {

        /* 1) удаляем REGULAR-а из онлайна */
        store.forceRemove(userId);

        /* 2) шлём всем OFFLINE */
        messaging.convertAndSend(
                "/topic/public",
                new User(userId, Status.OFFLINE, UserRole.REGULAR)
        );

        /* 3) деактивируем пару */
        deactivatePair(engineerId, userId);

        log.info("Пользователь {} вышел по 15-секундному тайм-ауту", userId);
    }
}
