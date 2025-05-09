package com.alibou.websocket.chatroom;

import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserRepository;
import com.alibou.websocket.user.UserRole;
import com.alibou.websocket.user.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

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
//                .active(true)   // устанавливаем активный чат
                .active(activePair)
                .build();

        ChatRoom recipientSender = ChatRoom
                .builder()
                .chatId(chatId)
                .senderId(recipientId)
                .recipientId(senderId)
//                .active(true)   // зеркальная запись - тоже активна
                .active(activePair)
                .build();

        chatRoomRepository.save(senderRecipient);
        chatRoomRepository.save(recipientSender);

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
        // Загружаем пользователей (может понадобиться, чтобы получить их роли)
        User sender = userRepository.findById(senderId).orElse(null);
        User recipient = userRepository.findById(recipientId).orElse(null);

        if (sender == null || recipient == null) {
            // Если у нас нет инфы о пользователях — либо выбрасываем исключение, либо пропускаем
            return false;
        }

        // Проверяем комбинации:
        if (sender.getRole() == UserRole.ENGINEER && recipient.getRole() == UserRole.REGULAR) {
            // Проверим, есть ли у recipient уже активный чат с каким-то инженером
//            return hasActiveChatWithAnyEngineer(recipient.getNickName());
            return isUserInActiveChatWithEngineer(sender.getNickName());
        }

        if (recipient.getRole() == UserRole.ENGINEER && sender.getRole() == UserRole.REGULAR) {
            // Аналогично, есть ли у sender уже активный чат?
//            return hasActiveChatWithAnyEngineer(sender.getNickName());
            return isUserInActiveChatWithEngineer(sender.getNickName());
        }

        // Если роли другие (ENGINEER-ENGINEER или REGULAR-REGULAR) — не блокируем
        return false;
    }

    /**
     * Проверяем, есть ли у пользователя (regularUserId) активная комната с любым инженером.
     */
    private boolean hasActiveChatWithAnyEngineer(String regularUserId) {
        return chatRoomRepository.existsByActiveTrueAndSenderId(regularUserId)
                || chatRoomRepository.existsByActiveTrueAndRecipientId(regularUserId);
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
    }
}
