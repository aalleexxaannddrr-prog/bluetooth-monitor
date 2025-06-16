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

/**
 * In-memory сервис сообщений.
 *
 *  • self-chat (A → A) сбрасывает «личный» таймер автора
 *  • REGULAR → ENGINEER и ENGINEER → REGULAR симметрично перезаряжают таймер пары
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatRoomService        chatRoomService;
    private final ChatInactivityService  inactivity;
    private final OnlineUserStore        store;

    /** chatId → список сообщений */
    private final Map<String, List<ChatMessage>> chats = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    /**
     * Сохраняем новое сообщение (только в памяти) и обновляем таймеры.
     */
    public ChatMessage save(ChatMessage chatMessage) {

        /* -------- валидация -------- */
        String senderId    = chatMessage.getSenderId();
        String recipientId = chatMessage.getRecipientId();
        if (senderId == null || recipientId == null) {
            log.error("❌ ChatMessage содержит null senderId или recipientId: {}", chatMessage);
            throw new IllegalArgumentException("senderId и recipientId не могут быть null");
        }
        log.info("➡️ Пришло сообщение: sender={}, recipient={}, content={}",
                senderId, recipientId, chatMessage.getContent());

        /* -------- «переадресация» REGULAR-а, уже занятого инженером -------- */
        Optional<User> senderOpt = store.get(senderId);
        if (senderOpt.isPresent()
                && senderOpt.get().getRole() == UserRole.REGULAR
                && chatRoomService.isUserInActiveChatWithEngineer(senderId)) {

            recipientId = chatRoomService.findActivePartner(senderId).orElse(recipientId);
            chatMessage.setRecipientId(recipientId);
        }

        /* -------- роли сторон -------- */
        Optional<User> recipientOpt = store.get(recipientId);
        User senderUser    = senderOpt.orElse(null);
        User recipientUser = recipientOpt.orElse(null);
        log.info("🎭 Роли: senderRole={}, recipientRole={}",
                senderUser != null ? senderUser.getRole() : "null",
                recipientUser != null ? recipientUser.getRole() : "null");

        /* ===================================================================
           1. SELF-CHAT  (idA == idB)
           =================================================================== */
        if (senderId.equals(recipientId)) {
            chatMessage.setChatId(senderId + "_" + recipientId);

            if (senderUser != null && senderUser.getRole() == UserRole.REGULAR) {
                inactivity.cancelRegular(senderId);   // был таймер простоя
                inactivity.touchRegular(senderId);    // перезапускаем
            }
            if (senderUser != null && senderUser.getRole() == UserRole.ENGINEER) {
                inactivity.cancelEngineer(senderId);  // инженеру личный таймер не нужен
            }
        }
        /* ===================================================================
           2. REGULAR ↔ ENGINEER
           =================================================================== */
        else {
            String cid = chatRoomService
                    .getChatRoomId(senderId, recipientId, /*createIfMissing*/ true)
                    .orElseThrow(() ->
                            new IllegalStateException("Чат не найден и не может быть создан"));
            chatMessage.setChatId(cid);

            if (senderUser != null && recipientUser != null) {

                /* REGULAR → ENGINEER  (как было) */
                if (senderUser.getRole() == UserRole.REGULAR &&
                        recipientUser.getRole() == UserRole.ENGINEER) {

                    inactivity.touch(recipientId, senderId);   // таймер пары
                    inactivity.touchRegular(senderId);         // личный REGULAR
                }

                /* ENGINEER → REGULAR  (патч) */
                if (senderUser.getRole() == UserRole.ENGINEER &&
                        recipientUser.getRole() == UserRole.REGULAR) {

                    inactivity.touch(senderId, recipientId);   // таймер пары
                    inactivity.cancelRegular(recipientId);     // «личный» таймер REGULARа больше не нужен
                }
            }
        }

        /* -------- сохраняем -------- */
        chatMessage.setId(seq.getAndIncrement());
        chatMessage.setTimestamp(new Date());

        chats.computeIfAbsent(chatMessage.getChatId(),
                k -> new CopyOnWriteArrayList<>()).add(chatMessage);

        log.info("💾 Сообщение {} сохранено ({} → {})",
                chatMessage.getId(), senderId, recipientId);

        return chatMessage;
    }

    /** История переписки (может быть пустой) */
    public List<ChatMessage> findChatMessages(String senderId, String recipientId) {
        return chatRoomService
                .getChatRoomId(senderId, recipientId, false)
                .map(id -> new ArrayList<>(
                        chats.getOrDefault(id, Collections.emptyList())))
                .orElseGet(ArrayList::new);
    }

    /** Полностью стереть историю (engineerId, userId) */
    public void clearHistory(String engineerId, String userId) {
        String chatId = engineerId + "_" + userId;
        chats.remove(chatId);
        log.info("🗑️ История чата {} удалена", chatId);
    }
}
