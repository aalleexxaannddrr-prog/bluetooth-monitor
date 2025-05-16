package com.alibou.websocket.chatroom;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chatrooms")
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    /** Инженер «берёт» пользователя */
    @PostMapping("/activate/{engineerId}/{userId}")
    public ResponseEntity<String> activate(@PathVariable String engineerId,
                                           @PathVariable String userId) {
        return ResponseEntity.ok(chatRoomService.activateChat(engineerId, userId));
    }

    /** Инженер «отпускает» пользователя, либо пользователь вышел из диалога */
    @PostMapping("/deactivate/{engineerId}/{userId}")
    public ResponseEntity<Void> deactivate(@PathVariable String engineerId,
                                           @PathVariable String userId) {
        chatRoomService.deactivatePair(engineerId, userId);
        return ResponseEntity.ok().build();
    }
}
