package com.otr.portal.hosts;

import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.core.support.ServerPortHolder;
import com.otr.portal.hosts.backends.BackendRegistry;
import com.otr.portal.hosts.backends.HostBasedBackendRegistryImpl;
import com.otr.portal.metadata.ServerMetadataService;
import com.otr.portal.proxy.metrics.BackendsMetrics;
import com.otr.portal.proxy.metrics.MicrometerBackendsMetricsImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author pobedenniy.alexey
 * @since 18.12.18
 */
@Configuration
public class HostsSpringConfiguration {
    @Bean
    public HostsService hostsService(ServerMetadataService serverMetadataService, ServerPortHolder serverPortHolder) {
        return new HostsService(serverPortHolder, serverMetadataService);
    }
    @Bean
    public BackendsMetrics backendsMetrics(MeterRegistry metricRegistry) {
        return new MicrometerBackendsMetricsImpl(metricRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public BackendRegistry backendRegistry(HostsService hostsService,
                                           ServerMetadataService serverMetadataService,
                                           PathHelper pathHelper
    ) {
        return new HostBasedBackendRegistryImpl(hostsService, serverMetadataService, pathHelper);
    }
}
