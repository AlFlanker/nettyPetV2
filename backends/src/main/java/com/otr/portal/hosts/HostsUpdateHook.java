package com.otr.portal.hosts;

import com.otr.portal.core.audit.events.AuditApplicationEvent;
import com.otr.portal.core.audit.events.AuditApplicationEventWithValue;
import com.otr.portal.metadata.config.ConfigurationUpdateHook;
import com.otr.portal.metadata.config.ExtendedUpdateHook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

import static com.otr.portal.core.audit.config.AuditMessageType.WARNING;
import static java.util.Optional.ofNullable;

/**
 * @author pobedenniy.alexey
 * @since 07.11.17
 */
@Component
public class HostsUpdateHook implements ConfigurationUpdateHook<HostsConfiguration> {
    private final ApplicationContext applicationContext;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final List<ExtendedUpdateHook<HostsConfiguration>> extendedUpdateHooks;

    public HostsUpdateHook(ApplicationEventPublisher applicationEventPublisher, ApplicationContext applicationContext, @Autowired(required = false) List<ExtendedUpdateHook<HostsConfiguration>> extendedUpdateHook) {
        this.applicationContext = applicationContext;
        this.applicationEventPublisher = applicationEventPublisher;
        this.extendedUpdateHooks = Optional.ofNullable(extendedUpdateHook).orElseGet(Collections::emptyList);
    }

    @Override
    public void afterConfigurationUpdate(HostsConfiguration oldConfigurationValue, HostsConfiguration configuration) {
        HostsService hostsService = applicationContext.getBean(HostsService.class);
        hostsService.reinitBackends(configuration);
        // Это событие может быть вызвано через событие etcd, и там нет HTTP‑запроса!
        if (hasHttpRequestContext()) {
            ofNullable(oldConfigurationValue.getHosts()).orElseGet(Collections::emptyList)
                    .forEach(oldValueHost ->
                            configuration.getHosts().stream()
                                    .filter(host -> Objects.equals(host.getName(), oldValueHost.getName()))
                                    .findAny()
                                    .ifPresent(host -> doAudit(oldValueHost, host))
                    );
        }

        extendedUpdateHooks.forEach(hook -> hook.afterConfigurationUpdate(oldConfigurationValue, configuration));

    }

    private boolean hasHttpRequestContext() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes;
    }

    private void doAudit(Host oldValue, Host newValue) {
        List<AuditApplicationEvent> events = new ArrayList<>();
        if (!(StringUtils.isEmpty(oldValue.getHost()) && StringUtils.isEmpty(newValue.getHost())) && !Objects.equals(oldValue.getHost(), newValue.getHost())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение хоста для бэкенда %s.",
                            newValue.getName()), oldValue.getHost(), newValue.getHost(), null));
        }

        if (!(StringUtils.isEmpty(oldValue.getUri()) && StringUtils.isEmpty(newValue.getUri())) && !Objects.equals(oldValue.getUri(), newValue.getUri())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение URI бэкенда %s.",
                            newValue.getName()), oldValue.getUri(), newValue.getUri(), null));
        }

        if (!(StringUtils.isEmpty(oldValue.getTarget()) && StringUtils.isEmpty(newValue.getTarget())) && !Objects.equals(oldValue.getTarget(), newValue.getTarget())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение ссылки целевой ссылки целевой системы бэкенда %s.",
                            newValue.getName()), oldValue.getTarget(), newValue.getTarget(), null));
        }

        if (!(StringUtils.isEmpty(oldValue.getPrefix()) && StringUtils.isEmpty(newValue.getPrefix())) && !Objects.equals(oldValue.getPrefix(), newValue.getPrefix())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение префикса бэкенда %s.",
                            newValue.getName()), oldValue.getPrefix(), newValue.getPrefix(), null));
        }

        if (!(StringUtils.isEmpty(oldValue.getDefaultUri()) && StringUtils.isEmpty(newValue.getDefaultUri())) && !Objects.equals(oldValue.getDefaultUri(), newValue.getDefaultUri())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение страницы по умолчанию для бэкенда %s.",
                            newValue.getName()), oldValue.getDefaultUri(), newValue.getDefaultUri(), null));
        }

        if (!(StringUtils.isEmpty(oldValue.getLogoutUri()) && StringUtils.isEmpty(newValue.getLogoutUri())) && !Objects.equals(oldValue.getLogoutUri(), newValue.getLogoutUri())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение страницы выхода для бэкенда %s.",
                            newValue.getName()), oldValue.getLogoutUri(), newValue.getLogoutUri(), null));
        }

        if (!Objects.equals(oldValue.getTimeout(), newValue.getTimeout())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение времени таймаута для бэкенда %s.",
                            newValue.getName()), oldValue.getTimeout(), newValue.getTimeout(), null));
        }

        if (!Objects.equals(oldValue.getPoolSize(), newValue.getPoolSize())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение количества потоков для бэкенда %s.",
                            newValue.getName()), oldValue.getPoolSize(), newValue.getPoolSize(), null));
        }

        if (!Objects.equals(oldValue.getQueueSize(), newValue.getQueueSize())) {
            events.add(new AuditApplicationEventWithValue(WARNING,
                    String.format("Произведено изменение размера пула для бэкенда %s.",
                            newValue.getName()), oldValue.getQueueSize(), newValue.getQueueSize(), null));
        }
        events.forEach(applicationEventPublisher::publishEvent);
    }
}
