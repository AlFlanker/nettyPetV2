package com.otr.portal.hosts;

import org.apache.commons.lang3.StringUtils;

/**
 * @author pobedenniy.alexey
 * @since 23.11.17
 */
public class URIGenerator implements URLGenerator {

    @Override
    public String generateUrl(String host, String uri, int localPort) {
        boolean shouldAddSlash = !StringUtils.startsWith(uri, "/") && !"".equals(uri);
        return shouldAddSlash ? "/" + uri : uri;
    }

    @Override
    public String generateRelUrl(String uri) {
        return generateUrl(null, uri, -1);
    }
}
