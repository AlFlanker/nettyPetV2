package com.otr.portal.hosts.health;

import io.netty.handler.codec.http.HttpHeaders;

/**
 * @author pobedenniy.alexey
 * @since 28.11.17
 */
public class BackendHealth {
    private final String backend;
    private final String backendType;
    private final int code;
    private Throwable err;
    private HttpHeaders headers;

    public BackendHealth(String backend, String backendType, int code) {
        this.backend = backend;
        this.backendType = backendType;
        this.code = code;
    }

    public BackendHealth(String backend, String backendType, int code, Throwable t) {
        this.backend = backend;
        this.backendType = backendType;
        this.code = code;
        this.err = t;
    }

    public BackendHealth(String backend, String backendType, int code, HttpHeaders headers) {
        this.backend = backend;
        this.backendType = backendType;
        this.code = code;
        this.headers = headers;
    }

    public String backendName() {
        return backend;
    }

    public boolean isOK() {
        return code == 200 || code == 404;
    }

    public int getCode() {
        return code;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public String backendType() {
        return backendType;
    }
}
