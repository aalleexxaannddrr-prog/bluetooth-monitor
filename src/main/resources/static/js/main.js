'use strict';

const usernamePage = document.querySelector('#username-page');
const chatPage = document.querySelector('#chat-page');
const usernameForm = document.querySelector('#usernameForm');
const messageForm = document.querySelector('#messageForm');
const messageInput = document.querySelector('#message');
const connectingElement = document.querySelector('.connecting');
const chatArea = document.querySelector('#chat-messages');
const logout = document.querySelector('#logout');
const finishChatBtn = document.getElementById('finishChat');
const chatBusy = {};
finishChatBtn.addEventListener('click', deactivateCurrentChat, true);
let stompClient = null;
let nickname = null;
let role = null;
let selectedUserId = null;
let lastRenderedMsgId = null;

// Обработчик для отправки сообщения при нажатии Enter (без Shift)
messageInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage(e);
    }
});

function switchToChatUI() {
    usernamePage.classList.add('hidden');
    chatPage.classList.remove('hidden');

    if (role === 'REGULAR') messageForm.classList.remove('hidden');
    document.querySelector('#connected-user-nickname').textContent = nickname;

    if (role === 'ENGINEER') findAndDisplayConnectedUsers();
    if (role === 'ADMIN') adminInit();
}

async function deactivateCurrentChat() {
    if (!selectedUserId) return;           // если собеседника нет — выходим

    // Кто инженер, а кто обычный пользователь?
    const engineerId = role === 'ENGINEER' ? nickname : selectedUserId;
    const userId = role === 'ENGINEER' ? selectedUserId : nickname;

    // говорим серверу «чат окончен»
    await fetch(`/chatrooms/deactivate/${engineerId}/${userId}`, {method: 'POST'});

    // чистим экран
    selectedUserId = null;
    chatArea.innerHTML = '';
    messageForm.classList.add('hidden');
    finishChatBtn.classList.add('hidden');

    // если ты инженер — вернём пользователя обратно в список
    if (role === 'ENGINEER') {
        await findAndDisplayConnectedUsers();
    }
}

/**
 * Подключаемся к сокету и настраиваем подписки
 */
function connect(event) {
    nickname = document.querySelector('#nickname').value.trim();
    role = document.querySelector('#role').value.trim();

    if (nickname && role) {
        // создаём SockJS + STOMP без предварительного скрытия формы
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);
        stompClient.connect({nickName: nickname}, onConnected, onError);
    }
    event.preventDefault();
}

/**
 * Коллбэк при успешном подключении STOMP
 */
function onConnected() {

    /* 1. личная очередь ошибок (ник занят) ---------------- */
    stompClient.subscribe('/user/queue/errors', frame => {
        alert(frame.body);
        stompClient.disconnect();           // остаёмся на форме логина
    });

    /* 2. подтверждение успешной регистрации --------------- */
    /* 2. подтверждение успешной регистрации --------------- */
    stompClient.subscribe('/topic/public', frame => {
        const user = JSON.parse(frame.body);

        /* ===  NEW: нас отключили за бездействие  ===================== */
        if (user.nickName === nickname && user.status === 'OFFLINE') {
            alert('Вы были отключены за отсутствие активности');

            /* сворачиваем UI в исходное состояние */
            chatPage.classList.add('hidden');
            usernamePage.classList.remove('hidden');
            messageForm.classList.add('hidden');
            finishChatBtn.classList.add('hidden');
            selectedUserId = null;
            lastRenderedMsgId = null;

            /* закрываем STOMP-сессию (чтобы можно было залогиниться заново) */
            if (stompClient && stompClient.connected) {
                stompClient.disconnect();
            }
            return;                         // дальше код не нужен
        }
        /* ============================================================= */

        /* если это мы сами и статус ONLINE – просто заходим в чат-UI */
        if (user.nickName === nickname && user.status === 'ONLINE') {
            switchToChatUI();
            return;
        }

        /* ---------- реакция на любые ONLINE/OFFLINE REGULAR-ов -------- */
        if (role === 'ENGINEER' && user.role === 'REGULAR') {
            findAndDisplayConnectedUsers();   // обновляем список
        }

        /* ---------- активный собеседник внезапно OFFLINE ------------- */
        if (user.role === 'REGULAR' &&
            user.status === 'OFFLINE' &&
            user.nickName === selectedUserId) {

            selectedUserId = null;
            chatArea.innerHTML = '';
            messageForm.classList.add('hidden');
            finishChatBtn.classList.add('hidden');
        }
    });


    /* 3. персональная очередь сообщений ------------------ */
    stompClient.subscribe(`/queue/${nickname}`, onMessageReceived);

    /* 4. ВСЕ слушают смену статуса пользователей ---------- */
    stompClient.subscribe('/topic/user-status', onUserStatus);

    /* 5. просим сервер зарегистрировать ------------------ */
    stompClient.send(
        '/app/user.addUser',
        {},
        JSON.stringify({nickName: nickname, role: role, status: 'ONLINE'})
    );
}


/**
 * Загружаем всех ONLINE-пользователей с сервера и отображаем
 */
async function findAndDisplayConnectedUsers() {
    try {
        // Если текущий пользователь – инженер, добавим ?role=ENGINEER
        let url = '/users';
        if (role === 'ENGINEER') {
            url += '?role=ENGINEER';
        }

        const connectedUsersResponse = await fetch(url);
        let connectedUsers = await connectedUsersResponse.json();

        // Фильтруем, чтобы убрать из списка самого себя
        connectedUsers = connectedUsers.filter(user => user.nickName !== nickname);

        const connectedUsersList = document.getElementById('connectedUsers');
        connectedUsersList.innerHTML = '';

        connectedUsers.forEach((user, index) => {
            appendUserElement(user, connectedUsersList);
            if (index < connectedUsers.length - 1) {
                const separator = document.createElement('li');
                separator.classList.add('separator');
                connectedUsersList.appendChild(separator);
            }
        });
    } catch (error) {
        console.error('Ошибка при загрузке пользователей:', error);
    }
}

async function onUserStatus(payload) {
    let status;
    try {
        status = JSON.parse(payload.body);
    } catch {
        return;
    }

    const userId = status.userId;   // чей статус пришёл
    const busy = status.busy;     // true = занят, false = свободен

    /* ---------- 1. REGULAR слышит «я стал свободен» --------------- */
    if (role === 'REGULAR' && userId === nickname && !busy) {
        selectedUserId = null;
        chatArea.innerHTML = '';
        messageForm.classList.add('hidden');
        finishChatBtn.classList.add('hidden');
        return;                     // дальше ничего не нужно
    }

    /* ---------- 2. остальное важно только инженеру ---------------- */
    if (role !== 'ENGINEER') return;

    /* 2-A. если сообщение про активного собеседника и тот освободился */
    if (userId === selectedUserId && !busy) {
        selectedUserId = null;
        chatArea.innerHTML = '';
        messageForm.classList.add('hidden');
        finishChatBtn.classList.add('hidden');
        document.querySelectorAll('.user-item').forEach(li =>
            li.classList.remove('active'));
    }

    /* 2-B. обновляем список онлайн-пользователей */
    if (busy) {
        // пользователь «занят» – убираем из списка
        const li = document.getElementById(userId);
        li && li.remove();
    } else {
        // стал «свободен» – перерисуем список
        await findAndDisplayConnectedUsers();
    }
}

/**
 * Формируем li-элемент для списка пользователей (только инженер видит)
 */
function appendUserElement(user, connectedUsersList) {
    const listItem = document.createElement('li');
    listItem.classList.add('user-item');
    listItem.id = user.nickName;

    const userImage = document.createElement('img');
    userImage.src = '../img/user_icon.png';
    userImage.alt = user.nickName;

    const usernameSpan = document.createElement('span');
    usernameSpan.textContent = user.nickName;

    const receivedMsgs = document.createElement('span');
    receivedMsgs.textContent = '0';
    receivedMsgs.classList.add('nbr-msg', 'hidden');

    listItem.appendChild(userImage);
    listItem.appendChild(usernameSpan);
    listItem.appendChild(receivedMsgs);

    // Клик по имени пользователя (только у инженера)
    listItem.addEventListener('click', userItemClick);

    connectedUsersList.appendChild(listItem);
}

/**
 * Когда инженер кликает на пользователя, открывается диалог
 */
async function userItemClick(event) {
    // Подсветка активного пользователя
    document.querySelectorAll('.user-item').forEach(item => {
        item.classList.remove('active');
    });
    messageForm.classList.remove('hidden');

    const clickedUser = event.currentTarget;
    clickedUser.classList.add('active');

    selectedUserId = clickedUser.getAttribute('id');
    await fetch(`/chatrooms/activate/${nickname}/${selectedUserId}`, {method: 'POST'});
// 2. показываем кнопку «Закончить разговор»
    finishChatBtn.classList.remove('hidden');
// 3. обновляем список – у других инженеров пользователь исчезнет
    await findAndDisplayConnectedUsers();
    fetchAndDisplayUserChat().then();

    // Сбрасываем счётчик непрочитанных для данного пользователя
    const nbrMsg = clickedUser.querySelector('.nbr-msg');
    nbrMsg.classList.add('hidden');
    nbrMsg.textContent = '0';
}

/**
 * Подгружаем историю сообщений между текущим (nickname) и выбранным (selectedUserId)
 */
async function fetchAndDisplayUserChat() {
    try {
        const userChatResponse = await fetch(`/messages/${nickname}/${selectedUserId}`);
        const userChat = await userChatResponse.json();
        chatArea.innerHTML = '';
        userChat.forEach(chat => {
            displayMessage(chat.senderId, chat.content, chat.id);
        });
        chatArea.scrollTop = chatArea.scrollHeight;
    } catch (error) {
        console.error('Ошибка при загрузке чата:', error);
    }
}

/**
 * Обработчик ошибок WebSocket
 */
function onError(frame) {
    if (frame && frame.headers && frame.headers['message']) {
        alert(frame.headers['message']);   // «Ник уже используется»
        window.location.reload();
    } else {
        connectingElement.textContent =
            'Не удалось подключиться к WebSocket. Пожалуйста, попробуйте снова!';
        connectingElement.style.color = 'red';
    }
}

/**
 * Отправляем новое сообщение
 */
function sendMessage(event) {
    // REGULAR до привязки к инженеру шлёт самому себе
    if (!selectedUserId && role === 'REGULAR') selectedUserId = nickname;
    if (!selectedUserId) return;

    const text = messageInput.value.trim();
    if (text && stompClient) {
        const chatMessage = {
            senderId: nickname,
            recipientId: selectedUserId,
            content: text,
            timestamp: new Date()
        };
        stompClient.send("/app/chat", {}, JSON.stringify(chatMessage));

        /* echo только если пишем НЕ себе (иначе придёт с брокера) */
        if (nickname !== selectedUserId) {
            displayMessage(nickname, text);
        }
        messageInput.value = '';
    }

    chatArea.scrollTop = chatArea.scrollHeight;
    event.preventDefault();
}


/**
 * Когда приходит сообщение (либо из /topic/public, либо личное (/queue/<user>))
 */
async function onMessageReceived(payload) {
    console.log('Message received', payload);
    if (role === 'ENGINEER') {
        // payload от /topic/public содержит объект User со статусом ONLINE / OFFLINE
        let msg;
        try {
            msg = JSON.parse(payload.body);
        } catch {
            msg = {};
        }

        // интересуют только REGULAR-ы и только если он ONLINE
        if (msg.role === 'REGULAR' && msg.status === 'ONLINE') {
            await findAndDisplayConnectedUsers();
        }
    }
    // Если это не валидный JSON — пропускаем
    let message;
    try {
        message = JSON.parse(payload.body);
    } catch (err) {
        console.error('Не удалось распарсить сообщение:', err);
        return;
    }


    // Если мы REGULAR и ещё не видим чат, значит, инженер начал диалог
    if (role === 'REGULAR') {

        if (chatPage.classList.contains('hidden')) {
            chatPage.classList.remove('hidden');
        }

        // первый вход: ещё вообще нет selectedUserId
        if (!selectedUserId) {
            selectedUserId = message.senderId;
            messageForm.classList.remove('hidden');
            await fetchAndDisplayUserChat();
        }

        // ► если инженер другой – переключаемся
        if (message.senderId !== nickname       // прислал не «я»
            && message.senderId !== selectedUserId) { // и это новый инженер
            selectedUserId = message.senderId;
            chatArea.innerHTML = '';
            await fetchAndDisplayUserChat();
            messageForm.classList.remove('hidden');
        }
        finishChatBtn.classList.remove('hidden');
    }


    if (selectedUserId
        && message.senderId === selectedUserId
        && message.id.toString() !== lastRenderedMsgId) {
        displayMessage(message.senderId, message.content, message.id);
        chatArea.scrollTop = chatArea.scrollHeight;
    } else {
        // Иначе это может быть сообщение от другого пользователя.
        // Если мы инженер, покажем счётчик непрочитанного.
        // Если REGULAR, у него всё равно только один собеседник-инженер.
        if (role === 'ENGINEER') {
            const notifiedUser = document.querySelector(`#${message.senderId}`);
            if (notifiedUser && !notifiedUser.classList.contains('active')) {
                const nbrMsg = notifiedUser.querySelector('.nbr-msg');
                nbrMsg.classList.remove('hidden');
                nbrMsg.textContent = '1';
            }
        }
    }
}

/**
 * Отображаем сообщение (простой вывод в чат)
 */
function displayMessage(senderId, content, id = null) {
    const messageContainer = document.createElement('div');
    messageContainer.classList.add('message');
    // Если это наше собственное сообщение
    if (senderId === nickname) {
        messageContainer.classList.add('sender');
    } else {
        messageContainer.classList.add('receiver');
    }

    const message = document.createElement('p');
    message.textContent = content;
    messageContainer.appendChild(message);
    chatArea.appendChild(messageContainer);
    if (id !== null && id !== undefined) {
        lastRenderedMsgId = id.toString();
    }
}

/**
 * Завершаем сеанс (клик по «Выйти»)
 */
function onLogout() {
    // Сообщаем серверу, что пользователь вышел
    stompClient.send(
        "/app/user.disconnectUser",
        {},
        JSON.stringify({nickName: nickname, status: 'OFFLINE'})
    );
    finishChatBtn.classList.add('hidden');
    window.location.reload();
}

function switchToChatUI() {
    usernamePage.classList.add('hidden');
    chatPage.classList.remove('hidden');
    document.querySelector('#connected-user-nickname').textContent = nickname;

    if (role === 'REGULAR') messageForm.classList.remove('hidden');
    if (role === 'ENGINEER') findAndDisplayConnectedUsers();
    if (role === 'ADMIN') adminInit();          // ← новый вызов
}

/* --- инициализация UI администратора --- */
async function adminInit() {
    // 1) прячем лишние поля и показываем кнопку «Удалить»
    messageForm.classList.add('hidden');
    finishChatBtn.classList.add('hidden');
    document.getElementById('kickUser')
        .classList.remove('hidden-admin');

    // 2) рисуем список онлайн-пользователей
    await adminReloadOverview();

    // 3) «подглядываем» все новые сообщения
    stompClient.subscribe('/topic/admin-feed', frame => {
        const msg = JSON.parse(frame.body);            // новое сообщение
        const pair = selectedPair();                   // выбранная пара в чате
        if (!pair) return;
        const [left, right] = pair;
        if (
            (msg.senderId === left && msg.recipientId === right) ||
            (msg.senderId === right && msg.recipientId === left)
        ) {
            displayMessage(msg.senderId, msg.content, msg.id);
            chatArea.scrollTop = chatArea.scrollHeight;
        }
    });

    // 4) ONLINE / OFFLINE
    stompClient.subscribe('/topic/public', frame => {
        const u = JSON.parse(frame.body);              // {nickName, role, status}
        if (u.role === 'ADMIN') return;                // самих админов не показываем

        if (u.status === 'OFFLINE') {                  // ушёл – убираем
            document.getElementById(u.nickName)?.remove();
            delete chatBusy[u.nickName];
            return;
        }
        // ONLINE – добавляем/обновляем
        upsertUserLi(u.nickName, u.role, chatBusy[u.nickName] ?? false);
    });

    // 5) BUSY / FREE
    stompClient.subscribe('/topic/user-status', frame => {
        const s = JSON.parse(frame.body);              // {userId, busy}
        chatBusy[s.userId] = s.busy;
        const li = document.getElementById(s.userId);
        if (li) {
            li.classList.toggle('busy', s.busy);
            li.onclick = () => adminSelectUser(s.userId, s.busy);
        } else {
            // был OFFLINE, а теперь пришёл BUSY/FREE – перерисуем весь список
            adminReloadOverview();
        }
    });
}

/* --- получить список занятости с сервера --- */
async function adminReloadOverview() {
    const res = await fetch('/admin/overview');
    const users = await res.json();                 // [{nickName, role, busy}, …]
    const list = document.getElementById('connectedUsers');
    list.innerHTML = '';

    users.forEach(u => {
        chatBusy[u.nickName] = u.busy;              // запоминаем занятость
        upsertUserLi(u.nickName, u.role, u.busy, list);
    });
}

function upsertUserLi(nick, roleLabel, busy, listEl = null) {
    const list = listEl || document.getElementById('connectedUsers');
    let li = document.getElementById(nick);
    if (!li) {                                   // ещё нет – создаём
        li = document.createElement('li');
        li.id = nick;
        li.classList.add('user-item');
        list.appendChild(li);
    }
    li.classList.toggle('busy', busy);           // красная точка
    li.textContent = `${nick} (${roleLabel})`;
    li.onclick = () => adminSelectUser(nick, busy);
}
/* --- выбор пользователя / инженера --- */
async function adminSelectUser(nick, busy) {
    if (!busy) return;           // смотреть можно лишь занятых
    const pRes = await fetch(`/admin/partner/${nick}`);
    if (!pRes.ok) return;
    const partner = await pRes.text();

    chatArea.innerHTML = '';
    /* сохраняем пару в data-* чтобы потом фильтровать входящие */
    chatArea.dataset.left = nick;
    chatArea.dataset.right = partner;

    const history = await fetch(`/messages/${nick}/${partner}`)
        .then(r => r.json());
    history.forEach(m => displayMessage(m.senderId, m.content, m.id));
    chatArea.scrollTop = chatArea.scrollHeight;
}

/* helper – какие две стороны сейчас выбраны */
function selectedPair() {
    const l = chatArea.dataset.left;
    const r = chatArea.dataset.right;
    return l && r ? [l, r] : null;
}

/* --- «Кик» свободного пользователя --- */
async function kickSelected() {
    const sel = document.querySelector('.user-item.active');
    if (!sel) return;
    const nick = sel.id;
    const liBusy = sel.classList.contains('busy');
    if (liBusy) {
        alert('Нельзя удалить занятого пользователя');
        return;
    }
    await fetch(`/admin/kick/${nick}`, {method: 'DELETE'});
    await adminReloadOverview();
}

/* кнопку «Удалить пользователя» (создайте в html внутри .users-footer) */
document.getElementById('kickUser')
    ?.addEventListener('click', kickSelected, true);

// Подписываемся на события формы
usernameForm.addEventListener('submit', connect, true);
messageForm.addEventListener('submit', sendMessage, true);
logout.addEventListener('click', onLogout, true);

// При перезагрузке или закрытии страницы отправляем disconnect
window.onbeforeunload = () => onLogout();
