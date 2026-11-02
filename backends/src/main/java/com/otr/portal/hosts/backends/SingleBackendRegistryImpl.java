package com.otr.portal.hosts.backends;

import com.otr.portal.core.support.ServerPortHolder;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @author pobedenniy.alexey
 * @since 21.09.17
 */
public class SingleBackendRegistryImpl implements BackendRegistry {
    private Backend backend;
    private String remoteHost;
    private final ServerPortHolder serverPortHolder;


    public SingleBackendRegistryImpl(String remoteHost, ServerPortHolder serverPortHolder) {
        this.remoteHost = remoteHost;
        this.serverPortHolder = serverPortHolder;
    }

    @Override
    public Backend lookupBackend(HttpServletRequest req) {
        return backend;
    }

    public void init() {
        backend = createBackend("http://" + remoteHost + ":" + serverPortHolder.getServerPort(), serverPortHolder.getServerPort());
    }

    private Backend createBackend(String remoteHost, int port) {
        Backend backend;
        try {
            backend = new Backend(new Host("default", InetAddress.getLocalHost().getHostName(), remoteHost), port, null);
        } catch (UnknownHostException e) {
            backend = new Backend(new Host("default", "localhost", remoteHost), port, null);
        }
        return backend;
    }
}
