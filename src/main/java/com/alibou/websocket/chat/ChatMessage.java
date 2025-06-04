package com.alibou.websocket.chat;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Date;

import lombok.*;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatMessage {

    private Long   id;          // будет генерироваться в памяти
    private String chatId;
    private String senderId;
    private String recipientId;
    private String content;
    private Date   timestamp;
}