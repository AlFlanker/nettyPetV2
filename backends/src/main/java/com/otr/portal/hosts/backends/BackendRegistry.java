package com.otr.portal.hosts.backends;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;

/**
 * @author pobedenniy.alexey
 * @since 21.09.17
 */
public interface BackendRegistry {
    Backend lookupBackend(HttpServletRequest req);
}
