package com.alibou.websocket.chatroom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    // Старый метод оставляем:
    Optional<ChatRoom> findBySenderIdAndRecipientId(String senderId, String recipientId);

    // Новое: возвращает список, если вдруг два или больше записей
    List<ChatRoom> findAllBySenderIdAndRecipientId(String senderId, String recipientId);

    List<ChatRoom> findAllBySenderId(String senderId);
    List<ChatRoom> findAllByRecipientId(String recipientId);

    List<ChatRoom> findAllBySenderIdAndActiveTrue(String senderId);
    List<ChatRoom> findAllByRecipientIdAndActiveTrue(String recipientId);
}
