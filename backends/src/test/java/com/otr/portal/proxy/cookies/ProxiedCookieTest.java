package com.otr.portal.proxy.cookies;

import com.otr.portal.proxy.cookies.Cookie;
import com.otr.portal.proxy.cookies.ProxiedCookie;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author pobedenniy.alexey
 * @since 22.03.19
 */
public class ProxiedCookieTest {

    @Test
    public void remove_prefix_from_cookie() {
        final String name = "name";
        final String value = "value";
        Cookie c = Cookie.parse(ProxiedCookie.generateCookieName(name, null, null) + "=" + value);

        ProxiedCookie pc = ProxiedCookie.parseProxied(c);

        assertThat(pc.name(), is(name));
        assertThat(pc.value(), is(value));
    }

    @Test
    public void get_backend_from_cookie() {
        final String name = "name";
        final String value = "value";
        final String backend = "backend";
        Cookie c = Cookie.parse(ProxiedCookie.generateCookieName(name, null, backend) + "=" + value);

        ProxiedCookie pc = ProxiedCookie.parseProxied(c);

        assertThat(pc.name(), is(name));
        assertThat(pc.value(), is(value));
        assertThat(pc.backend(), is(backend));
    }

    @Test
    public void get_path_from_cookie () {
        final String name = "name";
        final String value = "value";
        final String backend = "backend";
        final String path = "cookie-path/";
        Cookie c = Cookie.parse(ProxiedCookie.generateCookieName(name, path, backend) + "=" + value);

        ProxiedCookie pc = ProxiedCookie.parseProxied(c);

        assertThat(pc.name(), is(name));
        assertThat(pc.value(), is(value));
        assertThat(pc.backend(), is(backend));
        assertThat(pc.path(), is(path));
    }

    @Test
    public void get_path_from_cookie_() {
        final String name = "name";
        final String value = "value";
        final String backend = "backend";
        final String path = "cookie-path";
        Cookie c = Cookie.parse(ProxiedCookie.generateCookieName(name, path, backend) + "=" + value);

        ProxiedCookie pc = ProxiedCookie.parseProxied(c);

        assertThat(pc.name(), is(name));
        assertThat(pc.value(), is(value));
        assertThat(pc.backend(), is(backend));
        assertThat(pc.path(), is(path.concat("/")));
    }

}
