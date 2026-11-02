package com.otr.portal.hosts;

import com.otr.portal.metadata.server.admin.ui.roles.RoleRef;
import com.otr.portal.proxy.cookies.Cookie;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.removeEnd;
import static org.apache.commons.lang3.StringUtils.removeStart;
import static org.springframework.util.CollectionUtils.isEmpty;

/**
 * @author pobedenniy.alexey
 * @since 21.09.17
 */
public class Backend {
    public static final int DEFAULT_TIMEOUT = 5000;
    public static final int DEFAULT_POOL_SIZE = 2;
    public static final int DEFAULT_QUEUE_SIZE = 100;
    private final String backendUrl;
    private final String backendServerHost;
    private final List<String> applicationUris;
    private final List<String> passthroughCookies;
    private Host host;
    private int localPort;
    @Nullable
    private HostsService hostsService;
    private Set<RoleRef> roles;
    private URLGenerator urlGenerator;

    public Backend(Host host, int localPort, @Nullable HostsService hostsService) {
        this.host = host;
        this.localPort = localPort;
        this.hostsService = hostsService;
        this.backendUrl = StringUtils.removeEnd(host.getTarget(), "/");
        this.backendServerHost = StringUtils.substringBefore(StringUtils.removeStart(this.backendUrl, "http://"), "/");
        this.applicationUris = ofNullable(host.getApplicationUris())
                .orElse(emptyList());
        this.passthroughCookies = ofNullable(host.getPassthroughCookies())
                .orElse(emptyList())
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .collect(toList());
        if (host.getRoles() == null) {
            this.roles = Collections.emptySet();
        } else {
            this.roles = Collections.unmodifiableSet(new TreeSet<>(host.getRoles()));
        }
    }

    public static Backend fromHost(Host host, int localPort, HostsService hostsService) {
        return new Backend(host, localPort, hostsService);
    }

    public String fullBackendUrl(String uri) {
        return appendUri(backendUrl, uri);
    }

    public String fullPrefixedBackendUrl(String uri) {
        return appendUri(backendUrl, appendPrefixTo(uri));
    }

    private String appendUri(String baseUrl, String uri) {
        if (StringUtils.isNotEmpty(uri)) {
            return baseUrl + (StringUtils.startsWith(uri, "/") ? uri : "/" + uri);
        } else {
            return baseUrl;
        }
    }

    public String fullFrontendUrl(String uri) {
        return appendUri(getOrInitFrontendUrl(), uri);
    }

    public String relativeFrontendUrl(String uri) {
        return appendUri(urlGenerator().generateRelUrl(host.getUri()), uri);
    }

    private String getOrInitFrontendUrl() {
        return urlGenerator().generateUrl(host.getHost(), host.getUri(), localPort);
    }

    private URLGenerator urlGenerator() {
        if (this.urlGenerator == null) {
            if (hostsService != null) {
                this.urlGenerator = hostsService.getUrlGenerator();
            } else {
                this.urlGenerator = new DomainNameGenerator();
            }
        }
        return this.urlGenerator;
    }

    public String cutBackendUri(String requestUri) {
        String uri = requestUri;
        if (this.isUseUris()) {
            String normalizedBackendUri = removeStart(removeEnd(this.getUri(), "/"), "/");
            String afterBackendNameUri = removeStart(uri, "/" + normalizedBackendUri + "/");
            if (StringUtils.isNotBlank(afterBackendNameUri) && !StringUtils.startsWith(afterBackendNameUri, "/")) {
                uri = "/" + afterBackendNameUri;
            }
            //если есть постоянный префикс для запросов к этому бэкенду и он совпадает с URI - то его не убираем
            //если постоянный префикс и uri равны, то для случая /uri/uri/target мы сформируем верную ссылку
            //а для случая /uri/target мы сформируем неверную (т.е. только /target)
            if (samePrefixAndUri(this.getPrefix(), this.getUri())) {
                String pfx = this.getPrefix();
                pfx = StringUtils.startsWith(pfx, "/") ? pfx : '/' + pfx;
                pfx = StringUtils.endsWith(pfx, "/") ? pfx : pfx + '/';
                if (!uri.startsWith(pfx)) {
                    uri = requestUri;
                }
            }
        }
        return uri;
    }

    private boolean samePrefixAndUri(String prefix, String uri) {
        String normalizedPrefix = removeStart(removeEnd(prefix, "/"), "/");
        String normalizeURI = removeStart(removeEnd(uri, "/"), "/");
        return normalizeURI.equals(normalizedPrefix);
    }

    public String backendHost() {
        return backendServerHost;
    }

    public String getDescription() {
        return host.getDescription();
    }

    public String getDefaultUri() {
        return appendPrefixTo(host.getDefaultUri());
    }

    public String appendPrefixTo(String uri) {
        return appendUri(appendUri("", host.getPrefix()), uri);
    }

    public String frontendToBackendUrl(String frontendUrl) {
        if (hostsService != null) {

            SelfEntry selfHost = hostsService.selfEntryPoint();
            final String selfHostString = selfHost.toString();
            if (StringUtils.contains(frontendUrl, selfHostString)) {
                return StringUtils.replaceOnce(frontendUrl, selfHostString, backendUrl);
            }
        }
        return StringUtils.replaceOnce(frontendUrl, getOrInitFrontendUrl(), backendUrl);
    }

    public boolean isReplaceUrlInContent() {
        return host.isReplaceUrlInContent();
    }

    public List<String> getApplicationUris() {
        return applicationUris;
    }

    public List<String> getPassthroughCookies() {
        return passthroughCookies;
    }

    public boolean hasNoPassthroughCookies() {
        return isEmpty(passthroughCookies);
    }

    public boolean isPassthroughCookie(Cookie cookie) {
        return !isEmpty(passthroughCookies)
                && passthroughCookies.contains(cookie.name());
    }

    public String name() {
        return host.getName();
    }

    public String getName() {
        return host.getName();
    }

    public int getTimeout() {
        return host.getTimeout() <= 0 ? DEFAULT_TIMEOUT : host.getTimeout();
    }

    public int getPoolSize() {
        return host.getPoolSize() <= 0 ? DEFAULT_POOL_SIZE : host.getPoolSize();
    }

    public int getQueueSize() {
        return host.getQueueSize() <= 0 ? DEFAULT_QUEUE_SIZE : host.getQueueSize();
    }

    public boolean isMaintenanceMode() {
        return hostsService != null && hostsService.isBackendOnMaintenance(getName());
    }

    /**
     * @return Наименование контейнера СОБИ ФП
     */
    public String getContainer() {
        return this.host.getContainer();
    }

    /**
     * @return Код приложения для внешних авторизационных сервисов
     */
    public String getApplicationCode() {
        return this.host.getApplicationCode();
    }

    public boolean isReplaceOriginHeader() {
        return this.host.isReplaceOriginHeader();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Backend backend = (Backend) o;

        if (!getOrInitFrontendUrl().equals(backend.getOrInitFrontendUrl())) {
            return false;
        }
        return backendUrl.equals(backend.backendUrl) && host.getName().equals(backend.getName());
    }

    @Override
    public int hashCode() {
        int result = getOrInitFrontendUrl().hashCode();
        result = 31 * result + backendUrl.hashCode();
        result = 31 * result + host.getName().hashCode();
        return result;
    }

    public String getUri() {
        return host.getUri();
    }

    public boolean isUseUris() {
        return ofNullable(hostsService).map(HostsService::isUserUris).orElse(false);
    }
    public boolean isUseIsolateNavigationThreadPool() {
        return ofNullable(hostsService).map(HostsService::isUseIsolateNavigationThreadPool).orElse(false);
    }

    public boolean hasHealthUri() {
        return StringUtils.isNotBlank(host.getHealthUri());
    }

    public String getHealthUri() {
        return host.getHealthUri();
    }

    public String getLogoutUri() {
        return host.getLogoutUri();
    }

    public <T> T getExtFieldValue(String extFieldName) {
        return host.getExtFieldValue(extFieldName);
    }

    public String getDisabledReason() {
        return host.getDisabledReason();
    }

    public Optional<URIOverride> getUriOverride(String uri) {
        return ofNullable(host.getUriOverride())
                .orElse(emptyList())
                .stream()
                .filter(o -> o.matches(uri))
                .findFirst();
    }

    public String getPrefix() {
        return host.getPrefix();
    }

    public boolean shouldCache(String requestURI) {
        return ofNullable(host.getUriCaches())
                .orElse(emptyList())
                .stream()
                .anyMatch(x -> x.matches(requestURI));
    }
    @Nullable
    public Integer getMaxAge(String requestURI) {
        Optional<URICache> uriCache = ofNullable(host.getUriCaches())
                .orElse(emptyList())
                .stream()
                .filter(x -> x.matches(requestURI))
                .findFirst();
        return uriCache.map(URICache::getMaxAge).orElse(null);
    }

    public Set<RoleRef> getRoles() {
        return roles;
    }

    public boolean isSharedCookie(String name) {
        return host.getGroup() != null && host.getGroup().isSharedCookie(name);
    }

    public boolean isStoreCookiesOnServer() {
        return host.isStoreCookiesOnServer();
    }

    public String getCustomDocumentPattern() {
        return this.host.getDocumentUrlPattern();
    }

    public String getBackendUrl() {
        return this.backendUrl;
    }
}
