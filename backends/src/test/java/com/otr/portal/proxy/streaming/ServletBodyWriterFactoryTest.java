package com.otr.portal.proxy.streaming;

import com.otr.portal.proxy.caching.ContentCachingResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Выбор стратегии записи тела на настоящем Jetty 12: сервлет вызывает фабрику с теми запросом и ответом,
 * которые получает приложение, и передаёт результат тесту.
 * <p>
 * Тест проверяет сам выбор writer'а. {@code ProxyBodyMemoryTest} заметит переход на блокирующую запись
 * только косвенно, по аллокациям в куче.
 */
class ServletBodyWriterFactoryTest {
    private static final long TIMEOUT_SECONDS = 10;

    private final OkHttpClient client = new OkHttpClient();
    private Server server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Основной путь портала: асинхронный запрос за {@link HeaderWriterFilter} Spring Security. Его обёртка только
     * отслеживает коммит, поэтому фабрика должна дойти до ответа Jetty этого запроса и выбрать запись без копирования.
     */
    @Test
    void choosesAsyncWriterBehindCommitTrackingWrapper() throws Exception {
        Selection selection = selectWriter(commitTrackingFilter(), RequestMode.ASYNC);

        assertThat(selection.writer).isInstanceOf(AsyncJettyBodyWriter.class);
        assertThat(selection.asyncResponse)
                .isNotNull()
                .isSameAs(selection.jettyResponse);
    }

    /**
     * Без асинхронного режима {@code WriteListener} не зарегистрировать, остаётся блокирующая запись.
     */
    @Test
    void choosesBlockingWriterWithoutAsyncMode() throws Exception {
        Selection selection = selectWriter(commitTrackingFilter(), RequestMode.BLOCKING);

        assertThat(selection.writer).isInstanceOf(BlockingBodyWriter.class);
        assertThat(selection.asyncResponse).isNull();
    }

    /**
     * {@link ContentCachingResponse} копирует тело в кэш через свой {@code getOutputStream}: при записи в обход
     * обёртки кэш остался бы пустым.
     */
    @Test
    void choosesBlockingWriterBehindOutputInterceptor() throws Exception {
        Selection selection = selectWriter(contentCachingFilter(), RequestMode.ASYNC);

        assertThat(selection.writer).isInstanceOf(BlockingBodyWriter.class);
        assertThat(selection.asyncResponse).isNull();
    }

    @Test
    void choosesBlockingWriterOutsideJetty() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        request.startAsync();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(createWriter(request, response)).isInstanceOf(BlockingBodyWriter.class);
        assertThat(ServletBodyWriterFactory.findAsyncWritableResponse(request, response)).isNull();
    }

    private Selection selectWriter(Filter filter, RequestMode mode) throws Exception {
        CompletableFuture<Selection> selection = new CompletableFuture<>();
        int port = startServer(filter, new ProbeServlet(mode, selection));
        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + port + "/")
                .build();
        try (Response ignored = client.newCall(request).execute()) {
            return selection.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private int startServer(Filter filter, HttpServlet servlet) throws Exception {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        FilterHolder filterHolder = new FilterHolder(filter);
        filterHolder.setAsyncSupported(true);
        context.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));
        ServletHolder servletHolder = new ServletHolder(servlet);
        servletHolder.setAsyncSupported(true);
        context.addServlet(servletHolder, "/*");

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(context);
        server.start();
        return connector.getLocalPort();
    }

    private static Filter commitTrackingFilter() {
        return new HeaderWriterFilter(List.of(new StaticHeadersWriter("X-Test", "on")));
    }

    private static Filter contentCachingFilter() {
        return (request, response, chain) ->
                chain.doFilter(request, new ContentCachingResponse((HttpServletResponse) response));
    }

    private static ServletBodyWriter createWriter(HttpServletRequest request, HttpServletResponse response) {
        return ServletBodyWriterFactory.createWriter(BodyWriterContext.builder()
                .request(request)
                .response(response)
                .listener(Mockito.mock(BodyWriteListener.class))
                .callbackExecutor(Runnable::run)
                .build());
    }

    private enum RequestMode {
        ASYNC,
        BLOCKING
    }

    /**
     * Вызывает фабрику изнутри Jetty и передаёт результат тесту; ответ клиенту пустой.
     */
    private static final class ProbeServlet extends HttpServlet {
        private final RequestMode mode;
        private final transient CompletableFuture<Selection> selection;

        private ProbeServlet(RequestMode mode, CompletableFuture<Selection> selection) {
            this.mode = mode;
            this.selection = selection;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) {
            try {
                if (mode == RequestMode.ASYNC) {
                    req.startAsync();
                }
                selection.complete(Selection.capture(req, resp));
            } catch (RuntimeException e) {
                selection.completeExceptionally(e);
            } finally {
                if (req.isAsyncStarted()) {
                    req.getAsyncContext().complete();
                }
            }
        }
    }

    /**
     * Что выбрала фабрика и какой ответ Jetty на самом деле принадлежит запросу.
     */
    private static final class Selection {
        private final ServletBodyWriter writer;
        private final ServletContextResponse asyncResponse;
        private final ServletContextResponse jettyResponse;

        private Selection(ServletBodyWriter writer, ServletContextResponse asyncResponse,
                          ServletContextResponse jettyResponse) {
            this.writer = writer;
            this.asyncResponse = asyncResponse;
            this.jettyResponse = jettyResponse;
        }

        static Selection capture(HttpServletRequest request, HttpServletResponse response) {
            return new Selection(
                    createWriter(request, response),
                    ServletBodyWriterFactory.findAsyncWritableResponse(request, response),
                    ServletContextResponse.getServletContextResponse(response));
        }
    }
}
