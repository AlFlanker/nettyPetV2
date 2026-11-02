package example.proxy;

import io.netty.resolver.dns.DnsServerAddresses;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Медленный DNS не должен блокировать цикл событий Netty и выполнение запланированного таймера. */
class DnsProgressTest {
    @Test void stalledDnsDoesNotBlockEventLoopDeadline() throws Exception {
        try (DatagramSocket silentDns = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            silentDns.setSoTimeout(3000);
            var properties = new ProxyProperties("http://proxy-backend.invalid:8080", 1, 1, 16384,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(2));
            var dnsAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), silentDns.getLocalPort());
            try (ProxyTransport transport = new ProxyTransport(properties,
                    host -> DnsServerAddresses.singleton(dnsAddress).stream())) {
                var exchange = new ProxyCommand(mock(HttpServletRequest.class), mock(HttpServletResponse.class),
                        mock(AsyncContext.class), transport);
                var connection = transport.bootstrap(exchange).connect("proxy-backend.invalid", 8080);
                try {
                    // Сначала убеждаемся, что DNS-запрос действительно дошёл до неотвечающего сервера.
                    // Затем проверяем таймер в том же единственном цикле событий Netty.
                    silentDns.receive(new DatagramPacket(new byte[4096], 4096));
                    assertThat(connection.isDone()).isFalse();
                    var deadline = new CountDownLatch(1);
                    transport.eventLoops.next().schedule(deadline::countDown, 10, TimeUnit.MILLISECONDS);
                    assertThat(deadline.await(1, TimeUnit.SECONDS)).isTrue();
                    assertThat(connection.isDone()).isFalse();
                } finally {
                    connection.cancel(false);
                    connection.channel().close().syncUninterruptibly();
                }
            }
        }
    }
}
