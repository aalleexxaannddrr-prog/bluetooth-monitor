package com.alibou.websocket.chatroom;

import com.alibou.websocket.chat.ChatMessageService;
import com.alibou.websocket.user.OnlineUserStore;
import com.alibou.websocket.user.Status;
import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final OnlineUserStore store;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatInactivityService inactivity;

    // Ленивое подключение, чтобы не было циклических зависимостей
    private final @Lazy ChatMessageService messageService;

    public ChatRoomService(ChatRoomRepository chatRoomRepository,
                           OnlineUserStore store,
                           SimpMessagingTemplate messagingTemplate,
                           @Lazy ChatInactivityService inactivity,
                           @Lazy ChatMessageService messageService) {
        this.chatRoomRepository = chatRoomRepository;
        this.store = store;
        this.messagingTemplate = messagingTemplate;
        this.inactivity = inactivity;
        this.messageService = messageService;
    }

    /**
     * Обрабатывает тайм-аут: если REGULAR не писал инженер 15 секунд,
     * выкидывает этого REGULAR-а из онлайна и деактивирует пару.
     */
    public void handleInactivity(String engineerId, String userId) {
        // 1) Снимаем REGULAR-а из онлайна
        store.forceRemove(userId);

        // 2) Оповещаем всех, что REGULAR стал OFFLINE
        messagingTemplate.convertAndSend(
                "/topic/public",
                new User(userId, Status.OFFLINE, UserRole.REGULAR)
        );

        // 3) Гасим чат engineer ↔ user
        deactivatePair(engineerId, userId);

        log.info("Пользователь {} вышел по 15-секундному тайм-ауту", userId);
    }

    public boolean isUserInActiveChat(String nick) {
        return chatRoomRepository
                .findAllBySenderIdAndActiveTrue(nick).stream()
                .anyMatch(ChatRoom::isActive)
                || chatRoomRepository
                .findAllByRecipientIdAndActiveTrue(nick).stream()
                .anyMatch(ChatRoom::isActive);
    }

    /** Найти собеседника в активном чате (если он ровно один) */
    public Optional<String> findActivePartner(String nick) {
        return chatRoomRepository
                .findAllBySenderIdAndActiveTrue(nick).stream()
                .findFirst()
                .map(ChatRoom::getRecipientId);
    }

    /**
     * Если createNewRoomIfNotExists = true и нет комнаты,
     * создаёт её (если нужно), предварительно проверяя «занятость».
     */
    public Optional<String> getChatRoomId(String senderId,
                                          String recipientId,
                                          boolean createIfMissing) {

        /* ---------- self-chat ---------- */
        if (senderId.equals(recipientId)) {
            return Optional.of(senderId + "_" + recipientId);
        }

        String cid = pairId(senderId, recipientId);   //  A_B  либо  B_A

        /* 1) уже есть запись? */
        Optional<ChatRoom> existing = chatRoomRepository.findByChatId(cid);
        if (existing.isPresent()) return Optional.of(cid);

        /* 2) нет и не надо создавать? */
        if (!createIfMissing) return Optional.empty();

        /* 3) создаём две зеркальные записи */
        ChatRoom r1 = ChatRoom.builder()
                .chatId(cid).senderId(senderId)
                .recipientId(recipientId).active(true).build();
        ChatRoom r2 = ChatRoom.builder()
                .chatId(cid).senderId(recipientId)
                .recipientId(senderId).active(true).build();

        chatRoomRepository.save(r1);
        chatRoomRepository.save(r2);
        return Optional.of(cid);
    }

    /**
     * Создание chatId и сохранение записей в БД.
     */
    // Вставка изменений только в нужные места

// 1. 🔁 В createChatId:
    private String createChatId(String a, String b) {

        String chatId = pairId(a, b);          // 👈 упорядоченный id

        // если запись уже есть – просто вернуть
        if (chatRoomRepository.findByChatId(chatId).isPresent()) {
            return chatId;
        }

        ChatRoom r1 = ChatRoom.builder()
                .chatId(chatId).senderId(a)
                .recipientId(b).active(true).build();
        ChatRoom r2 = ChatRoom.builder()
                .chatId(chatId).senderId(b)
                .recipientId(a).active(true).build();

        chatRoomRepository.save(r1);
        chatRoomRepository.save(r2);

        log.info("Создана новая комната {} ({} ↔ {})", chatId, a, b);
        return chatId;
    }


    public boolean isUserInActiveChatWithEngineer(String userId) {
        List<ChatRoom> allActiveRooms = new ArrayList<>();
        allActiveRooms.addAll(chatRoomRepository.findAllBySenderIdAndActiveTrue(userId));
        allActiveRooms.addAll(chatRoomRepository.findAllByRecipientIdAndActiveTrue(userId));

        for (ChatRoom room : allActiveRooms) {
            String otherSide = room.getSenderId().equals(userId)
                    ? room.getRecipientId()
                    : room.getSenderId();
            // Если на другой стороне — инженер, значит user занят:
            User otherUser = store.get(otherSide).orElse(null);
            if (otherUser != null && otherUser.getRole() == UserRole.ENGINEER) {
                return true;
            }
        }
        return false;
    }

    private boolean isUserEngineerAndRegularBusy(String senderId, String recipientId) {
        User sender = store.get(senderId).orElse(null);
        User recipient = store.get(recipientId).orElse(null);

        if (sender == null || recipient == null) return false;

        // engineer → regular
        if (sender.getRole() == UserRole.ENGINEER && recipient.getRole() == UserRole.REGULAR) {
            return isUserInActiveChatWithEngineer(recipient.getNickName());
        }
        // regular → engineer
        if (recipient.getRole() == UserRole.ENGINEER && sender.getRole() == UserRole.REGULAR) {
            return isUserInActiveChatWithEngineer(sender.getNickName());
        }
        return false;
    }

    public String activateChat(String engineerId, String userId) {
        ChatRoom room = chatRoomRepository.findByChatId(pairId(engineerId, userId))
                .orElse(null);

        boolean stateChanged = false;

        if (room == null) {
            createChatId(engineerId, userId); // active=true для новой пары
            room = chatRoomRepository
                    .findAllBySenderIdAndRecipientId(engineerId, userId)
                    .stream()
                    .findFirst()
                    .orElseThrow();
            stateChanged = true;
        } else if (!room.isActive()) {
            room.setActive(true);
            chatRoomRepository.save(room);
            stateChanged = true;
        }

        chatRoomRepository
                .findAllBySenderIdAndRecipientId(userId, engineerId)
                .stream()
                .findFirst()
                .ifPresent(mirror -> {
                    if (!mirror.isActive()) {
                        mirror.setActive(true);
                        chatRoomRepository.save(mirror);
                    }
                });

        if (stateChanged) {
            log.info("Пользователь {} ЗАНЯТ инженером {}", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, true));
        }

        inactivity.touch(engineerId, userId);
        inactivity.cancelEngineer(engineerId);

        return room.getChatId();
    }
    /** Одинаковый chatId для одной и той же пары, не важно кто пишет первым */
    private static String pairId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }
    /**
     * Деактивируем пару engineer ↔ user, ставим их «свободными».
     */
    public void deactivatePair(String engineerId, String userId) {
        boolean stateChanged = false;

        List<ChatRoom> directRooms = chatRoomRepository.findAllBySenderIdAndRecipientId(engineerId, userId);
        List<ChatRoom> mirrorRooms = chatRoomRepository.findAllBySenderIdAndRecipientId(userId, engineerId);

        for (ChatRoom room : directRooms) {
            if (room.isActive()) {
                room.setActive(false);
                chatRoomRepository.save(room);
                stateChanged = true;
            }
        }

        for (ChatRoom room : mirrorRooms) {
            if (room.isActive()) {
                room.setActive(false);
                chatRoomRepository.save(room);
                stateChanged = true;
            }
        }

        if (stateChanged) {
            log.info("Пользователь {} СВОБОДЕН (инженер {})", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, false));
        }

        inactivity.cancel(engineerId, userId);
        messageService.clearHistory(engineerId, userId);

        // Удаляем все связанные комнаты
        chatRoomRepository.deleteAll(directRooms);
        chatRoomRepository.deleteAll(mirrorRooms);

        if (!userId.equals(engineerId)) {
            createChatId(userId, userId);
            log.info("Создан self-chat для пользователя {}", userId);
        }
    }


    /**
     * При отключении пользователя (или инженера) — переводим все связанные комнаты в неактивные.
     */
    public void deactivateChatsForUser(String userId) {
        List<ChatRoom> senderRooms = chatRoomRepository.findAllBySenderId(userId);
        List<ChatRoom> recipientRooms = chatRoomRepository.findAllByRecipientId(userId);

        List<ChatRoom> allRooms = new ArrayList<>();
        allRooms.addAll(senderRooms);
        allRooms.addAll(recipientRooms);

        for (ChatRoom room : allRooms) {
            room.setActive(false);
        }
        chatRoomRepository.saveAll(allRooms);
        log.info("Все комнаты пользователя {} переведены в неактивные", userId);
    }
}
