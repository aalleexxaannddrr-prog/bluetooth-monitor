package com.alibou.websocket.user;

import com.alibou.websocket.exception.NickAlreadyOnlineException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @MessageMapping("/user.addUser")
    @SendTo("/topic/public")
    public User addUser(@Payload User user,
                        @Header("simpSessionId") String sessId) {
        userService.saveUser(user, sessId);
        return user;
    }

    @MessageMapping("/user.disconnectUser")
    @SendTo("/topic/public")
    public User disconnectUser(@Payload User user,
                               @Header("simpSessionId") String sessionId) {

        userService.disconnect(user.getNickName(), sessionId);
        return user;           // прилетит тем, кто подписан на /topic/public
    }

    @GetMapping("/users")
    public ResponseEntity<List<User>> findConnectedUsers(@RequestParam(required = false) String role) {
        if ("ENGINEER".equals(role)) {
            // Если пришёл запрос от инженера — показываем только "доступных" пользователей
            return ResponseEntity.ok(userService.findConnectedUsersForEngineer());
        } else {
            // Иначе просто список всех онлайн
            return ResponseEntity.ok(userService.findConnectedUsers());
        }
    }
    @MessageExceptionHandler(NickAlreadyOnlineException.class)
    @SendToUser("/queue/errors")
    public String handleNickBusy(NickAlreadyOnlineException ex) {
        return ex.getMessage();              // «Ник "1" уже используется»
    }
}
