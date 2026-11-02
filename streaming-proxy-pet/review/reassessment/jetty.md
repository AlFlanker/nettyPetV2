# Итоговое повторное ревью Jetty / Servlet

**Текущая реализация соответствует пяти критериям в согласованном HTTP/1.1 pet-scope. Блокирующих Jetty/Servlet замечаний после исправления J-R1 не осталось.**

Исходный `nettyPetV2/backends` использовался только как reference, не менялся и не собирался. Собственных конкурентных Gradle запусков не было. Проверены текущие source/tests/README/IMPLEMENTATION_STATUS и результаты финального запуска root.

## Окончательный вердикт

| Критерий | Вердикт | Проверяемое основание |
|---|---|---|
| 1. Request всегда streaming или bounded buffering | Соответствует заявленному HTTP/1.1 | `ProxyCommand.java:209–247`: отдельный head, фиксированный heap ByteBuf, чтение в backing array, одна write future до следующего read; общего накопления payload нет. |
| 2. Основные HTTP-методы | Соответствует | `ProxyCommandSupport.java:22–45`: исходный метод и единый framing, sendRequest не имеет агрегирующей ветки по методу. Проверены POST/PUT/PATCH/GET/DELETE/HEAD и OPTIONS с телом/без тела, в том числе known/unknown length и HEAD request body. |
| 3. Оптимальная память в согласованном смысле bounded buffers + корректное освобождение | Соответствует | Ограничены request chunk/response queue; ByteBuf ownership явно передаётся/освобождается. `awaitUploadExit` и закрытие channel предшествуют releaseCapacity. Устранён найденный async-lifecycle leak: теперь проверяется также реальный Servlet onComplete. Это не утверждение абсолютного минимума RSS или копий. |
| 4. Основные сценарии покрыты тестами | Соответствует | Upload demand, stalled backend/reader, методы/framing, early413, 502/504, first-write race, abort и shutdown. Новый `ProxyServletCompletionTest.java:36–108` проверяет реальный onComplete и закрытие Spring/Jetty контекста до graceful deadline. |
| 5. Response как в примере достаточен, новых оптимизаций не требовать | Соответствует | Один response-worker владеет status/headers/body/fallback; error после commit закрывает HTTP/1.1 endpoint. Bounded OutputStream baseline и освобождение HttpContent достаточны. P-08/P-09, ByteBuffer writer/input и flush benchmark не являются условиями приёмки. |

## Исправленный в этом ревью блокер J-R1

До исправления `servletAborted`/`onError` ошибочно позволяли пропустить `AsyncContext.complete()`. При зелёных транспортных счётчиках четыре Jetty контекста доходили до graceful timeout30s.

Текущее исправление проверено:
- `ProxyCommand.java:57` — отдельный `servletCompleted`.
- `ProxyCommand.java:313–315` — он устанавливается только в onComplete.
- `ProxyCommand.java:176–184` — после остановки upload/channel владелец завершает AsyncContext даже после endpoint abort/onError, если actual completion ещё не произошёл.
- `servletEnded` остаётся отдельным запретом новых response writes, поэтому не возвращается гонка fallback/таймер.

Регрессия не подменяет lifecycle собственным счётчиком: `ProxyServletCompletionTest` сначала наблюдает реальный listener onComplete (`:86–89`), затем transport cleanup (`:90–96`), затем закрытие реального контекста менее2s при graceful deadline3s (`:97–102`). Backend gated до регистрации listener, поэтому результат не зависит от гонки его подключения.

## Финальные результаты

Проверены `/tmp/proxy-reviewed-final.log` и свежие JUnit XML:
- `test installDist`: BUILD SUCCESSFUL in 30s.
- **41 tests, 0 failures, 0 errors, 0 skipped.**
- `ProxyServletCompletionTest` прошёл.
- Все контексты завершили graceful shutdown сразу; прежних timeout/abort сообщений нет.

Сфокусированный предварительный запуск `/tmp/proxy-async-focused.log` также прошёл: 11 tests и 3 немедленных graceful completion. Отдельные новые оптимизации response в ходе повторного ревью не требовались.

## Остаточные ограничения — не blockers этих критериев

Документированный scope остаётся HTTP/1.1, без HTTP/2 stream abort, connection reuse и полного portal feature set. Блокирующий bounded bridge допустим; абсолютный zero-copy/минимум копий не заявляется. Отсутствие сравнительного benchmark не выдаётся за обнаруженный дефект; OPTIONS уже добавлен в финальную матрицу проверок. Требовать новые performance-эксперименты для завершения текущей задачи оснований нет.

---

## История диагностики до исправления

Проверены текущие прямой `io.netty` transport, servlet lifecycle, tests, README и IMPLEMENTATION_STATUS. Исходный `nettyPetV2/backends` использовался только как reference и не собирался. Gradle не запускался параллельно root. Результат ниже относится к просмотренному состоянию, где `ProxyCommand.java:175` пропускает `async.complete()` при servletAborted/servletEnded.

## Вердикт по пяти критериям

| Критерий | Вердикт | Основание |
|---|---|---|
| 1. Request всегда streaming или bounded buffering | Соответствует для заявленного HTTP/1.1 | `ProxyCommand:209–247`: head отдельно; один heap ByteBuf заданного chunkSize, read напрямую в backing array, write future до следующего read. Ни form-парсинга, ни FullHttpRequest/агрегатора. |
| 2. Основные HTTP-методы | Соответствует | `ProxyCommandSupport:22–45` использует исходный метод и единый framing; общий sendRequest не ветвится по POST/GET. Tests `ProxyIntegrationTest:79,106,277,285,331` охватывают POST/PUT/PATCH/GET/DELETE/HEAD и ответы без тела. OPTIONS проходит тем же общим путём; отсутствие отдельного OPTIONS-теста само по себе не дефект. |
| 3. Память оптимальна в согласованном смысле: ограниченность, отсутствие полного накопления, корректная очистка | Частично до исправления подтверждённого async-lifecycle blocker | Прикладные request/response buffers и очередь ограничены; задачи/каналы учитываются и awaitUploadExit предотвращает преждевременное возвращение permit. Но graceful shutdown показывает незавершённые servlet requests, которых эти счётчики не учитывают. Абсолютный минимум копий не требовался и не доказан; этот факт не является blocker. |
| 4. Основные сценарии покрыты тестами | Соответствует по ширине функциональных сценариев, частично по критерию полного завершения | Реальные тесты upload demand, early413, deadlines, first-write race, client abort, slow reader и shutdown. Однако собственные activeIo/Channels/Exchanges counters дают ложную уверенность в полном завершении Servlet lifecycle: полный run зелёный, затем четыре контекста ждут graceful timeout. Нужен контейнерный completion criterion. |
| 5. Response как в исходном примере достаточно; новые оптимизации не нужны | Соответствует по streaming/ownership/HTTP поведению; общий lifecycle blocker остаётся | Один response-worker пишет headers/body/fallback (`ProxyCommand:113–164,286–341`); timeout не пишет второй fallback. Bounded OutputStream baseline допустим. P-08/P-09, direct ByteBuffer writer и flush benchmark не являются требованиями этого повторного ревью. |

## Блокер J-R1 — завершение socket ошибочно приравнено к завершению Servlet AsyncContext

**Подтверждено кодом и результатом полного запуска, не предположение из отсутствия теста.**

Места:
- `ProxyCommand.java:175–182`: finally не вызывает async.complete(), если servletAborted или servletEnded.
- `ProxyCommand.java:343–350`: servletAborted означает raw HTTP/1.1 EndPoint.close(error), а не completed AsyncContext.
- `ProxyCommand.java:315–318`: onServletError выставляет servletEnded, хотя onError не равен onComplete.
- `ProxyCommand.java:372–375`: exchange удалён, permit возвращён независимо от контейнерного onComplete.
- `ProxyShutdownTest.java:49–73` проверяет транспортный close и собственные счётчики, но не полное закрытие Spring/Jetty контекста после оборванного response.

Факт: `/tmp/direct-netty-final.log:95,102,109,116` — четыре сообщения `webServerGracefulShutdown` still running after30000ms; рядом graceful shutdown aborted with requests still active. 38 успешных тестов не отменяют этот дефект очистки.

Механизм: по bytecode Jetty12.1.4 `ServletChannel.onCompleted()` вызывает core Callback.succeeded/failed после `state.completeResponse()`. Закрытие endpoint прекращает transport, но не заменяет выполнение async completion state machine. Нынешняя ветка finally специально пропускает эту часть именно в abort/error случаях.

Исправление в текущем scope:
1. После остановки upload/channel и освобождения очереди владелец должен завершить AsyncContext, включая путь raw endpoint abort.
2. Минимальный вариант — всегда попытаться async.complete() и обработать IllegalStateException, если container уже завершил exchange. Более явный — отдельный `servletCompleted`, выставляемый **только onComplete**, и независимые flags ошибки/transport abort для запрета fallback.
3. Не возвращать fallback после raw abort; сохранять текущий first-write ownership contract.
4. Добавить критерий контейнерного onComplete/active request count для abort/timeout/client RST и тест закрытия реального Spring/Jetty контекста за короткий срок. Проверить полный shutdown log, а не только JUnit status.

Приёмка: full suite зелёный; first-write barrier по-прежнему получает обрыв без второго статуса/reset; клиентский/late-upstream abort вызывает контейнерное завершение; закрытие всех затронутых контекстов не доходит до graceful timeout.

## Что действительно исправлено по сравнению с прошлым ревью

- Скрытой Reactor Netty method aggregation в новом request пути нет.
- response headers/status/body/fallback имеют одного владельца; тест `ResponseOwnershipRaceTest:81–107` проверяет окно перед первым commit и реальное продолжение заблокированной записи после interrupt.
- `awaitUploadExit` и response-worker lifetime предотвращают возврат permit, пока task ещё выполняется.
- Early413, response truncation/chunked EOF, client abort, backpressure и реальная остановка транспортных задач проверяются напрямую, а не только PID/port.
- Local rejection не вызывает sendError/повторный proxy `/error`.

## Необязательное / за пределами текущих пяти критериев

Не требовать ByteBuffer output/input, уменьшения flush частоты, connection reuse или benchmark ради завершения этой задачи. Указанные варианты могут обсуждаться отдельно после измерений. HTTP/2 stream abort, чужие некооперативные wrappers и полный portal feature set документированно вне текущего HTTP/1.1 pet. Не заявлять произвольные leaks или wire corruption без evidence.
