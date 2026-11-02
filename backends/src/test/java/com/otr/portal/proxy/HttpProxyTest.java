package com.otr.portal.proxy;

import com.google.common.base.Joiner;
import com.otr.portal.backends.ssl.netty.SslContextHandlerFactory;
import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceRegistryConfig;
import com.otr.portal.core.Configuration;
import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.backends.SingleBackendRegistryImpl;
import com.otr.portal.hosts.filter.BackendForRequestFilter;
import com.otr.portal.proxy.ssl.NettyTlsFactory;
import com.otr.tests.portal.TestContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.otr.portal.proxy.cookies.ProxiedCookie.COOKIE_POSTFIX_NAME;
import static com.otr.portal.proxy.cookies.ProxiedCookie.COOKIE_PREFIX_NAME;
import static com.otr.portal.proxy.cookies.ProxiedCookie.PROXY_COOKIE_PREFIX_NAME;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;
import static org.springframework.mock.web.MockHttpSession.SESSION_COOKIE_NAME;

/**
 * @author pobedenniy.alexey
 * @since 07.09.16
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        webEnvironment = NONE,
        classes = {
                PathHelper.class,
                Configuration.class,
                HttpProxy.class,
                TestContext.class,
                ResilienceRegistryConfig.class,
                CommandConfigRegistry.class,
                CommandResilienceDecorator.class,
                ProxyRequestExecutorLookup.class,
                ProxyCommandFactory.class,
                NettyProxyRequestExecutor.class
        },
        properties = {
                "spring.cloud.bootstrap.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration"
        }
)
class HttpProxyTest {
    public static final String PROXYJSESSIONID_COOKIE_NAME = PROXY_COOKIE_PREFIX_NAME + COOKIE_PREFIX_NAME + SESSION_COOKIE_NAME + COOKIE_POSTFIX_NAME;
    private static final String MAIN_HTML = "/main.html";
    private static final String UPLOAD = "/upload";
    private static final String DYNAMIC_CONTENT = "/zkau";

    private static final String TEXT_REPONSE_BODY = "Positive response";

    private MockWebServer mockWebServer;
    @Autowired
    PathHelper pathHelper;
    @Autowired
    HttpProxy httpProxy;
    @Autowired
    Configuration configuration;
    @Autowired
    String notavailableContent;
    @Autowired
    HostsService hostsService;
    @Autowired
    SslContextHandlerFactory sslContextHandlerFactory;
    @MockitoBean
    NettyTlsFactory nettyTlsFactory;

    @Autowired
    TestContext.TestServerPortHolder serverPortHolder;

    @Autowired
    SingleBackendRegistryImpl singleBackendRegistry;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-user", null, Collections.emptyList())
        );
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        serverPortHolder.setPort(mockWebServer.getPort());
        singleBackendRegistry.init();
        hostsService.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void proxyPageFromRemoteSource() throws Exception {
        mockWebServer.enqueue(new MockResponse().setBody(TEXT_REPONSE_BODY));

        final MockHttpServletRequest originalRequest = new MockHttpServletRequest(HttpMethod.GET.name(), MAIN_HTML);
        originalRequest.setAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME, singleBackendRegistry.lookupBackend(originalRequest));
        String load = httpProxy.load(MAIN_HTML, originalRequest, new PrintToStringHttpServletResponse());
        Assertions.assertThat(load)
                .isEqualTo(TEXT_REPONSE_BODY);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void requestToFailedResourceShouldRenderErrorPage() throws Exception {
        mockWebServer.shutdown();
        final MockHttpServletRequest originalRequest = new MockHttpServletRequest(HttpMethod.GET.name(), MAIN_HTML);
        originalRequest.setAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME, singleBackendRegistry.lookupBackend(originalRequest));
        String result = httpProxy.load(MAIN_HTML, originalRequest, new PrintToStringHttpServletResponse());

        Assertions.assertThat(result)
                .isEqualTo(notavailableContent);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void proxyRequestWillSendResponseBodyAndHeadersToRemoteSource() throws Exception {
        mockWebServer.enqueue(new MockResponse().setBody(TEXT_REPONSE_BODY));

        PrintToStringHttpServletResponse response = executeRequest(DYNAMIC_CONTENT);
        Assertions.assertThat(response.asString())
                .isEqualTo(TEXT_REPONSE_BODY);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void proxyRequestWillReturnRequestTimeoutIfRemoteUnavailable() throws Exception {
        mockWebServer.shutdown();

        PrintToStringHttpServletResponse response = executeRequest(DYNAMIC_CONTENT);

        Assertions.assertThat(response.getStatus())
                .isEqualTo(HttpServletResponse.SC_REQUEST_TIMEOUT);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void proxyRequestWillRenameSessionCookieFromRemote() throws Exception {
        final String expectedCookieValue = UUID.randomUUID().toString();
        mockWebServer.enqueue(new MockResponse()
                .setBody(TEXT_REPONSE_BODY)
                .addHeader(HttpHeaders.SET_COOKIE, SESSION_COOKIE_NAME + "=" + expectedCookieValue));

        PrintToStringHttpServletResponse response = executeRequest(MAIN_HTML);

        Assertions.assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                        .startsWith(PROXYJSESSIONID_COOKIE_NAME + "=" + expectedCookieValue);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void proxyRequestWillRenameSessionCookieFromClient() throws Exception {
        final String expectedCookieValue = UUID.randomUUID().toString();
        mockWebServer.enqueue(new MockResponse()
                .setBody(TEXT_REPONSE_BODY)
                .addHeader(HttpHeaders.SET_COOKIE, SESSION_COOKIE_NAME + "=" + expectedCookieValue));

        PrintToStringHttpServletResponse response = executeRequest(MAIN_HTML, new Cookie(PROXYJSESSIONID_COOKIE_NAME, expectedCookieValue));

        Assertions.assertThat(response.getStatus())
                        .isEqualTo(HttpServletResponse.SC_OK);
        Assertions.assertThat(response.asString())
                        .isEqualTo(TEXT_REPONSE_BODY);
    }


    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void testFileUpload() throws IOException, ServletException {
        String fileName = UUID.randomUUID().toString();
        byte[] fileContent = UUID.randomUUID().toString().getBytes();
        String paramName = "DesktopId";
        String paramValue = UUID.randomUUID().toString();

        mockWebServer.enqueue(new MockResponse().setBody(TEXT_REPONSE_BODY));

        final MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME, singleBackendRegistry.lookupBackend(request));
        request.addParameter(paramName, paramValue);
        request.addFile(new MockMultipartFile(fileName, fileContent));
        final PrintToStringHttpServletResponse response = new PrintToStringHttpServletResponse();

        request.setAsyncSupported(true);
        httpProxy.proxyRequest(request, response);
        TestHelper.waitForAsyncResponse(request);

        Assertions.assertThat(response.getStatus())
                        .isEqualTo(HttpServletResponse.SC_OK);
        Assertions.assertThat(response.asString())
                        .isEqualTo(TEXT_REPONSE_BODY);
    }


    private PrintToStringHttpServletResponse executeRequest(String url, Cookie... cookies) throws IOException, ServletException {
        final PrintToStringHttpServletResponse response = new PrintToStringHttpServletResponse();
        final MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), url);
        request.setCookies(cookies);
        request.addHeader(HttpHeaders.COOKIE, Joiner.on("; ").join(Stream.of(cookies).map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.toList())));
        request.setAttribute(BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME, singleBackendRegistry.lookupBackend(request));
        request.setAsyncSupported(true);
        httpProxy.proxyRequest(request, response);
        TestHelper.waitForAsyncResponse(request);
        return response;
    }

}
