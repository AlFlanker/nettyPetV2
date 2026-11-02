package example.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Настройки транспорта, проверяемые при создании Spring-контекста.
 * Ограничение параллелизма и размер порции задают предел прикладной буферизации;
 * таймауты задают сроки ожидания и инициируют отмену. Ресурсы освобождаются
 * после фактического завершения ввода-вывода, которое может произойти позже.
 * Значения по умолчанию действуют и при отсутствии соответствующих свойств в YAML.
 *
 * @param backendUrl адрес целевого сервера: схема, имя узла и необязательный порт
 * @param maxInFlight максимальное число одновременно выполняемых обменов
 * @param ioThreads число потоков обработки сетевых событий Netty
 * @param chunkSize размер порции тела запроса и предел порции чтения/декодирования ответа
 * @param connectTimeout предел TCP-подключения, TLS-рукопожатия и отдельного DNS-запроса
 * @param readTimeout ожидание следующего объекта ответа после отправки запроса
 *                    либо получения раннего окончательного ответа
 * @param exchangeTimeout срок всего обмена, включая чтение тела от клиента
 * @param shutdownTimeout интервал ожидания остановки I/O-задач и сетевых ресурсов
 */
@ConfigurationProperties("proxy")
public record ProxyProperties(
        @DefaultValue("http://127.0.0.1:9090") String backendUrl,
        @DefaultValue("32") int maxInFlight,
        @DefaultValue("2") int ioThreads,
        @DefaultValue("16384") int chunkSize,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("60s") Duration exchangeTimeout,
        @DefaultValue("5s") Duration shutdownTimeout) {

    public ProxyProperties {
        if (backendUrl == null) throw new IllegalArgumentException("proxy.backend-url is required");
        URI origin = URI.create(backendUrl);
        // Путь и query берутся из входящего запроса. Здесь разрешён только адрес
        // сервера, чтобы не смешивать два разных правила построения целевого URI.
        if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                || origin.getPort() == 0 || origin.getPort() > 65535
                || origin.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("proxy.backend-url must be an http(s) origin with a valid port, without path/query/userinfo");
        }
        // Ошибочную конфигурацию отклоняем до выделения потоков и открытия каналов.
        if (maxInFlight < 1 || ioThreads < 1 || chunkSize < 1 || chunkSize > 1024 * 1024) {
            throw new IllegalArgumentException("proxy limits must be positive; chunk-size must not exceed 1 MiB");
        }
        positive(connectTimeout, "connect-timeout");
        positive(readTimeout, "read-timeout");
        positive(exchangeTimeout, "exchange-timeout");
        positive(shutdownTimeout, "shutdown-timeout");
        if (connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("proxy.connect-timeout exceeds Netty's millisecond limit");
        }
    }

    private static void positive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero() || duration.toMillis() < 1) {
            throw new IllegalArgumentException("proxy." + name + " must be at least 1 ms");
        }
    }
}
