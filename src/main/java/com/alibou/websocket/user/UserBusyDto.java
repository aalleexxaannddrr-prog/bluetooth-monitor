package com.alibou.websocket.user;

public record UserBusyDto(
        String nickName,
        UserRole role,
        boolean busy          // true – в активном чате
) {}
