package com.alibou.websocket.config;

import com.alibou.websocket.user.Status;
import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component                 // ⇒ Spring создаёт бин и регистрирует его в контексте
@RequiredArgsConstructor   // ⇒ Lombok сгенерирует конструктор с final-полями
public class WebSocketDisconnectListener
        implements ApplicationListener<SessionDisconnectEvent> {
    // ↑ слушаем именно события разрыва STOMP-сессии

    private final UserService userService;   // внедряем наш сервис

    @Override
    public void onApplicationEvent(SessionDisconnectEvent ev) {
        StompHeaderAccessor acc = StompHeaderAccessor.wrap(ev.getMessage());
        String nick = acc.getFirstNativeHeader("nickName");
        String ses  = acc.getSessionId();                // ← ID этой сессии
        if (nick != null) {
            userService.disconnect(nick, ses);
        }
    }
}
