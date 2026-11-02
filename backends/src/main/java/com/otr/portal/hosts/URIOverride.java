package com.otr.portal.hosts;

import com.otr.portal.metadata.config.ApplicationProperties;
import org.springframework.util.AntPathMatcher;

/**
 * @author pobedenniy.alexey
 * @since 27.12.17
 */
public class URIOverride {
    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();
    @ApplicationProperties(name = "URI", description = "Задает URI, для которого действует данная настройка. Для задания URI можно использовать симолы подстановки /**. ", example = "/no-timeout/** /some-uri")
    private String uri;
    @ApplicationProperties(name = "Таймаут (мс)", description = "Переопределяет таймауты для этого URI. Таймаут задается в миллисекундах. Значение 0 или -1 обозначает отсутствие таймаута.", example = "30", defaultValue = "5000")
    private Integer timeout;
    @ApplicationProperties(name = "Запрещать доступ", description = "Запрещает доступ к этому URI. Клиент получит 403 ошибку")
    private boolean forbid;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public boolean isForbid() {
        return forbid;
    }

    public void setForbid(boolean forbid) {
        this.forbid = forbid;
    }

    public boolean matches(String uri) {
        return ANT_PATH_MATCHER.match(this.uri, uri);
    }
}
