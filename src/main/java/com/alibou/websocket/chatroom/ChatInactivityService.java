package com.alibou.websocket.chatroom;

import com.alibou.websocket.user.OnlineUserStore;
import com.alibou.websocket.user.Status;
import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserRole;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ChatInactivityService {
    private final ChatRoomService chatRoomService;
    private final OnlineUserStore store;
    private final SimpMessagingTemplate messaging;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);

    // Таймеры «пары» (engineer↔regular):
    private final Map<String, ScheduledFuture<?>> pairTimers = new ConcurrentHashMap<>();

    // «Личные» таймеры инженера:
    private final Map<String, ScheduledFuture<?>> engineerTimers = new ConcurrentHashMap<>();

    // «Личные» таймеры обычного пользователя (REGULAR):
    private final Map<String, ScheduledFuture<?>> regularTimers = new ConcurrentHashMap<>();

    public ChatInactivityService(
            @Lazy ChatRoomService chatRoomService,
            OnlineUserStore store,
            SimpMessagingTemplate messaging
    ) {
        this.chatRoomService = chatRoomService;
        this.store = store;
        this.messaging = messaging;
    }

    /* ====================== 1. Методы для пары (engineer↔user) ====================== */

    /**
     * Запустить (или обновить) таймер пары: если REGULAR не получил «касание» от engineer в течение 15 сек,
     * то REGULAR «выкидывается» из системы.
     */
    public void touch(String engineerId, String userId) {
        String key = engineerId + '_' + userId;
        ScheduledFuture<?> prev = pairTimers.remove(key);
        if (prev != null) prev.cancel(true);

        ScheduledFuture<?> fut = pool.schedule(
                () -> onTimeoutPair(engineerId, userId, key),
                TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
        );
        pairTimers.put(key, fut);
    }

    private void onTimeoutPair(String engineerId, String userId, String key) {
        log.info("Авто-тайм-аут пары {} ↔ {}", engineerId, userId);
        chatRoomService.handleInactivity(engineerId, userId);
        pairTimers.remove(key);
    }

    public void cancel(String engineerId, String userId) {
        String key = engineerId + '_' + userId;
        ScheduledFuture<?> fut = pairTimers.remove(key);
        if (fut != null) fut.cancel(true);
    }


    public void cancelEngineer(String engineerId) {
        ScheduledFuture<?> fut = engineerTimers.remove(engineerId);
        if (fut != null) fut.cancel(true);
    }


    /* ====================== 3. Методы для «ло́чного пользователя» (REGULAR) ====================== */

    /**
     * Запустить (или обновить) «личный» таймер REGULAR: если REGULAR не писал сам себе или инженеру 15 сек,
     * его «выкидывает» автоматически.
     */
    public void touchRegular(String userId) {
        ScheduledFuture<?> prev = regularTimers.remove(userId);
        if (prev != null) prev.cancel(true);

        ScheduledFuture<?> fut = pool.schedule(
                () -> onRegularTimeout(userId),
                TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
        );
        regularTimers.put(userId, fut);
    }

    /**
     * Отменить «личный» таймер REGULAR (например, когда REGULAR начал писать сам себе).
     */
    public void cancelRegular(String userId) {
        ScheduledFuture<?> fut = regularTimers.remove(userId);
        if (fut != null) fut.cancel(true);
    }

    private void onRegularTimeout(String userId) {
        log.info("Авто-тайм-аут REGULAR {}", userId);
        // 1) Удаляем REGULAR-а из онлайна
        store.forceRemove(userId);
        // 2) Оповещаем всех, что REGULAR ушёл в OFFLINE
        messaging.convertAndSend(
                "/topic/public",
                new User(userId, Status.OFFLINE, UserRole.REGULAR)
        );
        // 3) Деактивируем любые чаты, связанные с этим REGULAR
        chatRoomService.deactivateChatsForUser(userId);
        regularTimers.remove(userId);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
