package com.otr.portal.backends.ssl;

import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.metadata.ServerMetadataExtensionService;
import com.otr.portal.metadata.model.SelectMetadataField;
import com.otr.portal.ssl.context.SslContextProperties;
import com.otr.portal.ssl.context.SslContextProvider;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.otr.portal.ssl.context.SslContextType.BACKEND;
import static java.util.stream.Collectors.toList;
import static org.springframework.util.CollectionUtils.isEmpty;
import static org.springframework.util.StringUtils.hasText;

/**
 * Регистратор метаданных конфигурации SSL/mTLS для Backend.
 * Добавляет поле выбора конфигурации SSL в метаданные Backend через ServerMetadataExtensionService.
 *
 * @author alexraspopin
 * @since 31.10.2025
 */
@Data
@Slf4j
@Component
public class BackendSslContextMetadataExtension {
    public static final String BACKEND_SSL_CONTEXT = "SSL_CONTEXT";

    private final ServerMetadataExtensionService metadataExtensionService;
    private final SslContextProvider sslContextProvider;

    @PostConstruct
    void setUp() {
        var backendSslContexts = this.sslContextProvider.getConfigs()
                .entrySet()
                .stream()
                .filter(BackendSslContextMetadataExtension::isBackendType)
                .map(Map.Entry::getValue)
                .collect(toList());
        if (!isEmpty(backendSslContexts)) {
            log.warn("Конфигурация mTLS контекста для бэкэндов загружена: {}", backendSslContexts);
            enrichBackendPropertiesWithSslContexts();
        }
    }

    private void enrichBackendPropertiesWithSslContexts() {
        String defaultValue = "Не использовать SSL(mTLS)";
        final var selectMetadataField = new SelectMetadataField(
                BACKEND_SSL_CONTEXT,
                defaultValue,
                "mTLS контекст",
                "Определяет SSL контекст для запросов к бэкэндам. Для добавление новой конфигурации обратить к администратору",
                "",
                "Изменение значения параметра требует перезагрузки сервера!!!"
        );
        this.metadataExtensionService.registerExtensionForObject(
                HostsConfiguration.class,
                Host.class,
                selectMetadataField
        );

        selectMetadataField.addItem("", defaultValue);
        this.sslContextProvider.getConfigs().entrySet()
                .stream()
                .filter(BackendSslContextMetadataExtension::isBackendType)
                .forEach(entry ->
                        selectMetadataField.addItem(entry.getKey(),
                                getValue(entry.getValue()))
                );
    }

    private static boolean isBackendType(java.util.Map.Entry<String, SslContextProperties> entry) {
        return BACKEND == entry.getValue().getSslContextType();
    }

    private static String getValue(SslContextProperties sslContext) {
        return hasText(sslContext.getDisplayName())
                ? sslContext.getDisplayName()
                : sslContext.getTechnicalName();
    }
}
