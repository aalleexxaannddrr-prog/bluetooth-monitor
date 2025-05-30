package com.alibou.websocket.logs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
@Controller
public class ComposeLogsController {

    @GetMapping(value = "/logs/all", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getAllLogs() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker-compose", "logs", "--no-color", "--tail", "100"
            );
            pb.redirectErrorStream(true);
            log.info("Running: docker-compose logs --no-color --tail 100");

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            StringBuilder logs = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                logs.append(line).append("\n");
            }

            process.waitFor();
            return ResponseEntity.ok(logs.toString());

        } catch (Exception e) {
            log.error("Error fetching logs", e);
            return ResponseEntity.internalServerError().body("Error fetching logs: " + e.getMessage());
        }
    }
}
