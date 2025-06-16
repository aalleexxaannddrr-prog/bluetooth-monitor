package com.alibou.websocket.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatMessageService chatMessageService;

    @MessageMapping("/chat")
    public void processMessage(@Payload ChatMessage chatMessage) {
        if (chatMessage == null ||
                chatMessage.getSenderId() == null ||
                chatMessage.getRecipientId() == null ||
                chatMessage.getContent() == null) {
            log.error("❌ Получено некорректное сообщение: {}", chatMessage);
            return;
        }

        ChatMessage savedMsg = chatMessageService.save(chatMessage);

        messagingTemplate.convertAndSend(
                "/queue/" + chatMessage.getRecipientId(),
                new ChatNotification(
                        savedMsg.getId().toString(),
                        savedMsg.getSenderId(),
                        savedMsg.getRecipientId(),
                        savedMsg.getContent()
                )
        );
        messagingTemplate.convertAndSend("/topic/admin-feed", savedMsg);

//        log.info("Сообщение {} отправлено по /queue/{} ({} → {})",
//                savedMsg.getId(),
//                savedMsg.getRecipientId(),
//                savedMsg.getSenderId(),
//                savedMsg.getRecipientId());
    }


    @GetMapping("/messages/{senderId}/{recipientId}")
    public ResponseEntity<List<ChatMessage>> findChatMessages(@PathVariable String senderId,
                                                              @PathVariable String recipientId) {
        return ResponseEntity.ok(chatMessageService.findChatMessages(senderId, recipientId));
    }
}
