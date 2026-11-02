package com.otr.portal.hosts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.otr.portal.metadata.config.ApplicationProperties;
import com.otr.portal.metadata.config.PropertyGroup;
import com.otr.portal.metadata.extensions.ExtensibleConfigurationField;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * @author pobedenniy.alexey
 * @since 07.09.18
 */
public class HostGroup extends ExtensibleConfigurationField {
    @ApplicationProperties(name = "Имя группы")
    private String name;
    @ApplicationProperties(name = "Бэкенды")
    private List<Host> hosts;
    @PropertyGroup(name = "Страница по умолчанию")
    private DefaultUri defaultUri;
    @PropertyGroup(name = "Страница выхода", description = "Страница целевой системы, вызов которой будет осуществлен во время выхода из Портала")
    private LogoutUrl logoutUri;
    @PropertyGroup(name = "Перезапись ссылок контента", description = "Данная опция включает замену всех ссылок, в контенте, который возвращает бэкенд, на ссылки, ведущие через Портал.", warning = "Данная опция может серьезно снизить скорость работы системы.")
    private ReplaceUrl replaceUrlInContent;
    @PropertyGroup(name = "Таймаут (мс)", description = "Время в миллисекундах, в течение которого ожидается ответ сервер. Если ответ не приходит, клиенту возвращается ошибка с HTTP-кодом 408. Значение -1 отключает таймаут" +
            "Данная опция используется для уменьшения нагрузки на бэкенд, который перегружен запросами пользователей и не может отвечать быстро.")
    private IntegerValue timeout;
    @PropertyGroup(name = "Количество потоков", description = "Количество потоков, обслуживающих обращения к этому бэкенду. Рекомендуемое значение - 10.", warning = "Вся обработка асинхронная, в связи с чем нет необходимости задавать большое значение данному параметру. Изменение значения параметра требует перезагрузки сервера")
    private IntegerValue poolSize;
    @PropertyGroup(
            name = "Максимальное количество одновременных запросов к бэкенду",
            description = "Задает максимальное количество одновременных запросов к бэкенду, которое может обрабатываться через пул соединений. "
                    + "При достижении этого лимита новые запросы будут отклоняться.",
            warning = "Изменение значения параметра требует перезагрузки сервера"
    )
    private IntegerValue queueSize;
    @PropertyGroup(name = "Размер очереди (динамический)", description = "Задает размер очереди, который может быть изменен динамически, без необходимости перезагрузки сервера. Значение не может превышать значение параметра \"Размер очереди\"")
    private IntegerValue queueSizeRejectionThreshold;
    @PropertyGroup(name = "Health URI", description = "Задает URI для проверки работоспособности бэкенда (если задан), на который будет периодически отправляться HEAD запрос")
    private HealthUri healthUri;
    @ApplicationProperties(name = "Параметры для URI", description = "Позволяет переопределить отдельные параметры для определенных URI")
    private List<URIOverride> uriOverride;
    @ApplicationProperties(name = "Кеширование URI", description = "Позволяет настроить кеширование на Портале для определенных URI")
    private List<URICache> uriCaches;
    @ApplicationProperties(name = "Общие cookies", description = "Задает имена cookies, которые будут пересылаться между всеми бэкендами, входящими в группу. Это может быть полезно, когда все бэкенды в группе имеют общую аутентификацию. Данная настройка" +
            " не имеет эффекта, если вклюеена опция хранения cookies на сервере")
    private List<CookieShare> cookieShares;
    @ApplicationProperties(name = "Хранить cookies на сервере", description = "Данная настройка включает хранение cookies бэкенда на сервере. Если настройка включена, то на клиента пересылается только небольшая часть cookies, остальные " +
            "хранятся на сервере и прозрачно отправляются в целевую подсистему с каждым запросом")
    private boolean storeCookiesOnServer = false;

    public DefaultUri getDefaultUri() {
        return defaultUri;
    }

    public void setDefaultUri(DefaultUri defaultUri) {
        this.defaultUri = defaultUri;
    }

    public LogoutUrl getLogoutUri() {
        return logoutUri;
    }

    public void setLogoutUri(LogoutUrl logoutUri) {
        this.logoutUri = logoutUri;
    }

    public ReplaceUrl getReplaceUrlInContent() {
        return replaceUrlInContent;
    }

    public void setReplaceUrlInContent(ReplaceUrl replaceUrlInContent) {
        this.replaceUrlInContent = replaceUrlInContent;
    }

    public IntegerValue getTimeout() {
        return timeout;
    }

    public void setTimeout(IntegerValue timeout) {
        this.timeout = timeout;
    }

    public IntegerValue getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(IntegerValue poolSize) {
        this.poolSize = poolSize;
    }

    public IntegerValue getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(IntegerValue queueSize) {
        this.queueSize = queueSize;
    }

    public IntegerValue getQueueSizeRejectionThreshold() {
        return queueSizeRejectionThreshold;
    }

    public void setQueueSizeRejectionThreshold(IntegerValue queueSizeRejectionThreshold) {
        this.queueSizeRejectionThreshold = queueSizeRejectionThreshold;
    }

    public HealthUri getHealthUri() {
        return healthUri;
    }

    public void setHealthUri(HealthUri healthUri) {
        this.healthUri = healthUri;
    }


    public List<URIOverride> getUriOverride() {
        return uriOverride;
    }

    public void setUriOverride(List<URIOverride> uriOverride) {
        this.uriOverride = uriOverride;
    }

    public List<URICache> getUriCaches() {
        return uriCaches;
    }

    public void setUriCaches(List<URICache> uriCaches) {
        this.uriCaches = uriCaches;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Host> getHosts() {
        return hosts;
    }

    public void setHosts(List<Host> hosts) {
        this.hosts = hosts;
    }

    public List<CookieShare> getCookieShares() {
        return cookieShares;
    }

    public void setCookieShares(List<CookieShare> cookieShares) {
        this.cookieShares = cookieShares;
    }

    public boolean isStoreCookiesOnServer() {
        return storeCookiesOnServer;
    }

    public void setStoreCookiesOnServer(boolean storeCookiesOnServer) {
        this.storeCookiesOnServer = storeCookiesOnServer;
    }

    @JsonIgnore
    public Stream<Host> mergedHosts() {
        return ofNullable(this.hosts).map(List::stream).orElse(Stream.empty()).map(this::overrideHost);
    }

    private Host overrideHost(Host backend) {
        if (override(getDefaultUri())) {
            backend.setDefaultUri(this.getDefaultUri().getDefaultUri());
        }
        if (override(this.getLogoutUri())) {
            backend.setLogoutUri(this.getLogoutUri().getLogoutUrl());
        }
        if (override(this.getReplaceUrlInContent())) {
            backend.setReplaceUrlInContent(this.getReplaceUrlInContent().isReplaceUrlInContent());
        }
        if (override(this.getTimeout())) {
            backend.setTimeout(this.getTimeout().getValue());
        }
        if (override(this.getPoolSize())) {

            backend.setPoolSize(this.getPoolSize().getValue());
        }
        if (override(this.getQueueSize())) {
            backend.setQueueSize(this.getQueueSize().getValue());
        }
        if (override(this.getHealthUri())) {
            backend.setHealthUri(this.getHealthUri().getHealthUri());
        }
        if (!CollectionUtils.isEmpty(this.getUriOverride())) {
            backend.setUriOverride(this.getUriOverride());
        }
        if (!CollectionUtils.isEmpty(this.getUriCaches())) {
            backend.setUriCaches(this.getUriCaches());
        }
        if (this.isStoreCookiesOnServer()) {
            backend.setStoreCookiesOnServer(true);
        }
        backend.setGroup(this);
        return backend;
    }

    private boolean override(Togglable togglable) {
        return togglable != null && togglable.isOverride();
    }

    public boolean isSharedCookie(String name) {
        return ofNullable(this.cookieShares).map(List::stream).orElseGet(() -> Stream.empty()).anyMatch(cs -> cs.getCookieName().equals(name));
    }

    public void addHost(Host host) {
        if (this.hosts == null) {
            this.hosts = new ArrayList<>();
        }
        this.hosts.add(host);
    }

    public interface Togglable {
        boolean isOverride();
    }

    public static class DefaultUri implements Togglable {
        @ApplicationProperties(name = "Страница по умолчанию", description = "Страница целевой системы, открываемая по умолчанию", example = "/index.html")
        private String defaultUri;
        @ApplicationProperties(name = "Переопределить", description = "Включает переопределение данного параметра")
        private boolean override;


        public String getDefaultUri() {
            return defaultUri;
        }

        public void setDefaultUri(String defaultUri) {
            this.defaultUri = defaultUri;
        }

        public boolean isOverride() {
            return override;
        }

        public void setOverride(boolean override) {
            this.override = override;
        }
    }

    public static class LogoutUrl implements Togglable {
        @ApplicationProperties(name = "Страница выхода", description = "Страница целевой системы, вызов которой будет осуществлен во время выхода из Портала", example = "/logout.html")
        private String logoutUrl;
        @ApplicationProperties(name = "Переопределить", description = "Включает переопределение данного параметра")
        private boolean override;

        public String getLogoutUrl() {
            return logoutUrl;
        }

        public void setLogoutUrl(String logoutUrl) {
            this.logoutUrl = logoutUrl;
        }

        @Override
        public boolean isOverride() {
            return override;
        }

        public void setOverride(boolean override) {
            this.override = override;
        }
    }

    public static class ReplaceUrl implements Togglable {
        @ApplicationProperties(name = "Перезапись ссылок контента", description = "Данная опция включает замену всех ссылок, в контенте, который возвращает бэкенд, на ссылки, ведущие через Портал.", warning = "Данная опция может серьезно снизить скорость работы системы.")
        private boolean replaceUrlInContent;
        @ApplicationProperties(name = "Переопределить", description = "Включает переопределение данного параметра")
        private boolean override;

        public boolean isReplaceUrlInContent() {
            return replaceUrlInContent;
        }

        public void setReplaceUrlInContent(boolean replaceUrlInContent) {
            this.replaceUrlInContent = replaceUrlInContent;
        }

        @Override
        public boolean isOverride() {
            return override;
        }

        public void setOverride(boolean override) {
            this.override = override;
        }
    }

    public static class IntegerValue implements Togglable {
        @ApplicationProperties(name = "Значение")
        private int value;
        @ApplicationProperties(name = "Переопределить", description = "Включает переопределение данного параметра")
        private boolean override;

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }

        @Override
        public boolean isOverride() {
            return override;
        }

        public void setOverride(boolean override) {
            this.override = override;
        }
    }

    public static class HealthUri implements Togglable {
        @ApplicationProperties(name = "Переопределить", description = "Включает переопределение данного параметра")
        private boolean override;
        @ApplicationProperties(name = "Health URI", description = "Задает URI для проверки работоспособности бэкенда (если задан), на который будет периодически отправляться HEAD запрос", example = "/status")
        private String healthUri;

        @Override
        public boolean isOverride() {
            return override;
        }

        public void setOverride(boolean override) {
            this.override = override;
        }

        public String getHealthUri() {
            return healthUri;
        }

        public void setHealthUri(String healthUri) {
            this.healthUri = healthUri;
        }
    }
}
