package com.alibou.websocket.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter
@AllArgsConstructor      //  ← ДОБАВИЛИ
@NoArgsConstructor  // Lombok
public class User {                //   <-- убрали @Entity и @Table
    private String nickName;
    private Status status;
    private UserRole role;
}
