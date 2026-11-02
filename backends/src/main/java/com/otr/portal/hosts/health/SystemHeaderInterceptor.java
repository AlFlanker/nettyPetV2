package com.otr.portal.hosts.health;


import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Системные обработчики хидеров. Вызываются при любом обращении к бэкенду
 * @author pobedenniy.alexey
 * @since 16.07.18
 */
public interface SystemHeaderInterceptor {
    @NotNull
    Map<String, String> systemHeadersForBackend(String backend);
}
