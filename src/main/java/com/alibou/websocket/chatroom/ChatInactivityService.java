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

        ScheduledFuture<?> fut = pool.schedule(() -> {
            log.info("Авто-закрытие пары {} ↔ {} (15 с тишины)", engineerId, userId);
            chatRoomService.deactivatePair(engineerId, userId);
            timers.remove(key);
        }, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        timers.put(key, fut);
    }

    public void cancel(String engineerId, String userId) {
        String key = engineerId + '_' + userId;
        ScheduledFuture<?> fut = timers.remove(key);
        if (fut != null) fut.cancel(false);
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }
}
