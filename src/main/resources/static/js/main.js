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
finishChatBtn.addEventListener('click', deactivateCurrentChat, true);
let stompClient = null;
let nickname = null;
let role = null;
let selectedUserId = null;
let lastRenderedMsgId = null;

// Обработчик для отправки сообщения при нажатии Enter (без Shift)
messageInput.addEventListener('keydown', function(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage(e);
    }
});

async function deactivateCurrentChat() {
    if (!selectedUserId) return;           // если собеседника нет — выходим

    // Кто инженер, а кто обычный пользователь?
    const engineerId = role === 'ENGINEER' ? nickname       : selectedUserId;
    const  userId    = role === 'ENGINEER' ? selectedUserId : nickname;

    // говорим серверу «чат окончен»
    await fetch(`/chatrooms/deactivate/${engineerId}/${userId}`, { method: 'POST' });

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
        // Скрываем форму с ником
        usernamePage.classList.add('hidden');

        // Инженеру сразу показываем чат
        // if (role === 'ENGINEER') {
        //     chatPage.classList.remove('hidden');
        // }
        chatPage.classList.remove('hidden');
        if (role === 'REGULAR') {
            messageForm.classList.remove('hidden');
        }
        if (role === 'REGULAR' && !selectedUserId) {
            // пока инженер не подключился, считаем «получателем» самого себя
            selectedUserId = nickname;
        }
        // Инициализируем SockJS + STOMP
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);

        // Подключаемся
        stompClient.connect({}, onConnected, onError);
    }
    event.preventDefault();
}

/**
 * Коллбэк при успешном подключении STOMP
 */
function onConnected() {
    // Подписка на личные сообщения:
    // вместо `/user/${nickname}/queue/messages` -> `/queue/<nickname>`
    stompClient.subscribe(`/queue/${nickname}`, onMessageReceived);

    // Только инженер подписывается на общий канал (топик) /topic/public
    if (role === 'ENGINEER') {
        stompClient.subscribe("/topic/public", onMessageReceived);
        stompClient.subscribe("/topic/user-status", onUserStatus);  // <--
    }

    // Регистрируем пользователя в базе (устанавливаем ONLINE)
    stompClient.send(
        "/app/user.addUser",
        {},
        JSON.stringify({ nickName: nickname, role: role, status: 'ONLINE' })
    );

    document.querySelector('#connected-user-nickname').textContent = nickname;

    // Если это инженер – подгружаем список онлайн-пользователей
    if (role === 'ENGINEER') {
        findAndDisplayConnectedUsers().then();
    } else {
        // Обычному пользователю список не показываем
        document.getElementById('connectedUsers').innerHTML = '';
    }
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
    try { status = JSON.parse(payload.body); } catch { return; }

    // если это наш собственный пользователь – игнорируем
    if (status.userId === nickname) return;

    const list   = document.getElementById('connectedUsers');
    const li     = document.getElementById(status.userId);

    if (!status.busy && role === 'ENGINEER' && selectedUserId === status.userId) {
        selectedUserId = null;                  // больше нет активного собеседника
        chatArea.innerHTML = '';                // чистим историю
        messageForm.classList.add('hidden');    // прячем форму ввода
        finishChatBtn.classList.add('hidden');  // прячем кнопку «Закончить разговор»

        // убираем подсветку в списке
        document.querySelectorAll('.user-item').forEach(item =>
            item.classList.remove('active')
        );
    }
    // 1. пользователь занят -> убрать из списка, если он там есть
    if (status.busy) {
        li && li.remove();
        return;
    }
    // 2. пользователь освободился -> подгрузим его, но только если он online
    //    (проще повторно запросить /users — это лёгкий GET)
    await findAndDisplayConnectedUsers();
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
    await fetch(`/chatrooms/activate/${nickname}/${selectedUserId}`, { method: 'POST' });
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
        // userChat.forEach(chat => {
        //     displayMessage(chat.senderId, chat.content);
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
function onError() {
    connectingElement.textContent =
        'Не удалось подключиться к WebSocket. Пожалуйста, обновите страницу и попробуйте снова!';
    connectingElement.style.color = 'red';
}

/**
 * Отправляем новое сообщение
 */
function sendMessage(event) {
    // Если у нас не выбран собеседник, ничего не делаем
    if (!selectedUserId && role === 'REGULAR') {
        // пользователь ещё не «прикреплён» к инженеру,
        // поэтому временно шлём сообщения «самому себе»
        selectedUserId = nickname;
    }
    if (!selectedUserId) {
        return;
    }

    const messageContent = messageInput.value.trim();
    if (messageContent && stompClient) {
        const chatMessage = {
            senderId: nickname,
            recipientId: selectedUserId,
            content: messageInput.value,  // сохраняем переносы строк
            timestamp: new Date()
        };
        stompClient.send("/app/chat", {}, JSON.stringify(chatMessage));
        displayMessage(nickname, messageInput.value);
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
        try { msg = JSON.parse(payload.body); } catch { msg = {}; }

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


    // Если нам пришло сообщение от текущего собеседника, сразу отобразим
    // if (selectedUserId && message.senderId === selectedUserId) {
    //
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
        JSON.stringify({ nickName: nickname, status: 'OFFLINE' })
    );
    finishChatBtn.classList.add('hidden');
    window.location.reload();
}

// Подписываемся на события формы
usernameForm.addEventListener('submit', connect, true);
messageForm.addEventListener('submit', sendMessage, true);
logout.addEventListener('click', onLogout, true);

// При перезагрузке или закрытии страницы отправляем disconnect
window.onbeforeunload = () => onLogout();
