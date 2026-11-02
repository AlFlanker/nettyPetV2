# Повторное ревью: Spring / direct io.netty

> **Обновление после итоговой валидации, 2026-11-02.** Найденный ниже
> AsyncContext blocker исправлен: socket abort/onError отделены от настоящего
> servlet completion. Полный `test installDist` прошёл: **41 тест, 0 ошибок,
> 0 пропусков**, сборка за 30 с. Реальный AsyncListener/context-close regression,
> OPTIONS и SlowDownstreamTest прошли; graceful shutdown всех контекстов завершился
> без прежних 30-секундных ожиданий. Ниже сохранён исходный анализ на момент ревью;
> его старые статусы blocker/38 тестов/неисполненный тест не описывают итоговую
> версию. Актуальный сводный результат — [ASSESSMENT.md](../ASSESSMENT.md).

Проверена текущая реализация `/workspace/streaming-proxy-pet`: main, configuration, build/lockfile, README, тесты; исходный `backends` использован только как reference. Код не изменялся, Gradle не запускался. Результаты выполнения финального набора тестов принадлежат основному агенту; отдельно прочитан `/tmp/direct-netty-final.log`.

## Вердикт по пяти критериям

| Критерий | Оценка | Основание и предел вывода |
|---|---|---|
| 1. Request всегда streaming / bounded | По коду выполнено для заявленного HTTP/1.1 контракта | `ProxyCommand.java:209` — единый цикл чтения вне зависимости от метода; `:221` — фиксированный ByteBuf; `:235` — ожидание завершения предыдущей записи до следующего чтения. `ProxyCommandSupport.java:22` создаёт только head. Скрытого Reactor Netty агрегатора больше нет. Это не абсолютный zero-copy и не обещание для не реализованного HTTP/2. |
| 2. Основные HTTP-методы | Основная матрица поддержана и покрыта тестами | `ProxyCommandSupport.java:25` передаёт метод без методозависимого body parser; POST/PUT/PATCH и GET/DELETE/HEAD покрыты. `ProxyIntegrationTest.java:277` и `:285` проверяют GET/DELETE/HEAD с известной и неизвестной длиной и gate до EOF; `:331` — HEAD/204/304 без response body. OPTIONS не имеет выделенного теста — небольшой test gap, не найденный дефект. |
| 3. Память и ресурсы | Bounded payload подтверждается структурой; завершение всех контейнерных ресурсов пока нельзя принять полностью | Потоковые чанки, ограниченная очередь, bounded read-ahead и refCnt тесты заменили прежнее доказательство только на POST. Но четыре Spring/Jetty context shutdown в final log ожидают активные запросы по 30 секунд при нулевых custom counters. До исправления и повторной проверки контейнерного lifecycle полное утверждение об отсутствии накопления ресурсов недоказано. |
| 4. Основные случаи покрыты тестами | Существенно улучшено; остался важный gap shutdown настоящего Spring context | Есть raw form/multipart, body methods, headers/statuses, gzip, Expect, backpressure, deadlines, early413, abort, TLS/DNS и buffer ownership. `ProxyShutdownTest.java:59` вызывает непосредственно `transport.close()`, а не закрывает ApplicationContext. Это не ловит подтверждённое зависание `webServerGracefulShutdown`. |
| 5. Ответы достаточны для примера | HTTP-поведение достаточно при условии устранения контейнерного lifecycle blocker | Один response owner, raw HttpContent, HEAD/no-body responses, до/после commit, ранний final response и chunked framing представлены явно. Дополнительные production-функции не требуются. CONNECT/Upgrade/trailers/HTTP2 явно вне контракта README. |

## Проверка прежних Spring-находок

### S1 / 503 → ERROR `/error`: исправлено по коду и регрессионному тесту

- `ProxyServlet.java:21` отдельно обрабатывает ERROR-dispatch, не создавая upstream exchange.
- `ProxyServlet.java:63` формирует локальный отказ через `setStatus`, `Content-Length: 0` и `Connection: close`; `sendError` отсутствует.
- `ProxyLifecycleTest.java:88` проверяет 503 при исчерпанной capacity, отсутствие ERROR-dispatch и отсутствие запросов `/error`/отвергнутого URI на backend.
- `ProxyLifecycleTest.java:108` проверяет аналогичный контракт для Upgrade/501.
- Mapping `/*` сохраняется осознанно для выделенного pet; README `:177` объясняет ограничения переноса в смешанное MVC-приложение.

### S2 / Tomcat на тестовом classpath: исправлено

- В `build.gradle` исключён Tomcat также из `spring-boot-starter-webmvc-test`.
- В lockfile остаётся `tomcat-embed-el`, а не Tomcat server; это допустимая EL implementation.
- `ProxyIntegrationTest.java:398` проверяет наличие ровно одной Jetty factory и отсутствие классов Tomcat server/Reactor Netty HttpClient.

### S3 / свойства и deadlines: исправлено для pet scope

- `ProxyApplication.java:11` включает `ProxyProperties`.
- `ProxyProperties.java:10` — типизированный record с default values, origin/port/limit/duration validation до создания transport.
- `application.yml` и README `:154` описывают настраиваемые лимиты и сроки.
- `ProxyPropertiesTest.java:16` проверяет invalid configuration, `:25` — defaults/overrides через реальный Spring binder.

### S4 / raw-body и перенос в исходный стек: исправлено документированием

- Multipart и FormContentFilter остаются отключёнными для выделенного proxy servlet.
- `README.md:177` прямо отделяет standalone вариант от смешанного портала и ссылается на исходный `ProxyAwareFormContentFilter`.
- Глобальное отключение не предлагается переносить в локальные MVC endpoints.

## TLS и DNS

- `ProxyTransport.java:62` использует стандартную цепочку доверия; `:89` включает hostname verification `HTTPS`; `:91` ограничивает handshake.
- `ProxyTransportTlsTest.java:94` проверяет отказ неизвестному сертификату; `:106` — trusted/matching hostname и wrong hostname; `:148` запускает полный production pipeline с Jetty и HTTPS upstream.
- `ProxyTransport.java:69` использует asynchronous Netty DNS resolver; он закрывается в `:120`, event loops в `:121`.
- `DnsProgressTest.java:21` подтверждает, что ожидание DNS не блокирует scheduled task event loop. Это проверка прогресса event loop, а не отдельный end-to-end тест HTTP 504 при DNS stall.
- Нового подтверждённого Spring/TLS/DNS дефекта при read-only проверке не найдено.

## Подтверждённый незакрытый blocker R-S1: контейнерный async lifecycle

**Приоритет P1.** Принимать текущие custom counters за полное завершение Jetty/Spring requests нельзя.

Доказательства: `/tmp/direct-netty-final.log:95`, `:102`, `:109`, `:116` — четыре сообщения:

`Shutdown phase 2147482623 ends with 1 bean still running after timeout of 30000ms: [webServerGracefulShutdown]`.

Смежные сообщения сообщают `Graceful shutdown aborted with one or more requests still active`. При этом тесты прошли и проверяли нулевые counters transport.

Подозреваемый участок (диагноз уточняет Jetty-эксперт):

- `ProxyCommand.java:175` пропускает `async.complete()` при `servletAborted` или `servletEnded`.
- `ProxyCommand.java:315` считает `onError` окончанием servlet exchange наравне с `onComplete`.
- `ProxyCommand.java:343` делает hard endpoint close; физическое закрытие сокета не доказывает завершение контейнерного AsyncContext.

Это подтверждение проблемы полного lifecycle по runtime evidence, но не доказательство точного количества retained bytes или конкретной первопричины в одной строке.

План приёмки:

1. Согласовать hard connection abort с обязательным завершением Jetty AsyncContext после остановки рабочих потоков; отличать onError от onComplete.
2. Добавить проверку фактического завершения контейнерных requests, а не только semaphore/exchanges/ioTasks/channels.
3. Закрыть настоящий `ConfigurableApplicationContext` после timeout-after-commit, client abort и truncated response; операция должна завершаться в заданный короткий срок без `webServerGracefulShutdown` timeout и без оставшихся requests.
4. Повторить общий suite и проверить shutdown-часть лога после PASS, а не только JUnit counters.
5. Не устранять симптом отключением graceful shutdown, увеличением timeout или подавлением логов.

Spring-specific замечание: `@Bean(destroyMethod="close")` выполняется позже lifecycle-фазы `webServerGracefulShutdown`. Поэтому тест с прямым `transport.close()` не эквивалентен остановке приложения. Для shutdown при ещё живом upload отдельно определить допустимую политику: дождаться обмена в пределах grace либо отменить его; для уже завершённых/прерванных exchanges долгого ожидания быть не должно.

## Неблокирующие gaps / необязательные улучшения

- P3: отдельный OPTIONS smoke/integration test (обычный origin-form и, если заявляется, `OPTIONS *`). Общий transport не содержит специального ограничения метода; пока это отсутствие покрытия, не подтверждённая поломка.
- P3: энд-ту-энд тест общего deadline во время DNS stall дополнительно к существующему тесту event-loop progress. Отдельный обязательный production DNS subsystem не нужен.
- При принятии критерия памяти говорить о bounded payload и проверенной очистке после устранения R-S1, а не количественно гарантировать RSS: pooled allocators/Jetty/TLS/OS имеют собственные буферы. README уже корректно оговаривает это.

Итог: прежние Spring-дефекты и замечания закрыты; основная структура соответствует пяти целям pet. Финальный безусловный verdict следует отложить до устранения и проверки наблюдаемого контейнерного async shutdown blocker. Дополнительные enterprise-функции для этого не требуются.
