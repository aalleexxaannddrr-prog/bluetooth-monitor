package com.alibou.websocket.logs;


import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

@RestController
public class DockerLogsController {

    private final DockerClient dockerClient;

    public DockerLogsController() {
        // Конфигурация клиента для Unix-сокета /var/run/docker.sock
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();
        this.dockerClient = DockerClientBuilder.getInstance(config).build();
    }

    /**
     * Возвращает последние 100 строк логов контейнера.
     *
     * @param name имя контейнера (например, go_mind_backend)
     */
    @GetMapping(value = "/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<StreamingResponseBody> getLogs(
            @RequestParam("container") String name) {

        StreamingResponseBody body = outputStream -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            // 1) объявляем именно LogContainerResultCallback
            LogContainerResultCallback callback = new LogContainerResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        buffer.write(frame.getPayload());
                    } catch (Exception ignored) {}
                    super.onNext(frame);
                }
            };

            try {
                // 2) exec возвращает тот же callback, и на нём доступен awaitCompletion
                dockerClient.logContainerCmd(name)
                        .withStdErr(true)
                        .withStdOut(true)
                        .withTail(100)
                        .exec(callback)
                        .awaitCompletion(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // можно залогировать или вернуть ошибку
            }

            // 3) выдаём клиенту собранные логи
            outputStream.write(buffer.toByteArray());
            outputStream.flush();
        };

        return ResponseEntity.ok(body);
    }

}