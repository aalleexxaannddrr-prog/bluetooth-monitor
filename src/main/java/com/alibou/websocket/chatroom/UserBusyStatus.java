package com.alibou.websocket.chatroom;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class UserBusyStatus {
    private String userId; // nick пользователя REGULAR
    private boolean busy;  // true – «занят», false – «свободен»
}