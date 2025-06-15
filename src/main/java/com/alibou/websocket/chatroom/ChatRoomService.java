//package com.alibou.websocket.chatroom;
//
//import com.alibou.websocket.chat.ChatMessageService;
//import com.alibou.websocket.user.OnlineUserStore;
//import com.alibou.websocket.user.Status;
//import com.alibou.websocket.user.User;
//import com.alibou.websocket.user.UserRole;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.annotation.Lazy;
//import org.springframework.messaging.simp.SimpMessagingTemplate;
//import org.springframework.stereotype.Service;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.concurrent.ConcurrentHashMap;
//
//@Service
//@Slf4j
//public class ChatRoomService {
//    private static final Map<String, Object> CHAT_LOCKS = new ConcurrentHashMap<>();
//    private final ChatRoomRepository chatRoomRepository;
//    private final OnlineUserStore store;
//    private final SimpMessagingTemplate messagingTemplate;
//    private final ChatInactivityService inactivity;
//
//    // Ленивое подключение, чтобы не было циклических зависимостей
//    private final @Lazy ChatMessageService messageService;
//
//    public ChatRoomService(ChatRoomRepository chatRoomRepository,
//                           OnlineUserStore store,
//                           SimpMessagingTemplate messagingTemplate,
//                           @Lazy ChatInactivityService inactivity,
//                           @Lazy ChatMessageService messageService) {
//        this.chatRoomRepository = chatRoomRepository;
//        this.store = store;
//        this.messagingTemplate = messagingTemplate;
//        this.inactivity = inactivity;
//        this.messageService = messageService;
//    }
//
//    /**
//     * Обрабатывает тайм-аут: если REGULAR не писал инженер 15 секунд,
//     * выкидывает этого REGULAR-а из онлайна и деактивирует пару.
//     */
//    public void handleInactivity(String engineerId, String userId) {
//        // 1) Снимаем REGULAR-а из онлайна
//        store.forceRemove(userId);
//
//        // 2) Оповещаем всех, что REGULAR стал OFFLINE
//        messagingTemplate.convertAndSend(
//                "/topic/public",
//                new User(userId, Status.OFFLINE, UserRole.REGULAR)
//        );
//
//        // 3) Гасим чат engineer ↔ user
//        deactivatePair(engineerId, userId);
//
//        log.info("Пользователь {} вышел по 15-секундному тайм-ауту", userId);
//    }
//
//    public boolean isUserInActiveChat(String nick) {
//        return chatRoomRepository
//                .findAllBySenderIdAndActiveTrue(nick).stream()
//                .anyMatch(ChatRoom::isActive)
//                || chatRoomRepository
//                .findAllByRecipientIdAndActiveTrue(nick).stream()
//                .anyMatch(ChatRoom::isActive);
//    }
//
//    /** Найти собеседника в активном чате (если он ровно один) */
//    public Optional<String> findActivePartner(String nick) {
//        return chatRoomRepository
//                .findAllBySenderIdAndActiveTrue(nick).stream()
//                .findFirst()
//                .map(ChatRoom::getRecipientId);
//    }
//
//    public Optional<String> getChatRoomId(String senderId,
//                                          String recipientId,
//                                          boolean createIfMissing) {
//
//        /* self-chat */
//        if (senderId.equals(recipientId)) {
//            return Optional.of(senderId + "_" + recipientId);
//        }
//
//        String cid  = pairId(senderId, recipientId);            // A_B или B_A
//        if (!chatRoomRepository.findAllByChatId(cid).isEmpty()) // уже есть
//            return Optional.of(cid);
//
//        if (!createIfMissing)                                   // не создавать?
//            return Optional.empty();
//
//        /* создаём комнату под локом → ровно один поток выполнит вставку */
//        Object lock = CHAT_LOCKS.computeIfAbsent(cid, k -> new Object());
//        synchronized (lock) {
//            if (chatRoomRepository.findAllByChatId(cid).isEmpty()) {
//                createChatId(senderId, recipientId);            // две зеркальные записи
//            }
//        }
//        return Optional.of(cid);
//    }
//
//    /**
//     * Создание chatId и сохранение записей в БД.
//     */
//    // Вставка изменений только в нужные места
//
//// 1. 🔁 В createChatId:
//    private String createChatId(String a, String b) {
//
//        String chatId = pairId(a, b);          // A_B  или  B_A
//        Object lock   = CHAT_LOCKS.computeIfAbsent(chatId, k -> new Object());
//
//        synchronized (lock) {
//            // если кто-то уже успел вставить – просто выходим
//            if (!chatRoomRepository.findAllByChatId(chatId).isEmpty()) {
//                return chatId;
//            }
//
//            // иначе пишем ДВЕ зеркальные строки
//            ChatRoom r1 = ChatRoom.builder()
//                    .chatId(chatId).senderId(a).recipientId(b).active(true).build();
//            ChatRoom r2 = ChatRoom.builder()
//                    .chatId(chatId).senderId(b).recipientId(a).active(true).build();
//            chatRoomRepository.save(r1);
//            chatRoomRepository.save(r2);
//            log.info("Создана новая комната {} ({} ↔ {})", chatId, a, b);
//            return chatId;
//        }
//    }
//
//
//
//    public boolean isUserInActiveChatWithEngineer(String userId) {
//        List<ChatRoom> allActiveRooms = new ArrayList<>();
//        allActiveRooms.addAll(chatRoomRepository.findAllBySenderIdAndActiveTrue(userId));
//        allActiveRooms.addAll(chatRoomRepository.findAllByRecipientIdAndActiveTrue(userId));
//
//        for (ChatRoom room : allActiveRooms) {
//            String otherSide = room.getSenderId().equals(userId)
//                    ? room.getRecipientId()
//                    : room.getSenderId();
//            // Если на другой стороне — инженер, значит user занят:
//            User otherUser = store.get(otherSide).orElse(null);
//            if (otherUser != null && otherUser.getRole() == UserRole.ENGINEER) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    private boolean isUserEngineerAndRegularBusy(String senderId, String recipientId) {
//        User sender = store.get(senderId).orElse(null);
//        User recipient = store.get(recipientId).orElse(null);
//
//        if (sender == null || recipient == null) return false;
//
//        // engineer → regular
//        if (sender.getRole() == UserRole.ENGINEER && recipient.getRole() == UserRole.REGULAR) {
//            return isUserInActiveChatWithEngineer(recipient.getNickName());
//        }
//        // regular → engineer
//        if (recipient.getRole() == UserRole.ENGINEER && sender.getRole() == UserRole.REGULAR) {
//            return isUserInActiveChatWithEngineer(sender.getNickName());
//        }
//        return false;
//    }
//
//    public String activateChat(String engineerId, String userId) {
//
//        String cid  = pairId(engineerId, userId);                       // общий id
//        ChatRoom room = chatRoomRepository.findAllByChatId(cid)         // «любой первый»
//                .stream()
//                .findFirst()
//                .orElse(null);
//
//        boolean stateChanged = false;
//
//        if (room == null) {                                             // комнаты нет
//            createChatId(engineerId, userId);
//            room = chatRoomRepository.findAllByChatId(cid).stream()
//                    .findFirst()
//                    .orElseThrow();
//            stateChanged = true;
//        } else if (!room.isActive()) {                                  // была, но пассивна
//            room.setActive(true);
//            chatRoomRepository.save(room);
//            stateChanged = true;
//        }
//
//        /* активируем зеркальную запись, если вдруг пассивна */
//        chatRoomRepository.findAllByChatId(cid).forEach(mirror -> {
//            if (!mirror.isActive()) {
//                mirror.setActive(true);
//                chatRoomRepository.save(mirror);
//            }
//        });
//
//        if (stateChanged) {
//            log.info("Пользователь {} ЗАНЯТ инженером {}", userId, engineerId);
//            messagingTemplate.convertAndSend("/topic/user-status",
//                    new UserBusyStatus(userId, true));
//        }
//
//        inactivity.touch(engineerId, userId);
//        inactivity.cancelEngineer(engineerId);
//
//        return room.getChatId();      // == cid
//    }
//    /** Одинаковый chatId для одной и той же пары, не важно кто пишет первым */
//    private static String pairId(String a, String b) {
//        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
//    }
//    /**
//     * Деактивируем пару engineer ↔ user, ставим их «свободными».
//     */
//    public void deactivatePair(String engineerId, String userId) {
//        boolean stateChanged = false;
//
//        List<ChatRoom> directRooms = chatRoomRepository.findAllBySenderIdAndRecipientId(engineerId, userId);
//        List<ChatRoom> mirrorRooms = chatRoomRepository.findAllBySenderIdAndRecipientId(userId, engineerId);
//
//        for (ChatRoom room : directRooms) {
//            if (room.isActive()) {
//                room.setActive(false);
//                chatRoomRepository.save(room);
//                stateChanged = true;
//            }
//        }
//
//        for (ChatRoom room : mirrorRooms) {
//            if (room.isActive()) {
//                room.setActive(false);
//                chatRoomRepository.save(room);
//                stateChanged = true;
//            }
//        }
//
//        if (stateChanged) {
//            log.info("Пользователь {} СВОБОДЕН (инженер {})", userId, engineerId);
//            messagingTemplate.convertAndSend(
//                    "/topic/user-status",
//                    new UserBusyStatus(userId, false));
//        }
//
//        inactivity.cancel(engineerId, userId);
//        messageService.clearHistory(engineerId, userId);
//
//        // Удаляем все связанные комнаты
//        chatRoomRepository.deleteAll(directRooms);
//        chatRoomRepository.deleteAll(mirrorRooms);
//
//        if (!userId.equals(engineerId)) {
//            createChatId(userId, userId);
//            log.info("Создан self-chat для пользователя {}", userId);
//        }
//    }
//
//
//    /**
//     * При отключении пользователя (или инженера) — переводим все связанные комнаты в неактивные.
//     */
//    public void deactivateChatsForUser(String userId) {
//        List<ChatRoom> senderRooms = chatRoomRepository.findAllBySenderId(userId);
//        List<ChatRoom> recipientRooms = chatRoomRepository.findAllByRecipientId(userId);
//
//        List<ChatRoom> allRooms = new ArrayList<>();
//        allRooms.addAll(senderRooms);
//        allRooms.addAll(recipientRooms);
//
//        for (ChatRoom room : allRooms) {
//            room.setActive(false);
//        }
//        chatRoomRepository.saveAll(allRooms);
//        log.info("Все комнаты пользователя {} переведены в неактивные", userId);
//    }
//}
package com.alibou.websocket.chatroom;

import com.alibou.websocket.chat.ChatMessageService;
import com.alibou.websocket.user.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полностью in-memory-реализация работы с чат-комнатами.
 * <p>
 * Весь JPA-слой (ChatRoomRepository + @Entity ChatRoom) удалён,
 * поэтому все данные хранятся в опера­тив­ной памяти сервера и
 * обнуляются при рестарте приложения.
 */
@Service
@Slf4j
public class ChatRoomService {

    /** thread-safe карта «pairId  →  ChatRoom» */
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();

    /* ==== сторонние сервисы ==== */
    private final OnlineUserStore        store;
    private final SimpMessagingTemplate  messaging;
    private final ChatInactivityService  inactivity;
    private final ChatMessageService     messageService;

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
                                   УТИЛИТЫ
       ======================================================================= */

    /** одинаковый id для пары, порядок сторон не важен */
    public static String pairId(String a, String b) {
        return a.compareTo(b) < 0 ? a + '_' + b : b + '_' + a;
    }

    /* =======================================================================
                           PUBLIC API (используется снаружи)
       ======================================================================= */

    /**
     * Вернуть chatId пары, опционально создав его.
     * Для self-чата (A → A) chatId = "A_A".
     */
    public Optional<String> getChatRoomId(String senderId,
                                          String recipientId,
                                          boolean createIfMissing) {

        /* self-chat ---------------------------------------- */
        if (senderId.equals(recipientId)) {
            return Optional.of(senderId + '_' + recipientId);
        }

        /* engineer ↔ regular ------------------------------- */
        String cid = pairId(senderId, recipientId);

        if (rooms.containsKey(cid)) return Optional.of(cid);
        if (!createIfMissing)       return Optional.empty();

        /* создаём комнату, если ни одна нить ещё не успела */
        rooms.computeIfAbsent(cid, k -> new ChatRoom(cid, senderId, recipientId, true));
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
                .map(r -> r.getSenderId().equals(nick) ? r.getRecipientId() : r.getSenderId());
    }

    /** Инженер «берёт» пользователя в работу */
    public String activateChat(String engineerId, String userId) {
        String cid  = pairId(engineerId, userId);
        ChatRoom r  = rooms.computeIfAbsent(cid,
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
        rooms.remove(cid);                       // полностью убираем пару

        /* автоматически создаём self-chat для REGULAR-а */
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
                        СЛУЖЕБНЫЕ МЕТОДЫ, которые нужны другим слоям
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
     * «касание» от инженера в течение 15 секунд.
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
