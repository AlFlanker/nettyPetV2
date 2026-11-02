package com.otr.portal.hosts.breaker;

import com.otr.portal.breaker.backends.InterruptibleBackend;
import com.otr.portal.breaker.data.InterruptibleResource;
import com.otr.portal.breaker.data.InterruptibleResourceGroup;
import com.otr.portal.breaker.data.ResourceBreaker;
import com.otr.portal.breaker.service.BreakerResourceTypeRegistry;
import com.otr.portal.core.audit.config.Param;
import com.otr.portal.core.audit.config.UsesAudit;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

import static com.otr.portal.core.audit.config.AuditMessageType.WARNING;
import static java.util.Optional.ofNullable;

/**
 * @author gusev.aleksandr
 * @since 05.02.2018
 */
@Component
public class BackendBreaker implements ResourceBreaker<InterruptibleBackend> {

    private static final String GROUP_NAME = "Бэкенды";

    private final HostsService hostsService;

    public BackendBreaker(HostsService hostsService, BreakerResourceTypeRegistry breakerResourceTypeRegistry) {
        this.hostsService = hostsService;

        breakerResourceTypeRegistry.add(request -> {
            Backend backend = (Backend) request.getAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME);
            return ofNullable(backend)
                    .map(back -> new InterruptibleBackend(back.name(), !back.isMaintenanceMode(), back.getDisabledReason()))
                    .orElse(null);
        });
    }

    @UsesAudit(messageType = WARNING, message = "Перевод бэкенда {resource} в режим обслуживания")
    @Override
    public void interrupt(@Param(name = "resource") InterruptibleBackend resource) {
        Backend backend = hostsService.getBackendByName(resource.name());
        if (backend != null) {
            hostsService.putBackendOnMaintenance(backend.getName(), resource.reason());
        }
    }

    @UsesAudit(messageType = WARNING, message = "Вывод бэкенда {resource} из режима обслуживания")
    @Override
    public void resume(@Param(name = "resource") InterruptibleBackend resource) {
        Backend backend = hostsService.getBackendByName(resource.name());
        if (backend != null) {
            hostsService.releaseBackendFromMaintenance(backend.getName());
        }
    }

    @Override
    public InterruptibleResourceGroup loadAll() {
        return new InterruptibleResourceGroup(GROUP_NAME, hostsService.getBackends().stream()
                .map(backend -> new InterruptibleBackend(backend.name(), !backend.isMaintenanceMode(), backend.getDisabledReason()))
                .collect(Collectors.toList()));
    }

    @Override
    public boolean isSupported(InterruptibleResource resource) {
        return InterruptibleBackend.class.isInstance(resource);
    }
}