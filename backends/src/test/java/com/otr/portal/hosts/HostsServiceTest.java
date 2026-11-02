package com.otr.portal.hosts;

import com.otr.portal.TestBackend;
import com.otr.tests.portal.TestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HostsServiceTest {
    private HostsService hostsService;
    private Backend backend;

    @BeforeEach
    public void setUp() throws Exception {
        backend = TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").build();
        hostsService = new HostsService(new TestContext.TestServerPortHolder(), null) {
            @Override
            public boolean isUserUris() {
                return true;
            }
        };
    }

    @Test
    public void fullBackendUrl() {
        assertEquals("http://test-backend.domain:8080/", backend.fullBackendUrl("/"));
    }

    @Test
    public void fullBackendUrlWithSeparators() {
        Backend backend = TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080/").uri("test").build();
        assertEquals("http://test-backend.domain:8080/", backend.fullBackendUrl("/"));
    }

    @Test
    public void fullBackendUrlWithSeparatorsAndLiteralUri() {
        Backend backend = TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080/").uri("test").build();
        assertEquals("http://test-backend.domain:8080/uri", backend.fullBackendUrl("/uri"));
    }

    @Test
    public void fullBackendUrlWithoutSeparators() {
        Backend backend = TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").build();
        assertEquals("http://test-backend.domain:8080/uri", backend.fullBackendUrl("uri"));
    }

    @Test
    public void frontendUrlFromBackendUrl() {
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "http://test-backend.domain:8080/sufdclient/index.zul");
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/sufdclient/index.zul", frontendUrlOptional.get());
    }

    @Test
    public void frontendUrlFromBackendUrlWithoutSchema() {
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "/sufdclient/index.zul");
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/sufdclient/index.zul", frontendUrlOptional.get());
    }

    @Test
    public void frontendUrlFromBackendUrlWithPathSeparators() {
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "/sufdclient/index.zul");
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/sufdclient/index.zul", frontendUrlOptional.get());
    }

    @Test
    public void frontendUrlFromBackendUrlWithAnchor() {
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "http://test-backend.domain:8080/piao/static-report/web/portal.html#/contentExplorer");
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/piao/static-report/web/portal.html#/contentExplorer", frontendUrlOptional.get());
    }

    @Test
    public void frontendUrlFromBackendUrlWithAnchorAndQueryParams() {
        UUID uuid = UUID.randomUUID();
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "http://test-backend.domain:8080/piao/static-report/web/portal.html#/report?uuid=".concat(uuid.toString()));
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/piao/static-report/web/portal.html#/report?uuid=".concat(uuid.toString()), frontendUrlOptional.get());
    }

    @Test
    public void frontendUrlFromBackendUrlWithQueryParamsAndAnchor() {
        UUID uuid = UUID.randomUUID();
        assertEquals("http://test-backend.domain:8080/", backend.fullBackendUrl("/"));
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "http://test-backend.domain:8080/piao/static-report/web/portal.html/report?uuid=".concat(uuid.toString()).concat("#data"));
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/piao/static-report/web/portal.html/report?uuid=".concat(uuid.toString()).concat("#data"), frontendUrlOptional.get());
    }

    @Test
    public void frontendUrlFromBackendUrlWithQueryParams() {
        Optional<String> frontendUrlOptional = hostsService.frontendUrlFromBackendUrl(backend, "http://test-backend.domain:8080/accessible-data/getPersonalAccounts?pDate=07.11.2019");
        assertTrue(frontendUrlOptional.isPresent());
        assertEquals("/test/accessible-data/getPersonalAccounts?pDate=07.11.2019", frontendUrlOptional.get());
    }
}
