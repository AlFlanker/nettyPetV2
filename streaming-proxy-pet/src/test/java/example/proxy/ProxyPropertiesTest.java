package example.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет связывание и валидацию настроек до создания сетевых ресурсов транспорта. */
class ProxyPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test void invalidConfigurationFailsBeforeTransportResourcesAreCreated() {
        for (String setting : List.of("proxy.max-in-flight=0", "proxy.chunk-size=0", "proxy.io-threads=0",
                "proxy.exchange-timeout=0s", "proxy.connect-timeout=-1s", "proxy.read-timeout=0s",
                "proxy.backend-url=ftp://localhost", "proxy.backend-url=http://localhost:65536",
                "proxy.backend-url=http://user:password@localhost", "proxy.backend-url=http://localhost/path")) {
            runner.withPropertyValues(setting).run(context -> assertThat(context).as(setting).hasFailed());
        }
    }

    @Test void defaultsAndOverridesBindWithoutStartingTheHttpServer() {
        runner.withPropertyValues("proxy.backend-url=http://127.0.0.1:9099", "proxy.max-in-flight=3",
                "proxy.exchange-timeout=700ms").run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ProxyProperties.class);
                    assertThat(properties.maxInFlight()).isEqualTo(3);
                    assertThat(properties.exchangeTimeout()).isEqualTo(java.time.Duration.ofMillis(700));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProxyProperties.class)
    static class PropertiesOnly { }
}
