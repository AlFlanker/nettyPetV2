package com.otr.portal.hosts.filter;

import com.otr.portal.hosts.Backend;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLEncoder;

import static com.otr.portal.hosts.filter.BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ProxyAwareFormContentFilter} разбирает PUT-формы только у локальных запросов.
 * Признак проксируемого запроса - атрибут бэкенда, как у {@code ProxySpringConfiguration#proxiedRequestMatcher}.
 */
class ProxyAwareFormContentFilterTest {
    private static final String CONTENT = "{\"id\":\"значение\"}";
    private static final byte[] FORM = ("content=" + URLEncoder.encode(CONTENT, UTF_8)).getBytes(UTF_8);

    private final ProxyAwareFormContentFilter filter =
            new ProxyAwareFormContentFilter(request -> request.getAttribute(BACKEND_ATTRIBUTE_NAME) != null);

    /**
     * PUT-форма к бэкенду идёт дальше нетронутой, и прокси читает из потока всё тело.
     * Исходный {@code FormContentFilter} вычитал бы поток, и бэкенд получил бы пустое тело.
     */
    @Test
    void keepsBodyOfProxiedPutForm() throws Exception {
        MockHttpServletRequest request = putForm();
        request.setAttribute(BACKEND_ATTRIBUTE_NAME, mock(Backend.class));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(FORM);
    }

    /**
     * Локальные контроллеры по-прежнему получают поля PUT-формы через {@code getParameter}.
     */
    @Test
    void parsesPutFormOfLocalRequest() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(putForm(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest().getParameter("content")).isEqualTo(CONTENT);
    }

    private static MockHttpServletRequest putForm() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/repository-web/navigator/node/1");
        request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        request.setContent(FORM);
        return request;
    }
}
