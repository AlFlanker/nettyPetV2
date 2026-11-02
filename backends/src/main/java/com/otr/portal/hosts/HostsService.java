package com.otr.portal.hosts;

import com.otr.portal.core.support.ServerPortHolder;
import com.otr.portal.metadata.ServerMetadataService;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Optional.of;

/**
 * @author pobedenniy.alexey
 * @since 22.09.17
 */
public class HostsService {
    private static final Logger log = LoggerFactory.getLogger(HostsService.class);
    private HostsConfiguration hostsConfiguration;

    private ServerPortHolder serverPortHolder;
    private ServerMetadataService serverMetadataService;

    private Map<String, Backend> backendsByHost;
    private Map<String, Backend> backendsByName;
    private Map<String, Backend> backendsByURI;

    private String portPart;

    private SelfEntry selfEntryPoint;
    private String defaultHost;

    private Set<String> backendOnMaintenance;

    private final AntPathMatcher antMatcher = new AntPathMatcher();

    public HostsService(ServerPortHolder serverPortHolder, ServerMetadataService serverMetadataService) {
        this.serverPortHolder = serverPortHolder;
        this.serverMetadataService = serverMetadataService;

        this.backendsByHost = new HashMap<>();
    }

    @NotNull
    public URLGenerator getUrlGenerator() {
        if (hostsConfiguration.isUseUris()) {
            return new URIGenerator();
        }
        return new DomainNameGenerator();
    }

    public void putBackendOnMaintenance(String backendName, String reason) {
        toggleBackend(backendName, false, reason);
    }

    public void releaseBackendFromMaintenance(String backendName) {
        toggleBackend(backendName, true, null);
    }

    private void toggleBackend(String backendName, boolean enabled, String reason) {
        HostsConfiguration hosts = serverMetadataService.loadConfiguration(HostsConfiguration.class);
        hosts.allHosts()
                .filter(backend -> backend.getName().equals(backendName))
                .findFirst()
                .ifPresent(backend -> {
                    if (backend.isEnabled() != enabled) {
                        backend.setEnabled(enabled);
                        backend.setDisabledReason(reason);
                        serverMetadataService.saveConfiguration(hosts);
                    }
                });
    }

    public boolean isBackendOnMaintenance(String backendName) {
        return backendOnMaintenance.contains(backendName);
    }

    @Nullable
    public Backend getBackendForHost(String host) {
        host = StringUtils.removeEnd(host, portPart);
        log.trace("Ищем бэкенд сервер для хоста {}", host);
        final Backend backend = backendsByHost.get(host);
        if (backend != null) {
            return backend;
        }
        log.trace("Не найден бэкенд для хоста {}", host);
        return null;
    }

    @SuppressWarnings("unused")
    @Nullable
    public Backend getBackendByName(String backendName) {
        if (backendName == null) {
            return null;
        }
        return Optional.ofNullable(backendsByName).orElse(Collections.emptyMap()).getOrDefault(backendName.toUpperCase(), null);
    }

    @Nullable
    public Backend getBackendByURI(String uri) {
        if (uri == null) {
            return null;
        }
        return backendsByURI.get(uri.toUpperCase());
    }

    @Nullable
    public Backend getBackendByStaticURIs(String requestURI) {
        return backendsByName
                .values()
                .stream()
                .filter(
                        backend -> backend
                                .getApplicationUris()
                                .stream()
                                .anyMatch(uri -> antMatcher.match(uri, requestURI))
                )
                .findFirst()
                .orElse(null);
    }


    @SuppressWarnings("unused")
    @Nullable
    public String frontendUrlFor(String backendName) {
        return frontendUrlFor(backendName, null);
    }

    @Nullable
    public String frontendUrlFor(String backendName, String uri) {
        log.debug("Ищем бэкенд сервер по коду {}", backendName);
        Backend backend = backendsByName.get(backendName.toUpperCase());
        if (backend != null) {
            return backend.fullFrontendUrl(uri == null ? backend.getDefaultUri() : uri);
        } else {
            log.debug("Не найден бэкенд по коду {}", backendName);
            return null;
        }
    }

    @NotNull
    public String frontendUrlFor(String backendName, String uri, String... params) {
        log.debug("Ищем бэкенд сервер по коду {}", backendName);
        String fullUrl = frontendUrlFor(backendName, uri);
        if (StringUtils.contains(fullUrl, "?")) {
            fullUrl += Arrays.stream(params).reduce("", (x, y) -> x += "&" + y);
        } else {
            fullUrl += "?" + Arrays.stream(params).collect(Collectors.joining("&"));
        }
        return fullUrl;
    }

    @NotNull
    public Collection<Backend> getBackends() {
        return backendsByName == null ? Collections.emptySet() : backendsByName.values();
    }

    @PostConstruct
    public void init() {
        this.defaultHost = getDefaultHost();
        this.hostsConfiguration = serverMetadataService.loadConfiguration(HostsConfiguration.class);
        this.portPart = ":" + serverPortHolder.getServerPort();
        reinitBackends(hostsConfiguration);
    }

    void reinitBackends(HostsConfiguration hostsConfiguration) {
        final List<Host> hosts = hostsConfiguration.mergedHosts();
        if (hosts != null) {
            backendsByHost = new HashMap<>(hosts.stream()
                    .filter(host -> host.getHost() != null)
                    .collect(Collectors.toMap(
                            Host::getHost,
                            host -> Backend.fromHost(host, serverPortHolder.getServerPort(), this),
                            (host1, host2) -> host1
                    ))
            );
            backendsByName = new HashMap<>(hosts.stream()
                    .collect(Collectors.toMap(
                            host -> host.getName().toUpperCase(),
                            host -> Backend.fromHost(host, serverPortHolder.getServerPort(), this),
                            (host1, host2) -> host1
                    ))
            );
            backendsByURI = new HashMap<>(hosts
                    .stream()
                    .filter(host -> StringUtils.isNotBlank(host.getUri()))
                    .collect(Collectors.toMap(
                            host -> host.getUri().toUpperCase(),
                            host -> Backend.fromHost(host, serverPortHolder.getServerPort(), this),
                            (host1, host2) -> host1
                    ))
            );
            backendOnMaintenance = new HashSet<>(hosts.stream().filter(backend -> !backend.isEnabled()).map(Host::getName).collect(Collectors.toSet()));
        } else {
            backendsByHost = Collections.emptyMap();
            backendsByName = Collections.emptyMap();
            backendsByURI = Collections.emptyMap();
        }
        this.selfEntryPoint = Optional
                .ofNullable(hostsConfiguration.getEntryPoint())
                .map(x -> SelfEntry.from(x, serverPortHolder.getServerPort(), hostsConfiguration.isLocalEntryPoint()))
                .orElse(SelfEntry.from(defaultHost, serverPortHolder.getServerPort(), hostsConfiguration.isLocalEntryPoint()));
    }

    private String getDefaultHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    public Optional<String> frontendUrlFromBackendUrl(Backend backend, String target) {
        if (backend.isUseUris()) {
            if (target.startsWith("http")) {
                String path = UriComponentsBuilder.fromUriString(target).build().getPath();
                return of("/" + backend.getUri() + path + StringUtils.substringAfter(target, path));
            } else {
                return of("/" + backend.getUri() + target);
            }
        } else {

            final String backendUrl = backend.fullBackendUrl("/");
            if (target.startsWith(backendUrl)) {
                return of(backend.fullFrontendUrl(StringUtils.removeStart(target, backendUrl)));
            } else {
                return of(backend.fullFrontendUrl(target));
            }
        }
    }

    public SelfEntry selfEntryPoint() {
        return selfEntryPoint;
    }

    public boolean isUserUris() {
        return hostsConfiguration.isUseUris();
    }

    public boolean isUseIsolateNavigationThreadPool() {
        return hostsConfiguration.isUseIsolateNavigationThreadPool();
    }

}
