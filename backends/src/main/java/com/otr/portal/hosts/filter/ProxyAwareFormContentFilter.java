package com.otr.portal.hosts.filter;

import com.otr.portal.core.internal.ProxiedRequestMatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;

/**
 * {@link org.springframework.web.filter.FormContentFilter} для портала: формы POST/PUT/PATCH/DELETE разбираются
 * только у "локальных" запросов.
 * <p>
 * Исходный фильтр вычитывает тело в параметры, а {@code getInputStream()} обёртки отдаёт уже пустой поток.
 * Прокси передал бы бэкенду пустое тело, поэтому проксируемые запросы фильтр не трогает.
 *
 * @author alexraspopin
 * @since 13.10.2026
 */
public class ProxyAwareFormContentFilter extends OrderedFormContentFilter {
    private final ProxiedRequestMatcher proxiedRequests;

    public ProxyAwareFormContentFilter(ProxiedRequestMatcher proxiedRequests) {
        this.proxiedRequests = proxiedRequests;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return proxiedRequests.matches(request);
    }
}
