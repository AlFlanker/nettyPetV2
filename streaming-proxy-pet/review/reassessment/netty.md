# Повторное ревью Netty: текущая direct-io.netty реализация

> **Обновление после итоговой валидации, 2026-11-02.** Найденный ниже
> AsyncContext blocker исправлен: socket abort/onError отделены от настоящего
> servlet completion. Полный `test installDist` прошёл: **41 тест, 0 ошибок,
> 0 пропусков**, сборка за 30 с. Реальный AsyncListener/context-close regression,
> OPTIONS и SlowDownstreamTest прошли; graceful shutdown всех контекстов завершился
> без прежних 30-секундных ожиданий. Ниже сохранён исходный анализ на момент ревью;
> его старые статусы blocker/38 тестов/неисполненный тест не описывают итоговую
> версию. Актуальный сводный результат — [ASSESSMENT.md](../ASSESSMENT.md).

Проверены актуальные main-классы, тесты, README и сохранённые JUnit XML. Исходный backends использован только как reference; он не собирался. Исходники не изменялись; Gradle параллельно с основным агентом не запускался.

## Вывод по пяти критериям

| Критерий | Оценка | Доказательства и пределы |
|---|---|---|
| 1. Request всегда stream или ограниченные буферы | Выполнен для объявленного HTTP/1.1-контракта | ProxyCommand.java:209-248: один head, затем порции chunkSize, каждая запись ожидается до следующего чтения. Нет FullHttpRequest/aggregator/body parsing. GET/DELETE/HEAD больше не проходят скрытый Reactor Netty collectList. ProxyCommandSupport.java:37-50 задаёт CL/chunked по framing входа, а не по методу. |
| 2. Основные HTTP-методы | Выполнен для POST/PUT/PATCH/GET/DELETE/HEAD; OPTIONS — небольшой пробел проверки | Метод передаётся через HttpMethod.valueOf, без POST-ветки (Support.java:25). Интеграционные тесты покрывают точные payload, известную/неизвестную длину GET/DELETE и HEAD body. OPTIONS кодом не запрещён, но отдельной проверкой ещё не подтверждён. TRACE/CONNECT/Upgrade не нужны для признания pet полезным proxy-примером; ограничения следует сохранять явными. |
| 3. Оптимальная память | Основная цель достигнута; численная оптимальность не заявлена | Request: pooled heap чанк и максимум одна незавершённая запись (Command.java:221-238); собственный watermarks/receive bound (Transport.java:79-82). Response: очередь один HttpObject (Handler.java:17), FlowControlHandler + manual read + decoder chunk limit (Transport.java:95-98; Command.java:267-283). Память приложения не масштабируется с длиной файла. OutputStream bridge копирует порции; это обоснованный простой компромисс, а не blocker. |
| 4. Главные сценарии тестируются | Существенно выполнен, но текущий общий cleanup verdict требует поправки | XML содержат38 тестов,0 failures/0 skipped. Есть framing/method matrix, backpressure upload, refCnt, raw chunk truncation, deadline/ownership, early413, no retry, DNS, TLS и shutdown. Новый SlowDownstreamTest есть в source, но отсутствует среди прочитанных XML, значит его результат пока не подтверждён этим отчётом. См. подтверждённый Jetty shutdown blocker ниже. |
| 5. Response достаточно близок исходному примеру | Выполнен по архитектуре и основному поведению; terminal cleanup пока с оговоркой | Прямой HttpClientCodec/FlowControlHandler, manual read, явное владение HttpContent и отдельный servlet writer. Сохраняются status/headers/raw body, HEAD/204/304 и informational response; нет необязательных portal rewrite/cookie/auth компонентов. |

## Что исправлено по сравнению с прежним pet

- Reactor Netty HttpClient полностью заменён прямыми Bootstrap/Channel/HttpClientCodec; нет зависимости от method-sensitive send-агрегации.
- HTTP head создаётся один раз (`DefaultHttpRequest`), payload не кодируется заново. Это соответствует требованию «без ручной пересборки body» при неизбежном адаптере Servlet→Netty.
- Upload backpressure действительный: `.writeAndFlush(...).sync()` исполняется на virtual worker, не event loop; следующий chunk не читается до завершения предыдущей transport write. channelWritable дополнительно учитывается, callback будит reader.
- Response mutations/fallback принадлежат одному response worker. Таймер закрывает/прерывает, но не пишет конкурентный status/body.
- Capacity удерживается до выхода upload worker и закрытия backend channel, а не просто до завершения реактивного publisher.
- Отдельные EmbeddedChannel тесты проверяют refCnt, overflow, late content и release FlowControl queue. Это сильнее прежнего «LEAK не встретился в логах».

## Подтверждённый текущий blocker: Jetty logical completion после abort

Проверка основного агента и `/tmp/direct-netty-final.log` показали четыре graceful-shutdown ожидания по30s с `webServerGracefulShutdown` и `requests still active`, несмотря на0 Netty каналов/I/O/tasks/exchanges. Это настоящее неполное завершение servlet lifecycle, а не доказанная утечка Netty ByteBuf.

Связанные места на момент чтения: ProxyCommand.java:175-181 пропускает async.complete при servletAborted/servletEnded; :343-349 закрывает raw EndPoint. После transport abort Jetty logical async request ещё должен завершиться согласованным container API. Исправление исследует Jetty-эксперт/root. До его проверки нельзя утверждать «фактическая очистка всех ресурсов» только по собственным счётчикам.

Приёмка: abort-after-commit, timeout при blocked output, клиентский RST и truncated upstream; проверка Jetty active requests=0 и быстрое завершение контекста без graceful-shutdown timeout. Результаты всех текущих функциональных тестов должны сохраниться. Это единственный подтверждённый blocker, известный этому повторному ревью; новых независимых Netty transport blockers не найдено.

## Неподтверждённые риски / ограничения доказательств

1. Manual read + FlowControl queue + fixed receive allocation дают ограниченный bridge, но не утверждение «ровно один ByteBuf во всей цепочке». Codec может создать несколько объектов из одного read; TLS добавляет свои cumulation buffers. README это правильно различает. Не надо требовать абсолютного zero-copy или RSS-бенчмарка как условия корректности pet.
2. TLS: есть negative trust/hostname tests и добавлен full production path test с AUTO_READ=false/FlowControl/ProxyCommand/Jetty, а не только заменённый test handler. Это закрывает основной риск normal close_notify. Массовые slow TLS downloads не проверены отдельно; при сохранённом bounded path это дополнительная проверка, не подтверждённый дефект.
3. BackendResponseHandler и request handoff выглядят корректно: до outbound handoff release принадлежит worker, после — Netty; response ChannelInboundHandlerAdapter передаёт ownership целого HttpContent worker, который release в finally. Не найдено подтверждения double release. Request cancel между read и write проверяется stopUpload; late failure записи не означает необходимость второго release.
4. Shutdown ожидание uploadExited в крайнем случае становится неограниченным после abort (Command.java:353-369). Это сознательное предотвращение преждевременного возврата permit; зависимость от фактического разблокирования Jetty надо описывать и проверять. Текущий stalled-upload shutdown тест существует; общей гарантии для произвольной пользовательской ServletInputStream-обёртки он не даёт, что нормально для объявленного Jetty-контракта.

## Небольшие улучшения, не blockers

- Добавить OPTIONS smoke/проверку сохранения метода; при необходимости отдельно OPTIONS * (server-wide request-target). Расширить existing method matrix, без нового transport слоя.
- Дождаться результата SlowDownstreamTest и включить его в итоговый test count; не считать новый source-файл пройденным тестом.
- При дальнейшей performance работе измерить стоимость flush каждого чанка и response direct→heap copy. Не менять наугад working backpressure/latency контракт.
- Пул соединений, epoll, full portal rewrite и HTTP/2 не требуются для пяти критериев; одно соединение на обмен с Connection:close — приемлемое явно документированное упрощение.

Итог: direct-Netty request streaming/method support/memory architecture теперь соответствуют задаче. Результат следует завершить исправлением/подтверждением Jetty async terminal cleanup; другие предложенные измерения остаются необязательными.
