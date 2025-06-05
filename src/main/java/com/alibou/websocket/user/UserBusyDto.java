package com.alibou.websocket.user;
import com.alibou.websocket.user.UserRole;

public record UserBusyDto(
        String nickName,
        UserRole role,
        boolean busy          // true – в активном чате
) {}
