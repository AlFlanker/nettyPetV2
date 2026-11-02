package com.otr.portal.hosts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.otr.portal.metadata.config.ApplicationProperties;
import com.otr.portal.metadata.config.ConfigurationCheck;
import com.otr.portal.metadata.extensions.ExtensibleConfigurationField;
import com.otr.portal.metadata.extensions.roles.AccessRestrictedConfigurationObject;
import com.otr.portal.metadata.server.admin.ui.roles.RoleRef;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * @author pobedenniy.alexey
 * @since 21.09.17
 */
public class Host extends ExtensibleConfigurationField implements AccessRestrictedConfigurationObject {
    @ApplicationProperties(name = "Фиксированные URI", description = "Если приложение имеет набор фиксированных URI, то можно задать их этой настройкой. Тогда все обращения на эти URI будут маршрутизироваться на этот бэкенд", example = "/uri/**")
    List<String> applicationUris;
    @ApplicationProperties(
            name = "Cookies без обработки Портала",
            description = "Список имен cookies, которые будут передаваться в бэкенд из исходного запроса без переименования и специфичной обработки Портала",
            warning = "Используйте настройку аккуратно: при неверно указанном имени можно передать в бэкенд cookie, предназначенную для другого приложения."
    )
    private List<String> passthroughCookies;
    @ApplicationProperties(name = "Имя", description = "Имя бэкенда")
    private String name;
    @ApplicationProperties(name = "Описание", description = "Описание бэкенда")
    private String description;
    @ApplicationProperties(name = "Хост", description = "Хост, на котором происходит перенаправление трафика на бэкенд. Имя хоста должно быть алиасом сервера, на котором расположен Портал", example = "backend1.portal.domain.my")
    private String host;
    @ApplicationProperties(name = "URI", description = "Относительный путь, при обращении на который трафик будет перенаправлен на этот бэкенд. Используется, если включена настройка \"Различать бэкенды по uri\" ", example = "backend1")
    private String uri;
    @ApplicationProperties(name = "Целевая система", description = "Ссылка на целевую систему, отображаемую при входе пользователя на адрес, заданный параметром \"Хост\". Должен содержать полный URL, включая протокол и порт", example = "http://read-backend1.domain.my:8080/path")
    @ConfigurationCheck(checkName = "Проверить подключение", checkUrl = "/admin/backends/check")
    private String target;
    @ApplicationProperties(name = "Постоянный префикс для страниц целевой системы", description = "Может быть пустым. Если префикс задан, то он будет добавлен в адрес страницы по умолчанию и в URI, по которому загружается компонент бэкенда при переходе в навигации. Допустимо, чтобы префикс начинался с символа /. Т.е. может быть задан как \"sufdclient\" или \"/sufdclient\"", example = "sufdclient или /sufdclient")
    private String prefix;
    @ApplicationProperties(name = "Страница по умолчанию", description = "Страница целевой системы, открываемая по умолчанию", example = "/index.html")
    private String defaultUri;
    @ApplicationProperties(name = "Страница выхода", description = "Страница целевой системы, вызов которой будет осуществлен во время выхода из Портала", example = "/logout.html")
    private String logoutUri;
    @ApplicationProperties(name = "Перезапись ссылок контента", description = "Данная опция включает замену всех ссылок, в контенте, который возвращает бэкенд, на ссылки, ведущие через Портал.", warning = "Данная опция может серьезно снизить скорость работы системы.")
    private boolean replaceUrlInContent = false;
    @ApplicationProperties(defaultValue = "5000", name = "Таймаут (мс)", description = "Время в миллисекундах, в течение которого ожидается ответ сервер. Если ответ не приходит, клиенту возвращается ошибка с HTTP-кодом 408." +
            "Данная опция используется для уменьшения нагрузки на бэкенд, который перегружен запросами пользователей и не может отвечать быстро. При значении ≤ 0 таймаут устанавливается равным значению по умолчанию - 5000 мс")
    private Integer timeout = 5000;
    @ApplicationProperties(defaultValue = "10", name = "Количество потоков", description = "Количество потоков, обслуживающих обращения к этому бэкенду. Рекомендуемое значение - 10.", warning = "Вся обработка асинхронная, в связи с чем нет необходимости задавать большое значение данному параметру. Изменение значения параметра требует перезагрузки сервера")
    private Integer poolSize = 10;
    @ApplicationProperties(
            defaultValue = "100",
            name = "Максимальное количество одновременных запросов к бэкенду",
            description = "Задает максимальное количество одновременных запросов к бэкенду, которое может обрабатываться через пул соединений. "
                    + "При достижении этого лимита новые запросы будут отклоняться."
    )
    private Integer queueSize = 100;
    @ApplicationProperties(name = "Health URI", description = "Задает URI для проверки работоспособности бэкенда (если задан), на который будет периодически отправляться HEAD запрос", example = "/status")
    private String healthUri;
    @ApplicationProperties(name = "Параметры для URI", description = "Позволяет переопределить отдельные параметры для определенных URI")
    private List<URIOverride> uriOverride;
    @ApplicationProperties(name = "Кеширование URI", description = "Позволяет настроить кеширование на Портале для определенных URI")
    private List<URICache> uriCaches;

    @ApplicationProperties(name = "Включен", description = "Данная опция управляется модулем breaker", skipInUI = true)
    private boolean enabled = true;
    @ApplicationProperties(name = "Причина", description = "Причина отключения", skipInUI = true)
    private String disabledReason;
    @ApplicationProperties(name = "Хранить cookies на сервере", description = "Данная настройка включает хранение cookies бэкенда на сервере. Если настройка включена, то на клиента пересылается только небольшая часть cookies, остальные " +
            "хранятся на сервере и прозрачно отправляются в целевую подсистему с каждым запросом")
    private boolean storeCookiesOnServer = false;
    @ApplicationProperties(name = "Формат ссылки на открытие документа", description = "Задает именованный шаблон для составления ссылки. Допустимые имена переменных: " +
            "host - url текущего Портала; uri - наименование бекенда; backendPrefix - постоянный префикс для страниц целевой системы" +
            "mode - действие(EDIT, например); guid - идентификатор документа; documentType - тип документа. Переменная начинается с символа '&' или '$'." +
            "Если необходимо добавить символы после значения переменной, то необходимо между переменной и значением поставить символ '*'" +
            "Пример шаблона: \"&host/&uri/&backendPrefix*Example/&mode/GUID/&guid\"." +
            "После обработки: http://localhost:8080/pur/sufdExample/edit/GUID/4a3a0d54-350e-43c0-8ad8-38e8a1f567e4")
    private String documentUrlPattern;
    @ApplicationProperties(name = "Наименование контейнера СОБИ ФП", description = "Может использоваться для формирования типа защищаемого ресурса \"СОБИ\".")
    private String container;
    @ApplicationProperties(name = "Код приложения", description = "Используется как application_code для интеграций, которым требуется код приложения из настроек бэкенда.")
    private String applicationCode;
    @ApplicationProperties(name = "Заменять заголовок Origin", description = "Если опция включена, значение заголовка Origin будет заменено адресом целевой системы бэкенда")
    private boolean replaceOriginHeader = true;
    private Set<RoleRef> roles;
    @Nullable
    private volatile HostGroup group;

    public Host() {
    }

    public Host(String name, String host, String target) {
        this.name = name;
        this.host = host;
        this.target = target;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public boolean isReplaceUrlInContent() {
        return replaceUrlInContent;
    }

    public void setReplaceUrlInContent(boolean replaceUrlInContent) {
        this.replaceUrlInContent = replaceUrlInContent;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getDefaultUri() {
        return defaultUri;
    }

    public void setDefaultUri(String defaultUri) {
        this.defaultUri = defaultUri;
    }

    public String getLogoutUri() {
        return logoutUri;
    }

    public void setLogoutUri(String logoutUri) {
        this.logoutUri = logoutUri;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getHealthUri() {
        return healthUri;
    }

    public void setHealthUri(String healthUri) {
        this.healthUri = healthUri;
    }

    public List<String> getApplicationUris() {
        return applicationUris;
    }

    public void setApplicationUris(List<String> applicationUris) {
        this.applicationUris = applicationUris;
    }

    public List<String> getPassthroughCookies() {
        return passthroughCookies;
    }

    public void setPassthroughCookies(List<String> passthroughCookies) {
        this.passthroughCookies = passthroughCookies;
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

    public String getDisabledReason() {
        return disabledReason;
    }

    public void setDisabledReason(String disabledReason) {
        this.disabledReason = disabledReason;
    }

    public boolean isStoreCookiesOnServer() {
        return storeCookiesOnServer;
    }

    public String getDocumentUrlPattern() {
        return documentUrlPattern;
    }

    public void setDocumentUrlPattern(String documentUrlPattern) {
        this.documentUrlPattern = documentUrlPattern;
    }

    public void setStoreCookiesOnServer(boolean storeCookiesOnServer) {
        this.storeCookiesOnServer = storeCookiesOnServer;
    }

    public String getContainer() {
        return container;
    }

    public void setContainer(String container) {
        this.container = container;
    }

    public String getApplicationCode() {
        return applicationCode;
    }

    public void setApplicationCode(String applicationCode) {
        this.applicationCode = applicationCode;
    }

    public boolean isReplaceOriginHeader() {
        return replaceOriginHeader;
    }

    public void setReplaceOriginHeader(boolean replaceOriginHeader) {
        this.replaceOriginHeader = replaceOriginHeader;
    }

    @Override
    public Set<RoleRef> getRoles() {
        return roles;
    }

    public void setRoles(Set<RoleRef> selectedRoles) {
        this.roles = selectedRoles;
    }

    @JsonIgnore
    public HostGroup getGroup() {
        return group;
    }

    @JsonIgnore
    public void setGroup(HostGroup group) {
        this.group = group;
    }
}
