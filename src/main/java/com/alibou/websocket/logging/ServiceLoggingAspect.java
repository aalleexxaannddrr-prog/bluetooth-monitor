package com.alibou.websocket.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Configuration
public class ServiceLoggingAspect {

    /** Все public-методы, отмеченные @Service */
    @Around("within(@org.springframework.stereotype.Service *)")
    public Object logAround(ProceedingJoinPoint pjp) throws Throwable {

        long start = System.currentTimeMillis();
        log.debug("ВХОД  {}({})",
                pjp.getSignature().getName(),
                argList(pjp.getArgs()));

        try {
            Object result = pjp.proceed();
            long took = System.currentTimeMillis() - start;
            log.debug("ВЫХОД {} → {} ({} мс)",
                    pjp.getSignature().getName(),
                    shorten(result),
                    took);
            return result;

        } catch (Throwable ex) {
            log.warn("ОШИБКА {} – {}", pjp.getSignature().getName(), ex.toString());
            throw ex;
        }
    }
    private String argList(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return Arrays.stream(args)
                .map(this::shorten)                 // сокращаем каждый аргумент
                .collect(Collectors.joining(", "));
    }

    /** Делаем «компактный» вывод объекта, чтобы не засорять журнал. */
    private String shorten(Object o) {

        // 1. null
        if (o == null) return "null";

        // 2. Простые типы, enum
        if (o instanceof Number || o instanceof Boolean || o instanceof Enum<?>)
            return o.toString();

        // 3. Строки — обрезаем после 120 символов
        if (o instanceof String s) {
            return s.length() <= 120
                    ? "\"" + s + "\""
                    : "\"" + s.substring(0, 117) + "…\"(длина=" + s.length() + ")";
        }

        // 4. Массивы
        if (o.getClass().isArray()) {
            return o.getClass().getComponentType().getSimpleName() +
                    "[" + Array.getLength(o) + "]";
        }

        // 5. Коллекции
        if (o instanceof Collection<?> c) {
            return c.getClass().getSimpleName() + "(размер=" + c.size() + ")";
        }

        // 6. Map
        if (o instanceof Map<?, ?> m) {
            return m.getClass().getSimpleName() + "(размер=" + m.size() + ")";
        }

        // 7. JPA-Entity: выводим «User[id=…]»
        try {
            Method idGetter = o.getClass().getMethod("getId");
            Object id = idGetter.invoke(o);
            return o.getClass().getSimpleName() + "[id=" + id + "]";
        } catch (NoSuchMethodException ignored) {
            // нет getId() — падаем в «фолбэк»
        } catch (Exception e) {
            // reflection сломался — тоже фолбэк
        }

        // 8. Фолбэк — только имя класса
        return o.getClass().getSimpleName();
    }
    // helpers …
}
