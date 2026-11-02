package com.otr.portal.hosts;

import com.otr.portal.metadata.ApplicationContentProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Collections.emptyMap;

/**
 * @author pobedenniy.alexey
 * @since 09.10.17
 */
public class HostsConfigurationProvider implements ApplicationContentProvider {
    @Autowired
    HostsService hostsService;

    @Override
    public Map<String, Supplier> provideTemplateVars(HttpServletRequest request, HttpServletResponse response) {
        return Collections.singletonMap("hosts", () -> hostsService);
    }

    @Override
    public Map<String, Supplier> provideAdminTemplateVars(HttpServletRequest request) {
        return emptyMap();
    }
}
