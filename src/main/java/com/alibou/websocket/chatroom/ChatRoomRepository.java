package com.alibou.websocket.chatroom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    Optional<ChatRoom> findBySenderIdAndRecipientId(String senderId, String recipientId);
    boolean existsByActiveTrueAndSenderId(String senderId);
    boolean existsByActiveTrueAndRecipientId(String recipientId);

    // Вместо OR в одном методе — делаем два метода:
    List<ChatRoom> findAllBySenderId(String senderId);
    List<ChatRoom> findAllByRecipientId(String recipientId);

    List<ChatRoom> findAllBySenderIdAndActiveTrue(String senderId);

    // Находим все комнаты, где recipientId = ? и active = true
    List<ChatRoom> findAllByRecipientIdAndActiveTrue(String recipientId);
}
