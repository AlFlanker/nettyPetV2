package com.otr.portal.proxy.logging;

import com.otr.portal.proxy.metrics.BackendsMetrics;
import com.otr.portal.proxy.metrics.MicrometerBackendsMetricsImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

public class ProxiedRequestLifecycleTest {

    @Test
    public void failRequestNPETest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        BackendsMetrics metrics = spy(new MicrometerBackendsMetricsImpl(new SimpleMeterRegistry()));
        ProxiedRequestLifecycle lifeCycle = new ProxiedRequestLifecycle(req, metrics).receivedRequest();

        try {
            lifeCycle.failRequest();
            fail("where is NPE?");
        } catch (Exception e) {
            assertThat(e, instanceOf(NullPointerException.class));
        }
        verify(metrics).failRequest(null, null);

        lifeCycle.setBackendName("name");
        lifeCycle.failRequest();
        verify(metrics).failRequest("name", null);
    }
}
