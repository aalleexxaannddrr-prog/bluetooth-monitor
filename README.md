```markdown
# Bluetooth Monitor

Пример веб-приложения на WebSocket (SockJS + STOMP) для обмена сообщениями между инженерами и клиентами в реальном времени.

- **Frontend** — HTML/JS (одностраничное приложение: `index.html` и `main.js`).
- **Backend** — Spring Boot-приложение (WebSocket-эндпоинты, REST для пользователей и сообщений).
- **Docker** — контейнер для развёртывания.

Данное приложение реализует чат **инженера** с **клиентом (regular user)**, включая хранение сообщений и список пользователей (онлайн/оффлайн).

## 1. Сборка и запуск

### 1.1 Через Docker

1. Убедитесь, что установлены Docker и Docker Compose.
2. В корне проекта выполните:
   ```bash
   docker-compose down
   docker-compose build --no-cache
   docker-compose up -d
   ```
3. Приложение будет доступно по адресу `http://<IP_сервера>:8080`

---

### 1.2 Без Docker (опционально)

1. Соберите Spring Boot-приложение (Maven или Gradle), получив jar-файл (например, `bluetooth-monitor.jar`).
2. Запустите его:
   ```bash
   java -jar target/bluetooth-monitor.jar
   ```
3. Перейдите по адресу:
   ```
   http://localhost:8080
   ```

---

## Использование в Android

Чтобы интегрировать это приложение в ваше Android-приложение, необходима работа как с **REST API**, так и с **WebSocket (SockJS + STOMP)**.

### 1. REST API

REST-эндпоинты используются для:
- Загрузки списка пользователей (эндпоинт: `GET /users`).
- Получения истории сообщений (эндпоинт: `GET /messages/{from}/{to}`).

#### 1.1 Получение списка пользователей

```kotlin
// Пример с Retrofit (упрощён)
val users = api.getUsers() // GET /users
// users: List<User>
```

Возвращаемый JSON может выглядеть так:
```json
[
  {
    "nickName": "engineer1",
    "role": "ENGINEER",
    "status": "ONLINE"
  },
  {
    "nickName": "user123",
    "role": "REGULAR",
    "status": "OFFLINE"
  }
]
```

#### 1.2 Получение истории сообщений

```kotlin
// Пример с Retrofit (упрощён)
val messages = api.getMessages("myNickname", "selectedUser") // GET /messages/{from}/{to}
// messages: List<ChatMessage>
```

Пример ответа:
```json
[
  {
    "id": 1,
    "senderId": "user123",
    "recipientId": "engineer1",
    "content": "Hello, I need help!",
    "timestamp": "2025-03-13T15:00:00Z"
  }
]
```

---

### 2. WebSocket (SockJS + STOMP)

Для обмена сообщениями в **реальном времени** используется WebSocket.

1. **Подключение** к `ws://<адрес_сервера>:8080/ws` (или `wss://<адрес_сервера>/ws` при наличии SSL).
2. **Подписка**:
   - Инженер (`ENGINEER`) слушает `/topic/public` (общая рассылка) и `/queue/{nickname}` (личные сообщения).
   - Обычный пользователь (`REGULAR`) — только `/queue/{nickname}`.
3. **Отправка** сообщений: на эндпоинт STOMP `"/app/chat"`.
4. **Регистрация** (ONLINE): `"/app/user.addUser"` (JSON: `{"nickName": "...", "role": "...", "status": "ONLINE"}`).
5. **Отключение** (OFFLINE): `"/app/user.disconnectUser"` (JSON: `{"nickName": "...", "status": "OFFLINE"}`).

#### 2.1 Пример (NaikSoftware/StompProtocolAndroid)

```kotlin
// 1. Создаём STOMP-клиент
val stompClient = Stomp.over(
    Stomp.ConnectionProvider.OKHTTP,
    "ws://<IP_сервера>:8080/ws" // или wss://<IP_сервера>/ws
)

// 2. Подключаемся и слушаем события
stompClient.lifecycle().subscribe { event ->
    when (event.type) {
        LifecycleEvent.Type.OPENED -> {
            Log.d("STOMP", "Подключено к WebSocket")
            // Можно отправить "user.addUser", если нужно
        }
        LifecycleEvent.Type.ERROR -> {
            Log.e("STOMP", "Ошибка: ${event.exception}")
        }
        LifecycleEvent.Type.CLOSED -> {
            Log.d("STOMP", "Соединение закрыто")
        }
    }
}

// 3. Старт соединения
stompClient.connect()

// 4. Подписка на личные сообщения
val myNickname = "user123"
stompClient.topic("/queue/$myNickname").subscribe { stompMessage ->
    val jsonBody = stompMessage.payload
    val chatMessage = Gson().fromJson(jsonBody, ChatMessage::class.java)
    // Обновляем UI, добавляем новое сообщение
}

// 5. Отправка нового сообщения
val chatMessage = ChatMessage(
    senderId = "user123",
    recipientId = "engineer1",
    content = "Привет из Android!",
    timestamp = Date()
)
val jsonMsg = Gson().toJson(chatMessage)
stompClient.send("/app/chat", jsonMsg).subscribe()
```

---

### 3. Общий алгоритм

1. **(Опционально)** Авторизация пользователя (через REST или получение токена).
2. **Загрузка** списка пользователей (`GET /users`) — если роль `ENGINEER`.
3. **Подключение** к WebSocket (`stompClient.connect()`).
4. **Регистрация** (ONLINE) — `"/app/user.addUser"`.
5. **Загрузка истории** (`GET /messages/{from}/{to}`) при выборе собеседника.
6. **Отправка сообщений** через STOMP `"/app/chat"`.
7. **Просмотр входящих** сообщений в `"/queue/{nickname}"`.
8. **Отключение** (OFFLINE) — `"/app/user.disconnectUser"` и `stompClient.disconnect()`.

---

### 4. Дополнительно

- **SSL и HTTPS**: для продакшена рекомендуется использовать WSS (защищённый WebSocket).
- **Безопасность**: в реальном проекте может потребоваться авторизация (JWT, OAuth2 и т.д.).
- **Сетевые настройки**: в Android 9+ убедитесь, что используете HTTPS/WSS или правильно настроили [Network Security Config](https://developer.android.com/training/articles/security-config).
```
