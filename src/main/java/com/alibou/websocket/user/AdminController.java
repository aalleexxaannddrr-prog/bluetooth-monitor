package com.alibou.websocket.user;


import com.alibou.websocket.chatroom.ChatRoomService;
import com.alibou.websocket.user.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final OnlineUserStore store;
    private final ChatRoomService chatRoomService;
    private final UserService     userService;
    private final SimpMessagingTemplate messaging;

    /** 1. Список инженеров и пользователей + флаг занятости */
    @GetMapping("/overview")
    public List<UserBusyDto> overview() {
        return store.all().stream()
                .filter(u -> u.getRole() != UserRole.ADMIN)          // сами админы не нужны
                .map(u -> new UserBusyDto(
                        u.getNickName(),
                        u.getRole(),
                        chatRoomService.isUserInActiveChat(u.getNickName())))
                .toList();
    }

    /** 2. Для выбранного юзера – узнать его активного собеседника */
    @GetMapping("/partner/{nick}")
    public ResponseEntity<String> partner(@PathVariable String nick) {
        return chatRoomService.findActivePartner(nick)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 3. Удалить «свободного» пользователя (REGULAR или ENGINEER) */
    @DeleteMapping("/kick/{nick}")
    public ResponseEntity<Void> kick(@PathVariable String nick) {
        if (chatRoomService.isUserInActiveChat(nick)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();   // ещё занят
        }
        userService.forceDisconnect(nick);                               // (<- новая функция)
        messaging.convertAndSend("/topic/public",
                new User(nick, Status.OFFLINE, UserRole.REGULAR));
        return ResponseEntity.ok().build();
    }
}
