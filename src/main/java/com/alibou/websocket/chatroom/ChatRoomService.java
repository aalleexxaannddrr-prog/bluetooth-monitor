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
    public Optional<String> getChatRoomId(
            String senderId,
            String recipientId,
            boolean createNewRoomIfNotExists
    ) {
        /* ====== СПЕЦИАЛЬНАЯ ОБРАБОТКА SELF-CHAT (sender == recipient) ===== */
        if (senderId.equals(recipientId)) {
            // В self-chat не создаём две записи, просто возвращаем виртуальный chatId
            String virtualChatId = senderId + "_" + recipientId;
            return Optional.of(virtualChatId);
        }

        // 1. Ищем уже существующие комнаты (может оказаться более одной, если дубли не убрали):
        List<ChatRoom> existing = chatRoomRepository
                .findAllBySenderIdAndRecipientId(senderId, recipientId);

        if (!existing.isEmpty()) {
            // Если хотя бы одна запись есть — возвращаем chatId первой
            return Optional.of(existing.get(0).getChatId());
        }

        // Если нет комнаты и не нужно её создавать — возвращаем empty
        if (!createNewRoomIfNotExists) {
            return Optional.empty();
        }

        // 2. Проверяем, не занят ли user другим инженером:
        if (isUserEngineerAndRegularBusy(senderId, recipientId)) {
            log.warn("Не удалось создать чат: пользователь {} уже занят", recipientId);
            throw new RuntimeException("Пользователь уже занят другим инженером!");
        }

        // 3. Всё нормально — создаём новую комнату
        String newChatId = createChatId(senderId, recipientId);
        return Optional.of(newChatId);
    }

    /**
     * Создание chatId и сохранение записей в БД.
     */
    private String createChatId(String senderId, String recipientId) {
        String chatId = String.format("%s_%s", senderId, recipientId);

        // ===== SELF-CHAT =====
        if (senderId.equals(recipientId)) {
            deleteSelfChatRoom(senderId); // удаляем старый self-chat, если был
            ChatRoom room = ChatRoom.builder()
                    .chatId(chatId)
                    .senderId(senderId)
                    .recipientId(recipientId)
                    .active(false)
                    .build();
            chatRoomRepository.save(room);
            return chatId;
        }

        // ===== ПАРНЫЙ ЧАТ engineer ↔ regular =====
        boolean activePair = true;

        ChatRoom senderRecipient = ChatRoom.builder()
                .chatId(chatId)
                .senderId(senderId)
                .recipientId(recipientId)
                .active(activePair)
                .build();

        ChatRoom recipientSender = ChatRoom.builder()
                .chatId(chatId)
                .senderId(recipientId)
                .recipientId(senderId)
                .active(activePair)
                .build();

        chatRoomRepository.save(senderRecipient);
        chatRoomRepository.save(recipientSender);

        log.info("Создана новая комната {} ({} ↔ {})", chatId, senderId, recipientId);
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
        ChatRoom room = chatRoomRepository
                .findBySenderIdAndRecipientId(engineerId, userId)
                .orElse(null);

        boolean stateChanged = false;

        if (room == null) {
            createChatId(engineerId, userId); // active=true для новой пары
            room = chatRoomRepository
                    .findBySenderIdAndRecipientId(engineerId, userId)
                    .orElseThrow();
            stateChanged = true;
        } else if (!room.isActive()) {
            room.setActive(true);
            chatRoomRepository.save(room);
            stateChanged = true;
        }

        // Зеркальная запись (user → engineer)
        chatRoomRepository.findBySenderIdAndRecipientId(userId, engineerId)
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

        // Сброс тайм-аута пары + сброс «личного» тайм-аута инженера
        inactivity.touch(engineerId, userId);
        inactivity.cancelEngineer(engineerId);

        return room.getChatId();
    }
    public void deleteSelfChatRoom(String userId) {
        chatRoomRepository.findBySenderIdAndRecipientId(userId, userId)
                .ifPresent(room -> {
                    chatRoomRepository.delete(room);
                    log.info("Удалён self-chat для {}", userId);
                });
    }
    /**
     * Деактивируем пару engineer ↔ user, ставим их «свободными».
     */
    public void deactivatePair(String engineerId, String userId) {
        boolean stateChanged = false;

        // engineer → user
        Optional<ChatRoom> direct = chatRoomRepository
                .findBySenderIdAndRecipientId(engineerId, userId);
        if (direct.isPresent() && direct.get().isActive()) {
            direct.get().setActive(false);
            chatRoomRepository.save(direct.get());
            stateChanged = true;
        }

        // user → engineer
        Optional<ChatRoom> mirror = chatRoomRepository
                .findBySenderIdAndRecipientId(userId, engineerId);
        if (mirror.isPresent() && mirror.get().isActive()) {
            mirror.get().setActive(false);
            chatRoomRepository.save(mirror.get());
            stateChanged = true;
        }

        if (stateChanged) {
            log.info("Пользователь {} СВОБОДЕН (инженер {})", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, false));
        }

        // Отмена таймеров
        inactivity.cancel(engineerId, userId);

        // Очистка истории сообщений
        messageService.clearHistory(engineerId, userId);

        // Удаляем обе записи (engineer → user и user → engineer)
        direct.ifPresent(chatRoomRepository::delete);
        mirror.ifPresent(chatRoomRepository::delete);

        // Создаём self-chat для REGULAR
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
