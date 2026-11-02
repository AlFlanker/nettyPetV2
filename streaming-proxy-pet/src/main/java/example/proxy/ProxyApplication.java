package example.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Запускает самостоятельный прокси: Jetty принимает HTTP-запросы через Servlet API,
 * а прямой клиент io.netty передаёт их целевому серверу. Spring управляет
 * конфигурацией и временем жизни общих ресурсов транспорта.
 */
@SpringBootApplication
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProxyApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    ProxyTransport proxyTransport(ProxyProperties properties) {
        // Все запросы используют общие циклы обработки событий и исполнитель I/O-задач.
        // При закрытии контекста Spring вызывает close и освобождает эти ресурсы.
        return new ProxyTransport(properties);
    }

    @Bean
    ServletRegistrationBean<ProxyServlet> proxyServlet(ProxyTransport transport) {
        // Это выделенное приложение-прокси: все пути обслуживает один сервлет.
        // В приложении с локальными MVC-контроллерами потребуется отдельный маршрут.
        var registration = new ServletRegistrationBean<>(new ProxyServlet(transport), "/*");
        // AsyncContext позволяет завершить запрос после выхода из service():
        // ожидание целевого сервера не занимает поток обработки запроса Jetty.
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }
}
