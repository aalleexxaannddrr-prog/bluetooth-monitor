package com.alibou.websocket.chat;

import com.alibou.websocket.chatroom.ChatInactivityService;
import com.alibou.websocket.chatroom.ChatRoomService;
import com.alibou.websocket.user.*;
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

    private final ChatRoomService       chatRoomService;
    private final ChatInactivityService inactivity;
    private final OnlineUserStore       store;

    /* ------------ in-memory хранилище --------------- */
    private final Map<String, List<ChatMessage>> chats = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    /* сохранить сообщение (ТОЛЬКО в памяти) */
    public ChatMessage save(ChatMessage chatMessage) {

        String chatId = chatRoomService
                .getChatRoomId(chatMessage.getSenderId(),
                        chatMessage.getRecipientId(),
                        true)
                .orElseThrow();

        chatMessage.setChatId(chatId);
        chatMessage.setId(seq.getAndIncrement());
        chatMessage.setTimestamp(new Date());

        chats.computeIfAbsent(chatId, k -> new CopyOnWriteArrayList<>())
                .add(chatMessage);

        log.info("Сообщение {} сохранено в памяти ({} → {})",
                chatMessage.getId(),
                chatMessage.getSenderId(),
                chatMessage.getRecipientId());

        /* сброс тайм-аута — как было */
        User sender    = store.get(chatMessage.getSenderId()).orElse(null);
        User recipient = store.get(chatMessage.getRecipientId()).orElse(null);

        if (sender != null && recipient != null && sender.getRole() == UserRole.REGULAR) {
            // sender ─- REGULAR, recipient ─- ENGINEER
            inactivity.touch(recipient.getNickName(), sender.getNickName());
        }

        return chatMessage;
    }

    /* вернуть историю (может быть пустой) */
    public List<ChatMessage> findChatMessages(String senderId, String recipientId) {
        return chatRoomService
                .getChatRoomId(senderId, recipientId, false)
                .map(id -> new ArrayList<>(
                        chats.getOrDefault(id,
                                Collections.<ChatMessage>emptyList())   // тип задан
                ))
                .orElseGet(ArrayList::new);   // ← пустой ArrayList<ChatMessage>
    }

    /* стереть историю окончательно */
    public void clearHistory(String engineerId, String userId) {
        String chatId = String.format("%s_%s", engineerId, userId);
        chats.remove(chatId);
        log.info("История чата {} удалена", chatId);
    }
}
