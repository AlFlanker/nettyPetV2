package com.otr.portal.hosts.filter;

import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.hosts.backends.BackendRegistry;
import com.otr.portal.metadata.reloadable.ConfigurationService;
import com.otr.portal.metadata.reloadable.ReloadableConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

import static com.otr.portal.core.mdc.RemoveKeyFromMDCHelper.removeKeyFromMDC;
import static com.otr.portal.proxy.RouteType.LOCAL;

/**
 * @author pobedenniy.alexey
 * @since 07.11.17
 */
public class BackendForRequestFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(BackendForRequestFilter.class);

    public static final String PROXY_ROUTE_TYPE_ATTRIBUTE_NAME = "com.otr.portal.proxy.RouteType";
    public static final String MDC_KEY = "backend";
    public static final String BACKEND_ATTRIBUTE_NAME = "com.otr.Backend";
    public static final String BACKEND_NAME_ATTRIBUTE_NAME = "com.otr.Backend.name";
    public static final String BACKEND_HEADER_NAME = "X-Backend";
    private final BackendRegistry backendRegistry;
    private final Supplier<Boolean> sendDiagnosticHeaders;
    private final PathHelper pathHelper;
    private ReloadableConfiguration<HostsConfiguration>.ReloadableConfigurationProperty<Boolean> saveBackendInSession;
    private final RouteClassifier classifier;

    public BackendForRequestFilter(
            BackendRegistry backendRegistry,
            Supplier<Boolean> sendDiagnosticHeaders,
            ConfigurationService configurationService,
            PathHelper pathHelper) {
        this.backendRegistry = backendRegistry;
        this.sendDiagnosticHeaders = sendDiagnosticHeaders;
        this.pathHelper = pathHelper;
        this.saveBackendInSession = configurationService.getConfigurationProperty(HostsConfiguration.class, HostsConfiguration::isSaveBackendInSession);
        this.classifier = new RouteClassifier(pathHelper);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        var routeType = this.classifier.resolve(request);
        request.setAttribute(PROXY_ROUTE_TYPE_ATTRIBUTE_NAME, routeType);
        if (!LOCAL.equals(routeType)) {
            resolveBackend(request, response);
        }
        try {
            filterChain.doFilter(resolveHttpRequest(request), response);
        } finally {
            removeKeyFromMDC(request, MDC_KEY);
        }
    }

    private void resolveBackend(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response) {
        final Backend backend = backendRegistry.lookupBackend(request);
        request.setAttribute(BACKEND_ATTRIBUTE_NAME, backend);
        if (backend != null) {
            if (saveBackendInSession.getValue()) {
                // EB-91497 сохраняем предыдущий бэкенд в аттрибутах сессии, на случай если заголовок "Referer" перезапишется
                request.getSession().setAttribute(BACKEND_ATTRIBUTE_NAME, backend);
            }
            log.debug("Для запроса на {} подобран бэкенд {}", request.getRequestURI(), backend.getName());
            request.setAttribute(BACKEND_NAME_ATTRIBUTE_NAME, backend.getName());
            if (sendDiagnosticHeaders.get()) {
                response.addHeader(BACKEND_HEADER_NAME, backend.getName());
            }
            MDC.put(MDC_KEY, backend.getName());
        } else {
            log.debug("Для запроса на {} не найден бэкенд", request.getRequestURI());
        }
    }

    private HttpServletRequest resolveHttpRequest(@NonNull HttpServletRequest request) {
        return request;
    }

}
