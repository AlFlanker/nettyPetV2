package com.otr.tests.portal;

import com.otr.portal.backends.ssl.netty.SslContextHandlerFactory;
import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.core.support.ServerPortHolder;
import com.otr.portal.hosts.HostsConfiguration;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.backends.BackendRegistry;
import com.otr.portal.hosts.backends.SingleBackendRegistryImpl;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.metadata.ServerMetadataService;
import com.otr.portal.metadata.reloadable.ConfigurationService;
import com.otr.portal.metadata.reloadable.TestConfigurationService;
import com.otr.portal.proxy.cookies.FakeCookieProcessor;
import com.otr.portal.proxy.cookies.RewriteNameCookieInterceptor;
import com.otr.portal.proxy.headers.DefaultHeaderInterceptor;
import com.otr.portal.proxy.headers.DefaultHeaderProcessor;
import com.otr.portal.proxy.metrics.BackendsMetrics;
import com.otr.portal.proxy.metrics.MicrometerBackendsMetricsImpl;
import com.otr.portal.ssl.context.SslContextProvider;
import com.otr.portal.ssl.support.netty.NettySslContextAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.view.AbstractView;
import org.springframework.web.servlet.view.BeanNameViewResolver;

import java.util.Collections;
import java.util.Map;

/**
 * @author pobedenniy.alexey
 * @since 07.09.16
 */
@TestConfiguration
public class TestContext {

    @Bean
    public ViewResolver viewResolver() {
        return new BeanNameViewResolver();
    }

    @Bean
    public View notavailable() {
        return new AbstractView() {
            @Override
            protected void renderMergedOutputModel(Map<String, Object> model, HttpServletRequest request, HttpServletResponse response) throws Exception {
                response.getWriter().print(notavailableContent());
            }
        };
    }

    @Bean
    public SslContextProvider sslContextProvider() {
        return Mockito.mock(SslContextProvider.class);
    }

    @Bean
    public SslContextHandlerFactory nettyTlsFactory() {
        return new SslContextHandlerFactory(
                new NettySslContextAdapter(
                        sslContextProvider())
        );
    }

    @Bean
    public String notavailableContent() {
        return "@NOT_AVAILABLE";
    }

    @Bean
    HostsConfiguration hostsConfiguration() {
        return new HostsConfiguration();
    }

    @Bean
    DefaultHeaderProcessor defaultHeaderProcessor(RewriteNameCookieInterceptor rewriteNameCookieInterceptor, DefaultHeaderInterceptor  defaultHeaderInterceptor, FakeCookieProcessor fakeCookieProcessor) {
        return new DefaultHeaderProcessor(Collections.singletonList(defaultHeaderInterceptor), Collections.singletonList(rewriteNameCookieInterceptor), Collections.singletonList(fakeCookieProcessor));
    }

    @Bean
    public ServerPortHolder serverPortHolder() {
        return new TestServerPortHolder();
    }

    @Bean
    HostsService hostsService(HostsConfiguration hostsConfiguration, ServerPortHolder serverPortHolder) {
        ServerMetadataService serverMetadataService = Mockito.mock(ServerMetadataService.class);
        Mockito.when(serverMetadataService.loadConfiguration(HostsConfiguration.class)).thenReturn(hostsConfiguration);
        return new HostsService(serverPortHolder, serverMetadataService);
    }

    @Bean
    BackendRegistry backendRegistry(ServerPortHolder serverPortHolder, HostsService hostsService) {
        return new SingleBackendRegistryImpl("localhost", serverPortHolder);
    }

    @Bean
    MeterRegistry metricRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    BackendsMetrics backendsMetrics(MeterRegistry metricRegistry) {
        return new MicrometerBackendsMetricsImpl(metricRegistry);
    }

    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    @Bean
    RewriteNameCookieInterceptor rewriteNameCookieInterceptor() {
        return new RewriteNameCookieInterceptor();
    }

    @Bean
    DefaultHeaderInterceptor defaultHeaderInterceptor(HostsService hostsService) {
        return new DefaultHeaderInterceptor(hostsService);
    }

    @Bean
    FakeCookieProcessor fakeCookieProcessor() {
        return new FakeCookieProcessor();
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    ConfigurationService configurationService() {
        return new TestConfigurationService<>(new HostsConfiguration());
    }

    @Bean
    public FilterRegistrationBean backendForRequestFilter(BackendRegistry backendRegistry, ConfigurationService configurationService, PathHelper pathHelper) {
        final FilterRegistrationBean filterRegistrationBean = new FilterRegistrationBean(new BackendForRequestFilter(backendRegistry, () -> false, configurationService, pathHelper));
        filterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        filterRegistrationBean.setAsyncSupported(true);
        filterRegistrationBean.addUrlPatterns("/*");
        return filterRegistrationBean;
    }

    public static class TestServerPortHolder implements ServerPortHolder {
        private int port;

        @Override
        public int getServerPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
