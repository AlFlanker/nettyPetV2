package example.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/**
 * Локальный целевой сервер для проверки прокси. Читает тело порциями,
 * вычисляя SHA-256 и длину без хранения всего содержимого. Эти значения позволяют
 * проверить, что прокси сохранил исходные байты формы, файла или бинарного запроса.
 */
public final class DemoBackend {
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("BACKEND_PORT", "9090"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", DemoBackend::digest);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.close();
        }));
        server.start();
        System.out.println("Digest backend listening on 127.0.0.1:" + port);
    }

    private static void digest(HttpExchange exchange) throws IOException {
        try (exchange) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long count = 0;
            byte[] chunk = new byte[16 * 1024];
            // Буфер переиспользуется при каждом чтении. Даже в демонстрационном
            // сервере потребление памяти не должно зависеть от размера загрузки.
            try (var input = exchange.getRequestBody()) {
                for (int read; (read = input.read(chunk)) != -1;) {
                    digest.update(chunk, 0, read);
                    count += read;
                }
            }
            // В памяти формируется только небольшой диагностический ответ.
            // Параметры query и формы не разбираем: для проверки важен исходный вид.
            String result = "method=" + exchange.getRequestMethod()
                    + "\nuri=" + exchange.getRequestURI().toASCIIString()
                    + "\ncontent-type=" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + "\nbytes=" + count
                    + "\nsha256=" + HexFormat.of().formatHex(digest.digest()) + "\n";
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
