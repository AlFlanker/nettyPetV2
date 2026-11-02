package com.otr.portal.hosts.filter;

import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.proxy.RouteType;
import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Классифицирует запрос по назначению</br>
 * <b>В настоящий момент применяется ограниченно</b></br>
 * ToDo:<b>Локальность проверять еще и по Http-header Referer?</b>
 *
 * @author alexraspopin
 * @since 27.05.2026
 */
class RouteClassifier {
    private static final String API_PREFIX = "/api/v";
    private static final String ADMIN_PREFIX = "/admin";
    private static final String FAVICON_PATH = "/favicon.ico";

    private final Pattern localApiPattern;
    private final Pattern localAdminPattern;

    RouteClassifier(PathHelper pathHelper) {
        this.localApiPattern = Pattern.compile(
                "^" + Pattern.quote(pathHelper.concatAndNormalizePath(API_PREFIX)) + "\\d+(?:/|$)"
        );

        this.localAdminPattern = Pattern.compile(
                "^" + Pattern.quote(pathHelper.concatAndNormalizePath(ADMIN_PREFIX)) + "(?:/|$)"
        );
    }

    RouteType resolve(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (isLocalApi(uri)) {
            return RouteType.LOCAL;
        }

        if (isLocalAdmin(uri)) {
            return RouteType.LOCAL;
        }

        if (isFavicon(uri)) {
            return RouteType.LOCAL;
        }

        return RouteType.UNKNOWN;
    }

    private boolean isLocalApi(String uri) {
        return this.localApiPattern
                .matcher(uri)
                .lookingAt();
    }

    private boolean isLocalAdmin(String uri) {
        return this.localAdminPattern
                .matcher(uri)
                .lookingAt();
    }

    private boolean isFavicon(String uri) {
        return FAVICON_PATH.equals(uri);
    }
}
