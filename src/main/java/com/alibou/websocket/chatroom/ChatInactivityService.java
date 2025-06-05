package com.alibou.websocket.chatroom;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class ChatInactivityService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ScheduledExecutorService pool =
            Executors.newScheduledThreadPool(1);

    private final Map<String, ScheduledFuture<?>> timers =
            new ConcurrentHashMap<>();

    /** зависимость получаем лениво через КОНСТРУКТОР */
    private final ChatRoomService chatRoomService;

    public ChatInactivityService(@Lazy ChatRoomService chatRoomService) {
        this.chatRoomService = chatRoomService;
    }

    /* ---------- API ---------- */
    public void touch(String engineerId, String userId) {
        String key = engineerId + '_' + userId;
        ScheduledFuture<?> prev = timers.remove(key);
        if (prev != null) prev.cancel(false);

        ScheduledFuture<?> fut = pool.schedule(() ->
                        onTimeout(engineerId, userId, key),
                TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);

        timers.put(key, fut);
    }

    /* --- НОВЫЙ приватный обработчик --- */
    private void onTimeout(String engineerId, String userId, String key) {
        log.info("Авто-тайм-аут пары {} ↔ {}", engineerId, userId);
        chatRoomService.handleInactivity(engineerId, userId);   // ← новая логика
        timers.remove(key);                                     // подчистили map
    }

    public void cancel(String engineerId, String userId) {
        String key = engineerId + '_' + userId;
        ScheduledFuture<?> fut = timers.remove(key);
        if (fut != null) fut.cancel(false);
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }
}
