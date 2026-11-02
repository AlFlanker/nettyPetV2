package com.otr.portal.proxy.cookies;

import com.otr.portal.TestBackend;
import com.otr.portal.hosts.Backend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;

class PassthroughCookieInterceptorTest {
    private PassthroughCookieInterceptor cookieInterceptor;
    private Backend backend;

    @BeforeEach
    void setUp() {
        cookieInterceptor = new PassthroughCookieInterceptor();
        this.backend = TestBackend.create("PFKAP").build();
    }

    @Test
    @DisplayName("Пустой список - cookies не меняются")
    void shouldNotChangeCookiesWhenPassthroughListIsEmpty() {
        var cookie = Cookie.parse("IZAUTH=session-value");
        var request =
                new MockHttpServletRequest(GET.name(), "/test-uri");
        request.addHeader("Cookie", "IZAUTH=session-value");

        var res = cookieInterceptor.cookiesForBackend(Stream.of(cookie), request, this.backend);
        var cookies = res.collect(toList());

        assertThat(cookies)
                .hasSize(1);
        assertThat(cookies.get(0).name())
                .isEqualTo("IZAUTH");
        assertThat(cookies.get(0).value())
                .isEqualTo("session-value");
    }

    @Test
    @DisplayName("Cookie передаётся на бэкенд без переименования")
    void shouldSendPassthroughCookieFromRequestWithoutRenaming() {
        this.backend = TestBackend.create("PFKAP")
                .passthroughCookies(singletonList("IZAUTH"))
                .build();
        var request = new MockHttpServletRequest(GET.name(), "/test-uri");
        request.addHeader("Cookie", "IZAUTH=session-value; OTHER=value");

        var res =
                cookieInterceptor.cookiesForBackend(Stream.empty(), request, this.backend);
        var cookies =
                res.collect(toList());

        assertThat(cookies)
                .hasSize(1);
        assertThat(cookies.get(0).name())
                .isEqualTo("IZAUTH");
        assertThat(cookies.get(0).value())
                .isEqualTo("session-value");
    }

    @Test
    @DisplayName("Cookie из запроса заменяет значение из потока")
    void shouldReplacePassthroughCookieFromStreamWithOriginalRequestCookie() {
        this.backend = TestBackend.create("TST_BKND")
                .passthroughCookies(singletonList("IZAUTH"))
                .build();
        var request = new MockHttpServletRequest(GET.name(), "/test-uri");
        request.addHeader("Cookie", "IZAUTH=session-value; OTHER=value");

        var res = cookieInterceptor.cookiesForBackend(
                Stream.of(Cookie.parse("IZAUTH=old-value"), Cookie.parse("OTHER=value")),
                request,
                this.backend
        );
        var cookies = res.collect(toList());

        assertThat(cookies)
                .hasSize(2);
        assertThat(cookies)
                .extracting(Cookie::name).containsExactly("OTHER", "IZAUTH");
        assertThat(cookies)
                .extracting(Cookie::value).containsExactly("value", "session-value");
    }
}
