package com.otr.portal.hosts;

import com.otr.portal.metadata.config.ApplicationProperties;
import org.springframework.util.AntPathMatcher;

/**
 * @author pobedenniy.alexey
 * @since 23.04.18
 */
public class URICache {
    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();
    @ApplicationProperties(name = "URI", description = "Задает URI, для которого действует данная настройка. Для задания URI можно использовать симолы подстановки /**. ", example = "/**/*.gif /some-cached-uri", defaultValue = "")
    private String uri;

    @ApplicationProperties(name = "max-age", description = "Задает параметр max-age в минутах, для ресурсов которые могут быть закешированы в браузере. Определяет время жизни кеша ресурса в браузере. Пустое значение означает оставить параметр max-age без изменения.", example = "6000", defaultValue = "")
    private Integer maxAge;


    public boolean matches(String requestURI) {
        return ANT_PATH_MATCHER.match(this.uri, requestURI);
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Integer getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Integer maxAge) {
        this.maxAge = maxAge;
    }
}
