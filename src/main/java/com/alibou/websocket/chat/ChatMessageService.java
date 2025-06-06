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

        User senderUser = store.get(senderId).orElse(null);
        User recipientUser = store.get(recipientId).orElse(null);

        // SELF-CHAT
        if (senderId.equals(recipientId)) {
            chatMessage.setChatId(senderId + "_" + recipientId);

            if (senderUser != null && senderUser.getRole() == UserRole.REGULAR) {
                inactivity.cancelRegular(senderId);
                inactivity.touchRegular(senderId);  // ← запустить новый таймер
            }

            if (senderUser != null && senderUser.getRole() == UserRole.ENGINEER) {
                inactivity.cancelEngineer(senderId); // ← только отменить, запуск не нужен
            }
        } else {
            // Создание или получение чата
            String cid = chatRoomService
                    .getChatRoomId(senderId, recipientId, true)
                    .orElseThrow();
            chatMessage.setChatId(cid);

            // REGULAR → ENGINEER
            if (senderUser != null && recipientUser != null
                    && senderUser.getRole() == UserRole.REGULAR
                    && recipientUser.getRole() == UserRole.ENGINEER) {
                inactivity.touch(recipientId, senderId); // сбрасываем таймер engineer-user
                inactivity.touchRegular(senderId);       // ← добавляем: ставим личный таймер REGULAR-а
            }

            // ENGINEER → REGULAR — ничего не делаем
        }

        // ID + timestamp
        chatMessage.setId(seq.getAndIncrement());
        chatMessage.setTimestamp(new Date());

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
