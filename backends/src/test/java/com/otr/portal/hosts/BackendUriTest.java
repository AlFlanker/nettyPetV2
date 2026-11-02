package com.otr.portal.hosts;

import com.otr.portal.TestBackend;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BackendUriTest {

    @ParameterizedTest(name = "{index}: cut backendUri from {1}")
    @MethodSource("data")
    public void test_cutBackendUri(Backend backend, String requestUri, String expectedUri) {
        assertEquals(expectedUri, backend.cutBackendUri(requestUri));
    }

    public static Stream<Arguments> data() {
        return Stream.of(
                Arguments.of(
                        TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").build(),
                        "/test/testPageUri", "/testPageUri"),
                Arguments.of(
                        TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").build(),
                        "/testPageUri", "/testPageUri"),
                Arguments.of(
                        TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").prefix("testPage").build(),
                        "/testPage/testPageUri", "/testPage/testPageUri"
                ),
                Arguments.of(
                        TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").prefix("testPage").build(),
                        "/test/testPage/testPageUri", "/testPage/testPageUri"
                ),
                Arguments.of(
                        TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").prefix("test").build(),
                        "/test/test/testPageUri", "/test/testPageUri"
                ),
                Arguments.of(
                        TestBackend.create("TEST-BACKEND").target("http://test-backend.domain:8080").uri("test").prefix("test").build(),
                        "/test/testPageUri", "/test/testPageUri"
                )
        );
    }
}
