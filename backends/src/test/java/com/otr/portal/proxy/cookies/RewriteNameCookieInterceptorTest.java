package com.otr.portal.proxy.cookies;

import com.otr.portal.TestBackend;
import com.otr.portal.hosts.Backend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author pobedenniy.alexey
 * @since 03.04.19
 */
public class RewriteNameCookieInterceptorTest {
    private RewriteNameCookieInterceptor cookieInterceptor;
    private Backend backend;

    @BeforeEach
    public void setUp() {
        cookieInterceptor = new RewriteNameCookieInterceptor();
        this.backend = TestBackend.create("TST_BKND").uri("backend-uri").build();
    }

    /**
     * Проверяем, что кука имеющая путь /BACKEND_URI/path будет отправлена бэкенду
     * вместе с запросом на /path
     */
    @Test
    public void accepts_cookie_for_path_including_backend() {
        final String path = "/test-uri";
        String cookieName = "cookie1";
        String cookieValue = "cookieValue1";
        final Cookie cookie = Cookie.parse(ProxiedCookie.generateCookieName(cookieName, path, this.backend.getUri()) + "=" + cookieValue);

        Stream<? extends Cookie> res = cookieInterceptor.cookiesForBackend(Stream.of(cookie), new MockHttpServletRequest(HttpMethod.GET.name(), path), this.backend);
        List<? extends Cookie> cookies = res.collect(Collectors.toList());

        assertThat(cookies.size()).isEqualTo(1);
    }

    /**
     * Проверяем, что кука имеющая путь / будет отправлена бэкенду
     * вместе с запросом на /path
     */
    @Test
    public void accepts_cookie_without_path() {
        String cookieName = "cookie2";
        String cookieValue = "cookieValue2";
        final Cookie cookie = Cookie.parse(ProxiedCookie.generateCookieName(cookieName, null, this.backend.getUri()) + "=" + cookieValue);

        Stream<? extends Cookie> res = cookieInterceptor.cookiesForBackend(Stream.of(cookie), new MockHttpServletRequest(HttpMethod.GET.name(), "/test-uri1"), this.backend);
        List<? extends Cookie> cookies = res.collect(Collectors.toList());

        assertThat(cookies.size()).isEqualTo(1);
    }
}
