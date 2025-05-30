package com.alibou.websocket.logs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
public class ComposeLogsController {

    /**
     *  Пример вызова:
     *  GET http://localhost:8080/logs/backend?tail=200&follow=true
     *
     *  - <service> – как в docker-compose.yml
     *  - tail       – сколько последних строк подгрузить перед «follow» (0 – не подгружать)
     *  - follow     – true / false, включать ли «-f» (по умолчанию true)
     */
    @GetMapping(value = "/logs/{service}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<StreamingResponseBody> streamLogs(
            @PathVariable String service,
            @RequestParam(defaultValue = "100") int tail,
            @RequestParam(defaultValue = "true") boolean follow
    ) {

        List<String> cmd = new ArrayList<>();
        cmd.add("docker-compose");
        cmd.add("logs");
        cmd.add("--no-color");
        cmd.add("--tail");
        cmd.add(String.valueOf(tail));
        if (follow) cmd.add("-f");
        cmd.add(service);                     // имя сервиса в compose-файле

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);         // STDERR → STDOUT
        log.info("Running: {}", String.join(" ", cmd));

        return ResponseEntity.ok((StreamingResponseBody) outputStream -> {
            Process p = pb.start();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    // добавляем перенос строки, иначе всё «склеится»
                    outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();     // мгновенно отдаём клиенту
                }
            } finally {
                p.destroyForcibly();          // если клиент оборвал соединение
            }
        });
    }
}
