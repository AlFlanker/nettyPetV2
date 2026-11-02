package com.otr.portal.hosts.backends.metadata;

import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.metadata.ServerMetadataExtensionService;
import com.otr.portal.metadata.model.SelectMetadataField;
import jakarta.annotation.PostConstruct;

/**
 * @author pobedenniy.alexey
 * @since 13.12.17
 */
public abstract class BackendTypeMetadataExtension {
    public static final String BACKEND_TYPE_FIELD_NAME = "backendType";
    private ServerMetadataExtensionService metadataExtensionService;

    public BackendTypeMetadataExtension(ServerMetadataExtensionService metadataExtensionService) {
        this.metadataExtensionService = metadataExtensionService;
    }

    @PostConstruct
    public void registerExtension() {
        metadataExtensionService.registerExtensionForObject(
                HostsConfiguration.class,
                Host.class,
                new SelectMetadataField(BACKEND_TYPE_FIELD_NAME, getValue(), "Тип бэкенда", "Определяет тип бэкенда для загрузки навигации", "", "").addItem(getValue(), getName()));
    }

    protected abstract String getValue();

    protected abstract String getName();
}
