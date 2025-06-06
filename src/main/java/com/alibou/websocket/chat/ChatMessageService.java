package com.alibou.websocket.chat;

import com.alibou.websocket.chatroom.ChatInactivityService;
import com.alibou.websocket.chatroom.ChatRoomService;
import com.alibou.websocket.user.OnlineUserStore;
import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatRoomService chatRoomService;
    private final ChatInactivityService inactivity;
    private final OnlineUserStore store;

    /* in-memory хранилище чатов */
    private final Map<String, List<ChatMessage>> chats = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    /**
     * Сохраняем новое сообщение (только в памяти), обновляем соответствующие таймеры:
     *  - ENGINEER → ENGINEER (self-chat) — сбрасываем таймер инженера.
     *  - REGULAR → REGULAR (self-chat) — сбрасываем таймер REGULAR.
     *  - REGULAR → ENGINEER — сбрасываем таймер пары (engineer, regular).
     */
    public ChatMessage save(ChatMessage chatMessage) {
        String senderId = chatMessage.getSenderId();
        String recipientId = chatMessage.getRecipientId();

        /* ===== ОБРАБОТКА SELF-CHAT: sender == recipient ===== */
        if (senderId.equals(recipientId)) {
            // виртуальный chatId: "<nick>_<nick>"
            chatMessage.setChatId(senderId + "_" + recipientId);

            // если ENGINEER пишет себе → сбрасываем таймер инженера:
            User senderUser = store.get(senderId).orElse(null);
            if (senderUser != null && senderUser.getRole() == UserRole.ENGINEER) {
                inactivity.cancelEngineer(senderId);
            }

            // если REGULAR пишет себе → сбрасываем «личный» таймер REGULAR:
            if (senderUser != null && senderUser.getRole() == UserRole.REGULAR) {
                inactivity.cancelRegular(senderId);
            }
        }
        else {
            // В обычном случае (двое разных) получаем или создаём комнату
            String cid = chatRoomService
                    .getChatRoomId(senderId, recipientId, true)
                    .orElseThrow();
            chatMessage.setChatId(cid);

            // Если REGULAR → ENGINEER, то обновляем таймер пары:
            User senderUser = store.get(senderId).orElse(null);
            User recipientUser = store.get(recipientId).orElse(null);
            if (senderUser != null && recipientUser != null
                    && senderUser.getRole() == UserRole.REGULAR
                    && recipientUser.getRole() == UserRole.ENGINEER) {
                inactivity.touch(recipientId, senderId); // touch(engineer, regular)
            }
        }

        // Присваиваем ID и время
        chatMessage.setId(seq.getAndIncrement());
        chatMessage.setTimestamp(new Date());

        // Добавляем в in-memory список
        chats.computeIfAbsent(chatMessage.getChatId(),
                        k -> new CopyOnWriteArrayList<>())
                .add(chatMessage);

        log.info("Сообщение {} сохранено в памяти ({} → {})",
                chatMessage.getId(), senderId, recipientId);

        return chatMessage;
    }

    /** Вернуть историю (может быть пустая) */
    public List<ChatMessage> findChatMessages(String senderId, String recipientId) {
        return chatRoomService
                .getChatRoomId(senderId, recipientId, false)
                .map(id -> new ArrayList<>(
                        chats.getOrDefault(id, Collections.<ChatMessage>emptyList())
                ))
                .orElseGet(ArrayList::new);
    }

    /** Полностью стереть историю (engineerId, userId) */
    public void clearHistory(String engineerId, String userId) {
        String chatId = String.format("%s_%s", engineerId, userId);
        chats.remove(chatId);
        log.info("История чата {} удалена", chatId);
    }
}
