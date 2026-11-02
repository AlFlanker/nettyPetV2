package com.otr.portal.proxy.memory;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Разбор тела {@code application/x-www-form-urlencoded} для проверки пересобранных форм.
 */
public final class UrlEncodedForm {
    private UrlEncodedForm() {
    }

    /**
     * Имя параметра → значения в порядке следования; повторяющиеся имена собираются в один список.
     * Формы сравниваются разобранными, а не строкой: encoder может кодировать символы иначе, чем клиент.
     */
    public static Map<String, List<String>> parse(String body) {
        Map<String, List<String>> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            form.computeIfAbsent(URLDecoder.decode(name, UTF_8), key -> new ArrayList<>())
                    .add(URLDecoder.decode(value, UTF_8));
        }
        return form;
    }
}
