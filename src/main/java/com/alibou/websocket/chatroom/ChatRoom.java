//package com.alibou.websocket.chatroom;
//
//
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//
//@Data
//@AllArgsConstructor
//@NoArgsConstructor
//@Builder
//@Entity
//@Table(name = "chat_room")
//public class ChatRoom {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Long id;
//
//    private String chatId;
//    private String senderId;
//    private String recipientId;
//    /**
//     * Поле, показывающее, что чат между пользователем и инженером активен
//     * (т.е. в данный момент «занят»).
//     */
//    private boolean active;
//}
//
package com.alibou.websocket.chatroom;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoom {
    private String chatId;       // общий id пары (engineer↔regular)
    private String senderId;     // кто «создал» запись (для поиска)
    private String recipientId;  // вторая сторона
    private boolean active;      // true – «занят», false – «свободен»
}