package com.otr.portal.hosts;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pobedenniy.alexey
 * @since 26.11.17
 */
public class SelfEntry {
    private static final Logger log = LoggerFactory.getLogger(SelfEntry.class);
    protected final String entry;

    public SelfEntry(@Nullable String scheme, String domainName, @Nullable Integer port) {
        this.entry = (scheme != null ? scheme : "http://") +
                domainName +
                (port != null ? ":" + port : "");
    }

    public static SelfEntry from(String host, int localPort, boolean localEntryPoint) {
        if (host == null) {
            return null;
        }
        Matcher hostNameMatcher = Pattern.compile("(http://|https://)?([\\w\\.-]+):?(\\d[1, 5])?").matcher(host);
        if (!hostNameMatcher.matches()) {
            log.error("Неверно задан хост Портала {}", host);
            return null;
        }
        String scheme = hostNameMatcher.group(1);
        String domainName = hostNameMatcher.group(2);
        String port = hostNameMatcher.group(3);
        boolean hasScheme = StringUtils.isNotBlank(scheme);
        boolean hasPort = StringUtils.isNotBlank(port);
        return localEntryPoint ? new SelfEntry(hasScheme ? scheme : null, domainName, localPort) : new ExternalSelfEntry(hasScheme ? scheme : null, domainName, hasPort ? Integer.parseInt(port) : null);
    }

    private static class ExternalSelfEntry extends SelfEntry {
        ExternalSelfEntry(String scheme, String domainName, Integer port) {
            super(scheme, domainName, port);
        }
    }

    @Override
    public String toString() {
        return entry;
    }
}
