package com.otr.portal;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsService;
import com.otr.tests.portal.TestContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author pobedenniy.alexey
 * @since 03.04.19
 */
public class TestBackend extends Backend {

    TestBackend(Host host, int localPort, @Nullable HostsService hostsService) {
        super(host, localPort, hostsService);
    }

    public static TestBackendBuilder create(String name) {
        return new TestBackendBuilder(name);
    }

    public static class TestBackendBuilder {
        private final String name;
        private String uri;
        private String host;
        private String target;
        private int localPort = 0;
        private String prefix;
        private List<String> passthroughCookies;

        public TestBackendBuilder(String name) {
            this.name = name;
        }

        public TestBackendBuilder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public TestBackendBuilder host(String host) {
            this.host = host;
            return this;
        }

        public TestBackendBuilder target(String target) {
            this.target = target;
            return this;
        }

        public TestBackendBuilder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public TestBackendBuilder passthroughCookies(List<String> passthroughCookies) {
            this.passthroughCookies = passthroughCookies;
            return this;
        }

        public Backend build() {
            final Host host = new Host(name, this.host, this.target);
            host.setUri(this.uri);
            host.setPrefix(this.prefix);
            host.setPassthroughCookies(this.passthroughCookies);
            final HostsService hostsService = new HostsService(new TestContext.TestServerPortHolder(), null) {
                @Override
                public boolean isUserUris() {
                    return TestBackendBuilder.this.uri != null;
                }
            };
            return new TestBackend(host, this.localPort, hostsService);
        }
    }
}
