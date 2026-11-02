package com.otr.portal.hosts;

import com.otr.portal.metadata.config.ApplicationProperties;

/**
 * @author pobedenniy.alexey
 * @since 05.10.18
 */
public class CookieShare {
    @ApplicationProperties(name = "Имя куки", example = "OAMAuthCookie")
    private String cookieName;

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }
}
