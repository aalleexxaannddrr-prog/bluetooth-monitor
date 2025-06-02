package com.alibou.websocket.chatroom;

import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserRepository;
import com.alibou.websocket.user.UserRole;
import com.alibou.websocket.user.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
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
        // Ищем все активные комнаты, где userId = senderId
        List<ChatRoom> senderRooms = chatRoomRepository.findAllBySenderIdAndActiveTrue(userId);
        // Ищем все активные комнаты, где userId = recipientId
        List<ChatRoom> recipientRooms = chatRoomRepository.findAllByRecipientIdAndActiveTrue(userId);

        // Объединяем результаты
        List<ChatRoom> allActiveRooms = new ArrayList<>();
        allActiveRooms.addAll(senderRooms);
        allActiveRooms.addAll(recipientRooms);

        // Проходимся по всем комнатам и ищем, нет ли там инженера на другой стороне
        for (ChatRoom room : allActiveRooms) {
            // Определяем собеседника
            String otherSide = room.getSenderId().equals(userId)
                    ? room.getRecipientId()
                    : room.getSenderId();

            // Находим у репозитория информацию о другом пользователе
            User otherUser = userRepository.findById(otherSide).orElse(null);
            if (otherUser != null && otherUser.getRole() == UserRole.ENGINEER) {
                // Если другая сторона — инженер, значит пользователь "занят" чатом
                return true;
            }
        }

        // Если в ни одной из комнат нет инженера, пользователь "свободен"
        return false;
    }
    /**
     * Проверяем ситуацию, когда один собеседник — инженер, а другой — пользователь,
     * и при этом у пользователя уже есть активный чат с каким-то другим инженером.
     */
    private boolean isUserEngineerAndRegularBusy(String senderId, String recipientId) {
        User sender    = userRepository.findById(senderId).orElse(null);
        User recipient = userRepository.findById(recipientId).orElse(null);

        if (sender == null || recipient == null) return false;

        // инженер -> пользователь
        if (sender.getRole() == UserRole.ENGINEER && recipient.getRole() == UserRole.REGULAR) {
            // ДОЛЖНЫ проверять именно пользователя-regular!
            return isUserInActiveChatWithEngineer(recipient.getNickName());
        }

        // пользователь -> инженер
        if (recipient.getRole() == UserRole.ENGINEER && sender.getRole() == UserRole.REGULAR) {
            return isUserInActiveChatWithEngineer(sender.getNickName());
        }
        return false;
    }

    public String activateChat(String engineerId, String userId) {

        // 1. ищем (или создаём) комнату engineer → user
        ChatRoom room = chatRoomRepository
                .findBySenderIdAndRecipientId(engineerId, userId)
                .orElse(null);

        boolean stateChanged = false;

        // ♦  А) комнаты нет ─ создаём пару и сразу считаем, что состояние изменилось
        if (room == null) {
            createChatId(engineerId, userId);               // active=true
            room = chatRoomRepository.findBySenderIdAndRecipientId(
                    engineerId, userId).orElseThrow();
            stateChanged = true;                            // ← ОБЯЗАТЕЛЬНО!
        }
        // ♦  Б) комната была, но была неактивна
        else if (!room.isActive()) {
            room.setActive(true);
            chatRoomRepository.save(room);
            stateChanged = true;
        }

        // 2. зеркальная запись (user → engineer) — как было
        chatRoomRepository.findBySenderIdAndRecipientId(userId, engineerId)
                .ifPresent(mirror -> {
                    if (!mirror.isActive()) {
                        mirror.setActive(true);
                        chatRoomRepository.save(mirror);
                    }
                });

        // 3. если пользователь действительно «захвачен» – шлём событие busy
        if (stateChanged) {
            log.info("Пользователь {} ЗАНЯТ инженером {}", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, true));
        }
        return room.getChatId();
    }

    /**
     * Деактивируем конкретную пару engineer ↔ user
     * и говорим всем инженерам, что user освободился.
     */
    public void deactivatePair(String engineerId, String userId) {

        boolean stateChanged = false;

        // 1. engineer → user
        Optional<ChatRoom> direct = chatRoomRepository
                .findBySenderIdAndRecipientId(engineerId, userId);
        if (direct.isPresent() && direct.get().isActive()) {
            direct.get().setActive(false);
            chatRoomRepository.save(direct.get());
            stateChanged = true;
        }

        // 2. user → engineer
        Optional<ChatRoom> mirror = chatRoomRepository
                .findBySenderIdAndRecipientId(userId, engineerId);
        if (mirror.isPresent() && mirror.get().isActive()) {
            mirror.get().setActive(false);
            chatRoomRepository.save(mirror.get());
            stateChanged = true;
        }

        // 3. если хотя бы одна запись изменилась – рассылаем «free»
        if (stateChanged) {
            log.info("Пользователь {} СВОБОДЕН (инженер {})", userId, engineerId);
            messagingTemplate.convertAndSend(
                    "/topic/user-status",
                    new UserBusyStatus(userId, false));  // busy = false

        }
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
