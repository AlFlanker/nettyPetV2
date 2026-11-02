package com.otr.portal.proxy;

import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Date;

import static org.junit.Assert.fail;

/**
 * @author pobedenniy.alexey
 * @since 12.10.16
 */
public class TestHelper {

    public static void waitForAsyncResponse(MockHttpServletRequest request) {
        Date start = new Date();
        while (request.isAsyncStarted()) {
            Thread.yield();
            //timeout after 5 sec
            if (new Date().getTime() - start.getTime() > 5000) {
                fail();
            }
        }
    }
}
