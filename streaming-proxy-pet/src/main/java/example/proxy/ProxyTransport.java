package example.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Общие сетевые ресурсы прокси: потоки Netty, разрешение DNS-имён, каналы
 * и исполнитель блокирующих операций Servlet API.
 * <p>
 * Каждый обмен получает отдельное соединение с бэкендом. Соединения не возвращаются
 * в пул, а запросы не повторяются: уже прочитанное тело servlet-запроса нельзя
 * отправить заново без сохранённой копии. Жизненным циклом отдельного обмена
 * управляет {@link ProxyCommand}, жизненным циклом общих ресурсов — этот класс.
 */
final class ProxyTransport implements AutoCloseable {
    final ProxyProperties properties;
    final URI backend;
    final String hostHeader;
    final int port;
    final Semaphore inFlight;
    final NioEventLoopGroup eventLoops;
    final DnsAddressResolverGroup resolver;
    // Чтение и запись Servlet API могут блокироваться. Они выполняются на виртуальных
    // потоках; число активных обменов ограничивает inFlight, а не сам исполнитель.
    final ExecutorService servletIo = Executors.newVirtualThreadPerTaskExecutor();
    // Группа позволяет закрыть все каналы при остановке. Закрытые каналы Netty
    // удаляет из неё автоматически; глобальный служебный исполнитель группе не принадлежит.
    final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    final Set<ProxyCommand> exchanges = ConcurrentHashMap.newKeySet();
    final AtomicInteger ioTasks = new AtomicInteger();
    private final SslContext tls;
    volatile boolean closing;

    ProxyTransport(ProxyProperties properties) {
        this(properties, DnsServerAddressStreamProviders.platformDefault());
    }

    // Возможность подставить DNS-серверы позволяет проверить зависший запрос DNS
    // локально, без обращения к внешней сети.
    ProxyTransport(ProxyProperties properties, DnsServerAddressStreamProvider dnsServers) {
        this.properties = properties;
        backend = URI.create(properties.backendUrl());
        port = backend.getPort() >= 0 ? backend.getPort() : "https".equals(backend.getScheme()) ? 443 : 80;
        hostHeader = backend.getRawAuthority();
        inFlight = new Semaphore(properties.maxInFlight());
        try {
            // Используется штатная проверка цепочки сертификатов. Проверка имени
            // бэкенда добавляется ниже для каждого SSLEngine отдельно.
            tls = "https".equals(backend.getScheme()) ? SslContextBuilder.forClient().build() : null;
        } catch (javax.net.ssl.SSLException e) {
            throw new IllegalArgumentException("Cannot configure backend TLS", e);
        }
        eventLoops = new NioEventLoopGroup(properties.ioThreads());
        // Синхронное разрешение имени средствами JDK могло бы занять поток Netty
        // и задержать его таймеры. Асинхронное разрешение имени сохраняет возможность
        // отменить обмен по общему таймауту даже во время ожидания DNS-ответа.
        // Таймаут ниже относится к отдельному DNS-запросу, а не ко всему обмену.
        resolver = new DnsAddressResolverGroup(new DnsNameResolverBuilder()
                .channelType(NioDatagramChannel.class).socketChannelType(NioSocketChannel.class)
                .nameServerProvider(dnsServers).queryTimeoutMillis(properties.connectTimeout().toMillis()));
    }

    /**
     * Создаёт настройки одного соединения и связывает его с обработчиком обмена.
     * Общими остаются потоки и компонент разрешения имён; обработчик ответа
     * не разделяется между каналами.
     */
    Bootstrap bootstrap(ProxyCommand exchange) {
        return new Bootstrap().group(eventLoops).resolver(resolver).channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                // HTTP-ответ читается по явному channel.read() от потребителя.
                // SslHandler самостоятельно запрашивает чтения, необходимые для TLS-рукопожатия.
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.TCP_NODELAY, true)
                // Ограничиваем размер буфера и число чтений за один цикл Netty.
                // Это не предел памяти всех уровней: у TLS, кодека и ОС есть свои буферы.
                .option(ChannelOption.RCVBUF_ALLOCATOR,
                        new FixedRecvByteBufAllocator(properties.chunkSize()).maxMessagesPerRead(1))
                // Пороги переключают channel.isWritable(), но сами не запрещают
                // новые записи. ProxyCommand учитывает этот сигнал и дополнительно
                // ждёт завершения каждой записи до чтения следующей порции тела входящего запроса.
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(properties.chunkSize(), properties.chunkSize() * 2))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel channel) {
                        channels.add(channel);
                        if (tls != null) {
                            // Имя передаётся для TLS/SNI и сверяется с сертификатом.
                            // Одной доверенной цепочки недостаточно: она могла быть выдана другому хосту.
                            var handler = tls.newHandler(channel.alloc(), backend.getHost(), port);
                            var parameters = handler.engine().getSSLParameters();
                            parameters.setEndpointIdentificationAlgorithm("HTTPS");
                            handler.engine().setSSLParameters(parameters);
                            handler.setHandshakeTimeoutMillis(properties.connectTimeout().toMillis());
                            channel.pipeline().addLast("tls", handler);
                        }
                        // Кодек разбирает/формирует HTTP-сообщения без накопления всего тела.
                        // Ограничены строка ответа, заголовки и размер порции содержимого;
                        // последний флаг включает обнаружение закрытия без ожидаемого ответа.
                        channel.pipeline().addLast("http", new HttpClientCodec(4096, 8192,
                                properties.chunkSize(), true));
                        // Одно чтение из сокета может дать несколько HttpObject. FlowControlHandler
                        // выдаёт их по запросу потребителя, удерживая остальные до следующего read().
                        // true означает освобождение оставшихся сообщений при уничтожении обработчика.
                        channel.pipeline().addLast("flow", new FlowControlHandler(true));
                        channel.pipeline().addLast("backend", exchange.inbound);
                    }
                });
    }

    // Эти показатели описывают наши ресурсы, но не заменяют проверку завершения
    // асинхронного запроса внутри Jetty и не измеряют суммарную память процесса.
    int activeIoTasks() { return ioTasks.get(); }
    int activeChannels() { return channels.size(); }
    int activeExchanges() { return exchanges.size(); }

    /**
     * Останавливает обмены до уничтожения обслуживающих их потоков.
     * <p>
     * Пока закрываются каналы и завершаются servlet-задачи, потоки Netty ещё работают:
     * они нужны для обработки закрытия, отмены и освобождения буферов. После ограниченного
     * ожидания оставшимся servlet-задачам посылается прерывание; затем закрываются компонент
     * разрешения имён и группа потоков Netty. Блокирующее ожидание здесь допустимо в потоке остановки
     * приложения, но этот метод нельзя вызывать из потока самого Netty.
     */
    @Override public void close() {
        closing = true;
        exchanges.forEach(exchange -> exchange.cancel(new java.util.concurrent.RejectedExecutionException("Proxy shutting down")));
        channels.close().awaitUninterruptibly(properties.shutdownTimeout().toMillis());
        servletIo.shutdown();
        try {
            if (!servletIo.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                servletIo.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            servletIo.shutdownNow();
        }
        resolver.close();
        eventLoops.shutdownGracefully(0, properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(properties.shutdownTimeout().toMillis());
    }
}
