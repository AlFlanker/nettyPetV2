package com.otr.portal.hosts;

import org.apache.commons.lang3.StringUtils;

/**
 * @author pobedenniy.alexey
 * @since 23.11.17
 */
public class DomainNameGenerator implements URLGenerator {
    public DomainNameGenerator() {
    }

    @Override
    public String generateUrl(String host, String uri, int localPort) {
        return "http://" + StringUtils.removeEnd(host, "/") + ":" + localPort;
    }

    @Override
    public String generateRelUrl(String uri) {
        return "/";
    }
}
