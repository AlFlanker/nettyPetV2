package com.otr.portal.hosts.backends;

import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.metadata.ServerMetadataService;
import com.otr.portal.proxy.RouteType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.net.URI;

import static com.otr.portal.hosts.filter.BackendForRequestFilter.PROXY_ROUTE_TYPE_ATTRIBUTE_NAME;
import static com.otr.portal.proxy.RouteType.LEGACY_PROXY;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * @author pobedenniy.alexey
 * @since 21.09.17
 */
public class HostBasedBackendRegistryImpl implements BackendRegistry {
    private final boolean useUris;
    private final HostsService hostsService;
    private final String resourcePrefix;

    public HostBasedBackendRegistryImpl(HostsService hostsService,
                                        ServerMetadataService serverMetadataService,
                                        PathHelper pathHelper
    ) {
        this.hostsService = hostsService;
        this.resourcePrefix = pathHelper.getResourcePrefix();
        HostsConfiguration hostsConfiguration = serverMetadataService.loadConfiguration(HostsConfiguration.class);
        useUris = hostsConfiguration.isUseUris();
    }

    @Override
    public Backend lookupBackend(HttpServletRequest req) {
        if (!useUris) {
            return lookupBackendByHost(req);
        }
        Backend backend = lookupBackendByFirstSegment(req);
        if (backend != null) {
            return backend;
        }
        backend = lookupBackendBySegmentAfterPrefix(req);
        if (backend != null) {
            return backend;
        }
        backend = lookupBackendByReferer(req);
        if (backend != null) {
            return backend;
        }
        return lookupBackendByStaticUri(req);
    }

    private Backend lookupBackendByHost(HttpServletRequest req) {
        return hostsService.getBackendForHost(req.getServerName());
    }

    private Backend lookupBackendByFirstSegment(HttpServletRequest req) {
        return withRouteType(req, hostsService.getBackendByURI(firstSegment(req)), LEGACY_PROXY);
    }

    private Backend lookupBackendBySegmentAfterPrefix(HttpServletRequest req) {
        return withRouteType(req, hostsService.getBackendByURI(segmentAfterPrefix(req)), RouteType.PREFIXED_PROXY);
    }

    private Backend lookupBackendByReferer(HttpServletRequest req) {
        return withRouteType(req, backendFromReferer(req), LEGACY_PROXY);
    }

    private Backend lookupBackendByStaticUri(HttpServletRequest req) {
        return withRouteType(req, hostsService.getBackendByStaticURIs(req.getRequestURI()), LEGACY_PROXY);
    }

    private Backend withRouteType(HttpServletRequest req, Backend backend, RouteType routeType) {
        if (backend != null) {
            req.setAttribute(PROXY_ROUTE_TYPE_ATTRIBUTE_NAME, routeType);
        }
        return backend;
    }

    private String firstSegment(HttpServletRequest req) {
        return ofNullable(substringBetween(req.getServletPath(), "/", "/"))
                .orElseGet(() -> removeStart(req.getServletPath(), "/"));
    }

    private String segmentAfterPrefix(HttpServletRequest req) {
        String requestURI = req.getRequestURI();
        return segmentAfterPrefixStr(requestURI);
    }

    private String segmentAfterPrefixStr(String requestURI) {
        return requestURI.startsWith(this.resourcePrefix)
                ? substringBetween(
                    substringAfter(requestURI, this.resourcePrefix),
                "/")
                : "";
    }

    private Backend backendFromReferer(HttpServletRequest req) {
        String referer = req.getHeader(HttpHeaders.REFERER);
        if (!StringUtils.hasLength(referer)) {
            return null;
        }
        URI uri = URI.create(referer);
        referer = substringBetween(uri.getRawPath(), "/", "/");
        Backend backend = hostsService.getBackendByURI(referer);
        // для случая когда из iframe приходил еще и resourcePrefix(ПСБ)
        if (backend == null
                && uri.getRawPath().startsWith(this.resourcePrefix)) {
            var candidate = segmentAfterPrefixStr(uri.getRawPath());
            backend = hostsService.getBackendByURI(candidate);
        }
        return backend;
    }
}
