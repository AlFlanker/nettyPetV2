package com.otr.portal.hosts;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.metadata.config.ExtendedUpdateHook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;

import static com.otr.portal.proxy.ProxyCommand.COMMAND_NAME_PREFIX;
import static java.util.Optional.ofNullable;

/**
 * Компонент для "горячего" обновления специфичных для Resilience4J настроек бэкэндов
 *
 * @author alexraspopin
 * @since 20.04.2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendResilienceRefresher implements ExtendedUpdateHook<HostsConfiguration> {
    private final CommandConfigRegistry registry;

    @Override
    public void afterConfigurationUpdate(HostsConfiguration oldConfigurationValue, HostsConfiguration configuration) {
        ofNullable(oldConfigurationValue.getHosts())
                .orElseGet(Collections::emptyList)
                .forEach(oldValueHost ->
                        configuration.getHosts().stream()
                                .filter(host -> Objects.equals(host.getName(), oldValueHost.getName()))
                                .findAny()
                                .ifPresent(host -> doRefresh(oldValueHost, host))
                );
        // Группы
        ofNullable(oldConfigurationValue.getHostGroups())
                .orElseGet(Collections::emptyList)
                .forEach(hostGroup -> ofNullable(hostGroup.getHosts())
                        .orElseGet(Collections::emptyList)
                        .forEach(oldValueHost ->
                                ofNullable(configuration.getHostGroups())
                                        .orElseGet(Collections::emptyList)
                                        .stream()
                                        .filter(hg -> Objects.equals(hg.getName(), hostGroup.getName()))
                                        .findFirst()
                                        .flatMap(newHost -> ofNullable(newHost.getHosts())
                                                .orElseGet(Collections::emptyList)
                                                .stream()
                                                .filter(host -> Objects.equals(host.getName(), oldValueHost.getName()))
                                                .findFirst())
                                        .ifPresent(nh -> doRefresh(oldValueHost, nh))
                        )
                );
    }

    private void doRefresh(Host oldValue, Host newValue) {
        if (!Objects.equals(oldValue.getTimeout(), newValue.getTimeout())
            || !Objects.equals(oldValue.getQueueSize(), newValue.getQueueSize())) {
            log.warn("Произведено изменение специфичных для Resilience4J настроек бэкенда \"{}\"." +
                            "\noldTimeout: {}, newTimeout: {}, OldConcurrency: {}, newConcurrency: {}",
                    newValue.getName(),
                    oldValue.getTimeout(),
                    newValue.getTimeout(),
                    oldValue.getQueueSize(),
                    newValue.getQueueSize());
            registry.clearCache(COMMAND_NAME_PREFIX + newValue.getName());
        }
    }
}
