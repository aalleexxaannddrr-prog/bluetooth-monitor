package com.alibou.websocket.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class ClientContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        try {
            // ● Простейшая логика: смотрим заголовки, можно и куку/JWT разобрать
            String userId = req.getHeader("X-Client-Id");
            String chatId = req.getHeader("X-Chat-Id");

            if (userId != null) MDC.put("userId", userId);
            if (chatId != null) MDC.put("chatId", chatId);

            chain.doFilter(req, res);
        } finally {
            MDC.clear();               // важно чистить!
        }
    }
}
