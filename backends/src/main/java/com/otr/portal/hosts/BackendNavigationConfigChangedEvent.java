package com.otr.portal.hosts;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

/**
 * Событие изменения настроек навигации бэкэнда.
 * <b>Сейчас учитвается только настройка "URL веб-сервиса навигации"</b>
 * Proxy и SSL учитываются через ключ кеширования клиента. Т.е при добавлении prozy
 *
 * @author alexraspopin
 * @since 29.04.2026
 */
@Setter
@Getter
public class BackendNavigationConfigChangedEvent extends ApplicationEvent {
    private final String backendName;

    public BackendNavigationConfigChangedEvent(Object source, String backendName) {
        super(source);
        this.backendName = backendName;
    }

}
