package com.otr.portal.hosts;

/**
 * @author pobedenniy.alexey
 * @since 23.11.17
 */
public interface URLGenerator {
    String generateUrl(String host, String uri, int localPort);

    String generateRelUrl(String uri);
}
