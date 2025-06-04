package com.alibou.websocket.chatroom;

import com.alibou.websocket.chat.ChatMessageService;
import com.alibou.websocket.user.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
//@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    //    @Lazy
    private final ChatRoomRepository chatRoomRepository;
    private final OnlineUserStore store;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatInactivityService inactivity;

    /* добавляем ленивую ссылку, чтобы не создать новый цикл */
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
     * Если createNewRoomIfNotExists = true и нет комнаты,
     * создаём новую, предварительно проверяя «занятость».
     */
    public Optional<String> getChatRoomId(
            String senderId,
            String recipientId,
            boolean createNewRoomIfNotExists
    ) {
        // 1. Смотрим, есть ли уже чат между этими двумя
        Optional<ChatRoom> existing = chatRoomRepository.findBySenderIdAndRecipientId(senderId, recipientId);

        // Если он есть — возвращаем его chatId:
        if (existing.isPresent()) {
            return Optional.of(existing.get().getChatId());
        }

        // Если чата нет и не нужно создавать, то просто вернём empty
        if (!createNewRoomIfNotExists) {
            return Optional.empty();
        }

        // 2. Раз чата пока нет, перед созданием проверяем, не занят ли пользователь другим инженером
        if (isUserEngineerAndRegularBusy(senderId, recipientId)) {
            log.warn("Не удалось создать чат: пользователь {} уже занят", recipientId);
            // Можете пробросить своё исключение, например ChatRoomBusyException:
            throw new RuntimeException("Пользователь уже занят другим инженером!");
        }

        // 3. Если всё нормально, генерируем новый chatId
        var chatId = createChatId(senderId, recipientId);
        return Optional.of(chatId);
    }

    /**
     * Собственно создание chatId и сохранение двух записей (sender->recipient и recipient->sender).
     */
    private String createChatId(String senderId, String recipientId) {
        var chatId = String.format("%s_%s", senderId, recipientId);
        boolean activePair = !senderId.equals(recipientId);
        ChatRoom senderRecipient = ChatRoom
                .builder()
                .chatId(chatId)
                .senderId(senderId)
                .recipientId(recipientId)
                .active(activePair)
                .build();

        ChatRoom recipientSender = ChatRoom
                .builder()
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

        // Все активные комнаты, где userId либо sender, либо recipient
        List<ChatRoom> allActiveRooms = new ArrayList<>();
        allActiveRooms.addAll(chatRoomRepository.findAllBySenderIdAndActiveTrue(userId));
        allActiveRooms.addAll(chatRoomRepository.findAllByRecipientIdAndActiveTrue(userId));

        for (ChatRoom room : allActiveRooms) {
            // Кто на другой стороне?
            String otherSide = room.getSenderId().equals(userId)
                    ? room.getRecipientId()
                    : room.getSenderId();

            // Берём данные о собеседнике из оперативного хранилища
            User otherUser = store.get(otherSide).orElse(null);
            if (otherUser != null && otherUser.getRole() == UserRole.ENGINEER) {
                // значит наш пользователь «занят» инженером
                return true;
            }
        }
        return false; // нет активных чатов с инженером
    }

    private boolean isUserEngineerAndRegularBusy(String senderId, String recipientId) {
        User sender = store.get(senderId).orElse(null);
        User recipient = store.get(recipientId).orElse(null);

        if (sender == null || recipient == null) return false;

        // инженер -> пользователь
        if (sender.getRole() == UserRole.ENGINEER && recipient.getRole() == UserRole.REGULAR)
            return isUserInActiveChatWithEngineer(recipient.getNickName());

        // пользователь -> инженер
        if (recipient.getRole() == UserRole.ENGINEER && sender.getRole() == UserRole.REGULAR)
            return isUserInActiveChatWithEngineer(sender.getNickName());

        return false;
    }

    public String activateChat(String engineerId, String userId) {

        // 1. ищем (или создаём) комнату engineer → user
        ChatRoom room = chatRoomRepository
                .findBySenderIdAndRecipientId(engineerId, userId)
                .orElse(null);

        boolean stateChanged = false;

        // А) комнаты не было ─ создаём новую пару
        if (room == null) {
            createChatId(engineerId, userId);           // active=true
            room = chatRoomRepository
                    .findBySenderIdAndRecipientId(engineerId, userId)
                    .orElseThrow();
            stateChanged = true;
        }
        // Б) комната была, но была неактивна
        else if (!room.isActive()) {
            room.setActive(true);
            chatRoomRepository.save(room);
            stateChanged = true;
        }

        // 2. зеркальная запись (user → engineer)
        chatRoomRepository.findBySenderIdAndRecipientId(userId, engineerId)
                .ifPresent(mirror -> {
                    if (!mirror.isActive()) {
                        mirror.setActive(true);
                        chatRoomRepository.save(mirror);
                    }
                });

        // 3. если пользователь действительно «захвачен» – шлём busy
        if (stateChanged) {
            log.info("Пользователь {} ЗАНЯТ инженером {}", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, true));
        }

        /* 4. запускаем (или перезапускаем) таймер неактивности */
        inactivity.touch(engineerId, userId);

        return room.getChatId();
    }


    /**
     * Деактивируем конкретную пару engineer ↔ user
     * и говорим всем инженерам, что user освободился.
     */
    public void deactivatePair(String engineerId, String userId) {

        boolean stateChanged = false;

        /* ---------- 1. гасим обе записи в chat_room ---------------- */

        // engineer → user
        Optional<ChatRoom> direct = chatRoomRepository
                .findBySenderIdAndRecipientId(engineerId, userId);
        if (direct.isPresent() && direct.get().isActive()) {
            direct.get().setActive(false);
            chatRoomRepository.save(direct.get());
            stateChanged = true;
        }

        // user → engineer (зеркальная)
        Optional<ChatRoom> mirror = chatRoomRepository
                .findBySenderIdAndRecipientId(userId, engineerId);
        if (mirror.isPresent() && mirror.get().isActive()) {
            mirror.get().setActive(false);
            chatRoomRepository.save(mirror.get());
            stateChanged = true;
        }

        /* ---------- 2. если статус изменился — шлём free ---------- */
        if (stateChanged) {
            log.info("Пользователь {} СВОБОДЕН (инженер {})", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, false));
        }

        /* ---------- 3. отменяем таймер неактивности --------------- */
        inactivity.cancel(engineerId, userId);

        /* ---------- 4. чистим историю сообщений ------------------- */
        messageService.clearHistory(engineerId, userId);
    }


    /**
     * При отключении пользователя (или инженера) – переводим все связанные комнаты в неактивные.
     * (Либо можно точечно лишь ту одну комнату, в которой они состыкованы — зависит от бизнес-логики.)
     */
    public void deactivateChatsForUser(String userId) {
        // Вместо "OR" используем два отдельных вызова
        List<ChatRoom> senderRooms = chatRoomRepository.findAllBySenderId(userId);
        List<ChatRoom> recipientRooms = chatRoomRepository.findAllByRecipientId(userId);

        // Объединяем результаты
        List<ChatRoom> allRooms = new ArrayList<>();
        allRooms.addAll(senderRooms);
        allRooms.addAll(recipientRooms);

        // Делаем комнаты неактивными
        for (ChatRoom room : allRooms) {
            room.setActive(false);
        }
        chatRoomRepository.saveAll(allRooms);
        log.info("Все комнаты пользователя {} переведены в неактивные", userId);
    }
}
