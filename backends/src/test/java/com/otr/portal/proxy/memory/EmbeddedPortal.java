package com.otr.portal.proxy.memory;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.proxy.HttpProxy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

/**
 * Встроенный Jetty 12 (ee10) с асинхронным сервлетом, вызывающим {@link HttpProxy#proxyRequest}.
 * Перед сервлетом стоит {@link HeaderWriterFilter} Spring Security (обёртка ответа с отслеживанием коммита).
 */
public final class EmbeddedPortal implements AutoCloseable {
    public static final String THREAD_NAME_PREFIX = "proxy-it-jetty";
    public static final String SECURITY_HEADER = "X-Portal-Security";

    private static final Authentication AUTHENTICATION =
            new UsernamePasswordAuthenticationToken("test-user", null, Collections.emptyList());
    private static final EnumSet<DispatcherType> DISPATCHER_TYPES = EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC);

    private final Server server;
    private final ServerConnector connector;

    private EmbeddedPortal(Server server, ServerConnector connector) {
        this.server = server;
        this.connector = connector;
    }

    /**
     * Портал без фильтров запроса; {@code gzip} оборачивает его в Jetty {@code GzipHandler}.
     * {@code backendResolver} по URI запроса выбирает хост — вместо {@code BackendForRequestFilter}.
     */
    public static EmbeddedPortal start(HttpProxy httpProxy, Function<String, Backend> backendResolver, boolean gzip)
            throws Exception {
        ServletContextHandler context = createContext(httpProxy, backendResolver, List.of());
        return startServer(gzip ? wrapWithGzip(context) : context);
    }

    /**
     * {@code requestFilters} ставятся перед прокси в заданном порядке — как фильтры портала перед {@code HttpProxyFilter}.
     */
    public static EmbeddedPortal startWithFilters(HttpProxy httpProxy, Function<String, Backend> backendResolver,
                                                  List<Filter> requestFilters) throws Exception {
        return startServer(createContext(httpProxy, backendResolver, requestFilters));
    }

    public String url(String path) {
        return "http://127.0.0.1:" + connector.getLocalPort() + path;
    }

    /**
     * Порт для запросов в обход HTTP-клиента, например через сырой сокет.
     */
    public int port() {
        return connector.getLocalPort();
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }

    private static ServletContextHandler createContext(HttpProxy httpProxy, Function<String, Backend> backendResolver,
                                                       List<Filter> requestFilters) {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        addFilter(context, new HeaderWriterFilter(List.of(new StaticHeadersWriter(SECURITY_HEADER, "on"))));
        requestFilters.forEach(filter -> addFilter(context, filter));
        ServletHolder proxy = new ServletHolder(new ProxyServlet(httpProxy, backendResolver));
        proxy.setAsyncSupported(true);
        context.addServlet(proxy, "/*");
        return context;
    }

    private static void addFilter(ServletContextHandler context, Filter filter) {
        FilterHolder holder = new FilterHolder(filter);
        holder.setAsyncSupported(true);
        context.addFilter(holder, "/*", DISPATCHER_TYPES);
    }

    private static Handler wrapWithGzip(Handler handler) {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setMinGzipSize(128);
        gzipHandler.setSyncFlush(true);
        gzipHandler.setHandler(handler);
        return gzipHandler;
    }

    private static EmbeddedPortal startServer(Handler handler) throws Exception {
        QueuedThreadPool threadPool = new QueuedThreadPool(32, 8);
        threadPool.setName(THREAD_NAME_PREFIX);
        Server server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
        return new EmbeddedPortal(server, connector);
    }

    private static final class ProxyServlet extends HttpServlet {
        private final transient HttpProxy httpProxy;
        private final transient Function<String, Backend> backendResolver;

        private ProxyServlet(HttpProxy httpProxy, Function<String, Backend> backendResolver) {
            this.httpProxy = httpProxy;
            this.backendResolver = backendResolver;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            SecurityContextHolder.getContext().setAuthentication(AUTHENTICATION);
            try {
                req.setAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME, backendResolver.apply(req.getRequestURI()));
                httpProxy.proxyRequest(req, resp);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
