package com.alibou.websocket.chatroom;

import com.alibou.websocket.user.OnlineUserStore;
import com.alibou.websocket.user.Status;
import com.alibou.websocket.user.User;
import com.alibou.websocket.user.UserRole;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Финальная версия: один watchdog-поток сканирует дедлайны,
 * никаких ScheduledFuture и гонок cancel→run.
 */
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = {@Lazy})
public class ChatInactivityService {

    /* ===== инфраструктура и настройки ===== */

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ChatRoomService       chatRoomService;
    private final OnlineUserStore       store;
    private final SimpMessagingTemplate messaging;

    /** «Часовой» для всех тайм-аутов */
    private final DebouncedTimeouts watchdog = new DebouncedTimeouts();

    /* ==================== 1. Таймер пары engineer ↔ regular ==================== */

    public void touch(String engineerId, String userId) {
        String key = "pair:" + engineerId + '_' + userId;
        watchdog.touch(key, TIMEOUT,
                () -> onTimeoutPair(engineerId, userId, key));
    }

    public void cancel(String engineerId, String userId) {
        watchdog.cancel("pair:" + engineerId + '_' + userId);
    }

    private void onTimeoutPair(String engineerId, String userId, String key) {
        log.info("Авто-тайм-аут пары {} ↔ {}", engineerId, userId);
        chatRoomService.handleInactivity(engineerId, userId);
        watchdog.cancel(key);            // на всякий случай
    }

    /* ==================== 2. «Личный» таймер REGULAR ==================== */

    public void touchRegular(String userId) {
        watchdog.touch("reg:" + userId, TIMEOUT,
                () -> onRegularTimeout(userId));
    }

    public void cancelRegular(String userId) {
        watchdog.cancel("reg:" + userId);
    }

    private void onRegularTimeout(String userId) {
        log.info("Авто-тайм-аут REGULAR {}", userId);

        // 1) удаляем из онлайна
        store.forceRemove(userId);

        // 2) оповещаем всех
        messaging.convertAndSend(
                "/topic/public",
                new User(userId, Status.OFFLINE, UserRole.REGULAR)
        );

        // 3) деактивируем связанные чаты
        chatRoomService.deactivateChatsForUser(userId);
    }

    /* ==================== 3. «Личный» таймер инженера (не нужен) ==================== */

    public void cancelEngineer(String engineerId) {
        watchdog.cancel("eng:" + engineerId);
    }

    /* ==================== 4. shutdown ==================== */

    @PreDestroy
    public void shutdown() {
        watchdog.shutdown();
    }

    /* ========================================================================== */
    /*                          Внутренний watchdog-класс                         */
    /* ========================================================================== */

    private static final class DebouncedTimeouts {

        @RequiredArgsConstructor
        private static final class Entry {
            final Runnable onTimeout;
            final AtomicLong expiresAt = new AtomicLong();
        }

        private static final long PERIOD_MS = 500;

        private final Map<String, Entry> timers = new ConcurrentHashMap<>();

        private final ScheduledExecutorService guard =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "chat-timeout-watchdog");
                    t.setDaemon(true);
                    return t;
                });

        DebouncedTimeouts() {
            guard.scheduleAtFixedRate(this::scan,
                    PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        }

        /** «Прикоснуться» к таймеру (создаёт либо обновляет). */
        void touch(String key, Duration ttl, Runnable onTimeout) {
            long deadline = System.currentTimeMillis() + ttl.toMillis();
            timers.compute(key, (k, e) -> {
                if (e == null) e = new Entry(onTimeout);
                e.expiresAt.set(deadline);
                return e;
            });
        }

        /** Отменить таймер. */
        void cancel(String key) {
            timers.remove(key);
        }

        /** Обход всех дедлайнов. */
        private void scan() {
            long now = System.currentTimeMillis();
            timers.forEach((key, entry) -> {
                if (now >= entry.expiresAt.get()) {
                    timers.remove(key);
                    try {
                        entry.onTimeout.run();
                    } catch (Exception ex) {
                        log.error("Timeout handler failed", ex);
                    }
                }
            });
        }

        void shutdown() {
            guard.shutdownNow();
        }
    }
}
