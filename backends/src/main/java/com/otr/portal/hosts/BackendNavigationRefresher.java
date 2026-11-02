package com.otr.portal.hosts;

import com.otr.portal.metadata.config.ExtendedUpdateHook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * Компонент "горячего" обновления soap-клиента для получения навигации бэкэндов
 *
 * @author alexraspopin
 * @since 29.04.2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackendNavigationRefresher implements ExtendedUpdateHook<HostsConfiguration> {
    public static final String NAVIGATION_URL_FIELD_NAME = "nvaigationServiceUrl";
    private final ApplicationEventPublisher eventPublisher;

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
        String oldNavigationUrl = oldValue.getExtFieldValue(NAVIGATION_URL_FIELD_NAME);
        String newNavigationUrl = newValue.getExtFieldValue(NAVIGATION_URL_FIELD_NAME);

        if (!Objects.equals(oldNavigationUrl, newNavigationUrl)) {
            log.warn("Произведено изменение \"URL веб-сервиса навигации\" бэкенда \"{}\". " +
                            "было: {}, стало: {}",
                    newValue.getName(),
                    oldNavigationUrl,
                    newNavigationUrl
            );
            eventPublisher.publishEvent(new BackendNavigationConfigChangedEvent(this, oldValue.getName()));
        }
    }
}
