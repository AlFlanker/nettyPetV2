# Повторная оценка по пяти требованиям

Ревью выполнили четыре роли: специалист по Jetty, специалист по Netty,
специалист по Spring и технический лид. Независимые отчёты сохранены в
[review/reassessment](reassessment/README.md). Они относятся к моменту
обнаружения замечаний; таблицы ниже отражают последующие исправления.

Проверяется самостоятельный pet на Spring MVC + Jetty + Servlet API + **прямом
io.netty**. Исходный модуль `backends` остаётся примером: он не изменялся
и не собирался. Полноценный портал, HTTP/2, connection pool/reuse и дополнительные
оптимизации ответа не являются условиями выполнения этой задачи.

## Результат по требованиям

| Требование | Оценка | Основание |
|---|---|---|
| 1. Request передаётся потоком либо через ограниченные буферы | Выполнено для заявленного HTTP/1.1-контракта | Один request head и последовательность HttpContent. Порция читается прямо в heap ByteBuf фиксированного размера; следующая ждёт writable и завершения предыдущей записи. Нет FullHttpRequest, body aggregator или form/multipart encoder. Проверены ранняя доставка до EOF и остановка read-ahead при медленном backend. |
| 2. Поддерживаются основные HTTP-методы | Основная матрица выполнена | POST/PUT/PATCH/DELETE/GET/HEAD используют единый request pump; GET/DELETE/HEAD проверены с известной и неизвестной длиной. Отдельный OPTIONS smoke также прошёл: проверены запросы с body и без body, метод, status, Allow и X-Options-Backend. |
| 3. Память используется рационально | Основная цель выполнена: размер body не определяет прикладной буфер | Ограничены размер чанка, число незавершённых записей, socket read/decoder и очередь response. Проверяется refCnt при cancel/overflow/close. Это доказательство bounded streaming, а не минимального RSS, отсутствия всех копий или абсолютной оптимальности allocator. |
| 4. Основные сценарии покрыты тестами | Выполнено; итоговый набор и контейнерный lifecycle проверены | Есть bytes/query, form/multipart, framing, headers, gzip, Expect, early413, deadlines, abort, TLS/DNS, ownership и backpressure. Ревью обнаружило пробел контейнерного завершения, который не ловили собственные счётчики; исправление проверено отдельной регрессией и полным повторным прогоном из 41 теста. |
| 5. Достаточно текущей передачи response, близкой к исходному примеру | Выполнено в согласованном объёме | Прямой Netty handler, manual demand, ограниченная очередь, один servlet response owner, сохранение status/headers/body и правильный abort после commit. Bounded OutputStream baseline достаточен; замена на direct ByteBuffer writer не требуется. |

## Найденные замечания и план завершения

### A-01 — P1: закрытие сокета не завершало AsyncContext

**Статус: исправлено и проверено полным прогоном.**

В прежнем `ProxyCommand` endpoint abort и событие `onError` считались основанием
не вызывать `async.complete()`. Однако они не равны `onComplete`. В полном
прогоне все 38 тестов проходили, после чего четыре контекста Spring/Jetty ожидали
`webServerGracefulShutdown` по 30 с с активными requests. Собственные transport
counters при этом уже были нулевыми. Это подтверждённый lifecycle-дефект,
а не гипотеза о Netty ByteBuf leak.

Исправление разделяет завершение servlet (`servletCompleted`, только из
`onComplete`) и ошибку/обрыв транспорта. Владелец обмена после остановки upload,
закрытия backend channel и очистки очереди завершает AsyncContext также после
abort. Повторный fallback и конкурентная запись ответа не добавлены.

Код: [ProxyCommand](../src/main/java/example/proxy/ProxyCommand.java), методы
`runExchange`, `onServletComplete`, `onServletError`, `abortServlet`.

Приёмка:

- timeout-after-commit, truncated upstream, client RST и blocked-first-write
  сохраняют корректный HTTP-обрыв без второго статуса/тела;
- завершается контейнерный AsyncContext, а не только возвращается semaphore;
- закрытие Spring/Jetty context не доходит до graceful-shutdown timeout;
- итоговый полный прогон сохраняет функциональные результаты.

### A-02 — P2: cleanup-тесты наблюдали транспорт, но не Jetty request lifecycle

**Статус: исправлено; ProxyServletCompletionTest прошёл.**

Нулевые `activeIoTasks`, `activeChannels`, `activeExchanges` и возвращённые permits
полезны, но недостаточны для утверждения «все ресурсы завершены». Именно поэтому
зелёный набор ранее пропустил A-01.

Добавленный [ProxyServletCompletionTest](../src/test/java/example/proxy/ProxyServletCompletionTest.java)
получает prefix ответа, проверяет обрыв после deadline, ждёт настоящий
`AsyncListener.onComplete` и закрывает реальный ApplicationContext быстрее
настроенного graceful-shutdown timeout. Тест прошёл; shutdown-часть полного лога
проверена после JUnit PASS. Graceful shutdown не отключался, предупреждения не
подавлялись, срок ожидания для сокрытия ошибки не увеличивался.

### A-03 — P3: отдельный OPTIONS smoke

**Статус: выполнено; OPTIONS smoke прошёл.**

Обычный OPTIONS проходит через общий адаптер метода. Добавленный origin-form
OPTIONS smoke проверяет запросы с body и без body, исходные байты payload, method,
upstream status, Allow и X-Options-Backend. `OPTIONS *` проверять
отдельно только если заявляется поддержка этого request-target.

### A-04 — проверка медленного downstream

**Статус: выполнена целевым тестом и полным прогоном.**

[SlowDownstreamTest](../src/test/java/example/proxy/SlowDownstreamTest.java) останавливает
чтение клиентом, наблюдает остановку backend producer до EOF и затем делает RST.
Проверяются выход backend writer, завершение transport tasks/channels/exchange и
успешный следующий запрос. Он прошёл в целевом и полном прогонах после исправления A-01.
Тест измеряет прекращение продвижения, а не предполагает, что во всех TCP/Jetty/
Netty буферах вместе помещается ровно один чанк.

### A-05 — необязательные измерения памяти

**Статус: отложено; не условие завершения пяти требований.**

При отдельной задаче на количественную оптимизацию измерить heap/direct memory,
RSS, allocations и GC при одинаковых concurrency, размерах payload, TLS и slow
peers. Сейчас подтверждены ограниченность payload buffering и отдельные случаи
освобождения buffers; сравнительного benchmark альтернатив нет.

### A-06 — необязательное измерение flush/copy стоимости ответа

**Статус: отложено; текущий response baseline принят.**

Перед изменением flush policy или переходом на direct ByteBuffer writer сравнить
throughput и latency первой порции. Не менять рабочий backpressure/ownership
контракт ради непроверенного выигрыша. Необходимость HTTP/2, connection reuse,
epoll, HTML rewriting или полного portal feature set из этого замечания не следует.

### A-07 — необязательное усиление DNS deadline-теста

**Статус: дополнительная проверка, не найденный дефект.**

`DnsProgressTest` проверяет, что зависший DNS не блокирует event-loop deadline.
При дальнейшей доработке можно добавить end-to-end HTTP 504 во время такого lookup.
Это не требует нового DNS subsystem и не блокирует основной request-streaming
сценарий.

## Итоговая проверка

До исправления A-01: 38 тестов проходили, но shutdown-лог содержал четыре ожидания
по 30 с. Поэтому этот результат не считается окончательным подтверждением cleanup.

После исправления A-01 целевой прогон `ProxyLifecycleTest`, `RawBackendTest` и
`SlowDownstreamTest`: **11 тестов, 0 failures / 0 errors / 0 skipped**, около 20 с на
весь запуск; три затронутых контекста завершились без 30-секундных ожиданий.
Новые XML относятся к запуску 2026-11-02; предыдущие отчёты экспертов сохранены
без переписывания их исторических выводов.

**Итоговый полный прогон завершён успешно: 41 тест, 0 failures, 0 errors,
0 skipped.** Команда `test installDist` завершилась с `BUILD SUCCESSFUL in 30s`
и exit code 0. Сформирован запускаемый дистрибутив.

В набор вошли OPTIONS smoke, медленный downstream и регрессия реального servlet
completion. Все контексты завершили graceful shutdown без прежних сообщений
`requests still active` / `webServerGracefulShutdown` timeout. Сообщений Netty
`LEAK` в полном логе нет; этот факт дополняет, а не заменяет refCnt-тесты.

Доказательства в рабочей среде:

- JUnit XML: `build/test-results/test/`;
- HTML: `build/reports/tests/test/index.html`;
- сохранённый полный лог финального запуска: [build/review-validation.log](../build/review-validation.log);
- дистрибутив: `build/install/streaming-proxy-pet/`.

Свежий дистрибутив также проверен отдельным запуском proxy и demo backend:
POST формы (39 байт) и chunked DELETE (2 MiB) сохранили точные URI, Content-Type,
число байт и SHA-256. Оба запущенных для проверки процесса остановились менее
чем за 8 с; proxy завершил graceful shutdown без ожидания таймаута.

Обязательные замечания A-01/A-02 закрыты; дополнительные проверки A-03/A-04
выполнены. A-05/A-06/A-07 сохранены как необязательные направления дальнейшей
работы. Для выполнения согласованных пяти требований расширять объём задачи
этими оптимизациями не требуется.
