# Экспертное ревью Spring

Исходный модуль не собирался, файлы проектов не изменялись. Выполнены два локальных runtime-эксперимента с подготовленным дистрибутивом; диагностический код находится только в `/tmp`.

## S1 — P1: ERROR-dispatch может превратить отказ 503 в успешный проксированный запрос `/error`

**Статус: подтверждённый дефект.**

Источники:

- `streaming-proxy-pet/src/main/java/example/proxy/ProxyApplication.java:23` — регистрация `ProxyServlet` на `/*`.
- `streaming-proxy-pet/src/main/java/example/proxy/ProxyServlet.java:33` — проверка semaphore.
- `streaming-proxy-pet/src/main/java/example/proxy/ProxyServlet.java:35` — `sendError(503, ...)`.
- `streaming-proxy-pet/src/main/java/example/proxy/ProxyServlet.java:24` — отсутствует отдельная обработка `DispatcherType.ERROR`.

Boot регистрирует страницу ошибок `/error`; servlet mapping `/*` перехватывает её раньше DispatcherServlet с mapping `/`. Если после отказа 503, но до обработки ERROR-dispatch освобождается слот, `ProxyServlet` принимает ошибочную диспетчеризацию как новый proxy exchange. Запрос уходит на backend как `/error`, а backend-статус заменяет локальный отказ.

Доказательства:

- При стабильной перегрузке: 32 удерживаемых запроса, 33-й получает 503, backend `/error` не получает.
- При контролируемом освобождении настоящих backend-запросов между `sendError` и ERROR-dispatch: 33-й запрос получает **200, тело `backend:/error`**. Backend действительно получает `/error`.
- Диагностический фильтр только управлял моментом завершения удерживаемых backend-запросов, не менял semaphore или код прокси.
- `/tmp/review-spring-dispatch.log`: `PROBE ERROR uri=/error status=503`, затем `permits=5`.
- Воспроизводящий код: `/tmp/review-spring-dispatch.py`, `/tmp/probe-spring/probe/Probe.java`.
- Обычный Upgrade-запрос возвращает 501. Однако он также попадает в ERROR-dispatch `/error` и повторную ветку отказа; это сопутствующий недостаток той же схемы, а не отдельный доказанный обход.

План:

1. Локальные 501/503 формировать через `setStatus` и явное завершение ответа без запуска Boot error-page dispatch.
2. Явно запретить создание upstream-запросов из `DispatcherType.ERROR`, сохранив предусмотренную обработку ASYNC-dispatch поздней ошибки.
3. Проверить поведение `/error` и других локальных MVC endpoints; описать, что `/*` сейчас перехватывает их.
4. Добавить регрессионный тест с освобождением слота перед ERROR-dispatch: результат обязан остаться 503, backend не должен получить ни исходный отвергнутый запрос, ни `/error`.
5. Добавить проверки 501/503 и сохранения всех permits после отказа.

## S2 — P3: тестовый classpath содержит второй servlet container

**Статус: подтверждённое расхождение окружений теста и запуска, улучшение сопровождения.**

Источники:

- `streaming-proxy-pet/build.gradle:19` — `spring-boot-starter-webmvc-test` без исключения Tomcat.
- `streaming-proxy-pet/gradle.lockfile:57` — `tomcat-embed-core` только на test classpath.
- `streaming-proxy-pet/gradle.lockfile:59` — `tomcat-embed-websocket` только на test classpath.
- `streaming-proxy-pet/gradle.lockfile:135`, `:136`, `:142` — Tomcat starter/runtime/autoconfiguration.

Последние восемь тестов **действительно запускались на Jetty**, что подтверждает XML-лог `JettyServletWebServerFactory` и Jetty 12.1.4. Ошибочно утверждать, что тесты уже проверили другой контейнер. Проблема — лишний контейнер и автоконфигурация в тестах, которых нет в обычном runtime; это усложняет сопровождение и может менять выбор после изменения зависимостей.

План:

1. Исключить Tomcat starter/runtime из соответствующего тестового пути зависимостей.
2. Обновить lockfile.
3. Явно проверять в integration context, что используется Jetty web-server factory.
4. Критерий: test runtime не содержит Tomcat server jars, обычный runtime и тесты используют один контейнер. `tomcat-embed-el` сам по себе не считать вторым servlet container: он может понадобиться как EL implementation.

## S3 — P2: сетевые лимиты и deadlines нельзя задавать через Spring configuration

**Статус: улучшение конфигурации и проверяемости, не доказанный дефект текущих defaults.**

Источники:

- `streaming-proxy-pet/src/main/java/example/proxy/ProxyTransport.java:17` — pool size/queue/acquire timeout.
- Там же `:22` — отдельно захардкоженные 32 permits.
- Там же `:24`, `:25` — connect/read timeouts.
- `streaming-proxy-pet/src/main/java/example/proxy/ProxyCommand.java:77` — общий timeout 60 секунд.
- `streaming-proxy-pet/src/main/java/example/proxy/ProxyApplication.java:17` — через Spring настраивается только backend URL.
- `streaming-proxy-pet/src/main/resources/application.yml:15`.

Ограничение уже частично раскрыто в README. Большой медленный upload требует изменения Java-кода, а тестирование timeout-веток без параметризации долгое. Дублирование размера пула и admission limit создаёт возможность рассогласования.

План:

1. Ввести небольшой `@ConfigurationProperties("proxy")` с backend origin, admission limit, pool limit и отдельными connect/acquire/read/whole-exchange timeout.
2. Валидировать значения при старте, включая допустимый порт origin и положительные лимиты.
3. Определить связь pool capacity и admission limit либо явно документировать их независимость.
4. Настраивать короткие deadlines только в тестовом профиле.
5. Приёмка: invalid configuration не запускает сервер; slow upload/slow upstream/rejected pool acquisition имеют заданный результат и корректно завершают exchange без изменения исходников.

## S4 — P3: глобальное отключение body-parsing не переносится напрямую в смешанное MVC-приложение

**Статус: улучшение документации и примера интеграции; в изолированном pet это не ошибка.**

Источники:

- `streaming-proxy-pet/src/main/resources/application.yml:8` — глобально выключен multipart.
- Там же `:11` — глобально выключен FormContentFilter.
- `streaming-proxy-pet/README.md:60`.
- Исходный `nettyPetV2/backends/src/main/java/com/otr/portal/proxy/ProxySpringConfiguration.java:42` — сначала определяется backend.
- Там же `:54`, `:63` — route-aware matcher и собственный FormContentFilter.
- `nettyPetV2/backends/src/main/java/com/otr/portal/hosts/filter/ProxyAwareFormContentFilter.java:26` — пропуск только проксируемых запросов.

Текущие Boot properties корректны; проверено metadata Boot 4.0.0. Перенос глобального отключения в портал отключит обработку локальных форм/multipart, а mapping `/*` дополнительно сделает локальные MVC endpoints недоступными через обычную маршрутизацию.

План:

1. Добавить в README короткий раздел «Перенос в исходный стек»: чистый входной поток обеспечивается до proxy, выбор маршрута выполняется до consuming filters.
2. Сослаться на уже существующий в исходном примере `ProxyAwareFormContentFilter` как на подход для смешанного приложения.
3. Если демонстрируется смешанный вариант, ограничить proxy route и сохранить локальные form/multipart обработчики.
4. Приёмка для такого варианта: proxy form/multipart сохраняются побайтно, локальный MVC endpoint продолжает корректно получать разобранные параметры/файлы.
5. Не расширять текущую реализацию до всего портала: документационного пункта достаточно, пока смешанный вариант не входит в задачу.

## Lifecycle: граница выводов

Новых доказанных Spring-specific дефектов сверх S1 не установлено. Boot graceful shutdown и `destroyMethod="close"` присутствуют. Проверку shutdown при активных upload/download стоит включить в общий lifecycle-план Jetty/Netty; выдавать отсутствие такой проверки за уже подтверждённую утечку нельзя.
