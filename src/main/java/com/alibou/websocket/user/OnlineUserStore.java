package com.alibou.websocket.user;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OnlineUserStore {

    private record Online(String sessionId, User user) {}

    private final Map<String, Online> users = new ConcurrentHashMap<>();

    /** true – ник свободен и добавлен, false – занят */
    public boolean addIfAbsent(String nick, String sessionId, User u) {
        return users.putIfAbsent(nick, new Online(sessionId, u)) == null;
    }
    public void forceRemove(String nick) {
        users.remove(nick);
    }
    public void remove(String nick, String sessionId) {
        users.computeIfPresent(nick, (n, online) ->
                online.sessionId.equals(sessionId) ? null : online);
    }

    public Optional<User> get(String nick) {
        return Optional.ofNullable(users.get(nick)).map(o -> o.user);
    }

    public Collection<User> all() {
        return users.values().stream().map(o -> o.user).toList();
    }
}
