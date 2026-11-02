# Уточнённый план Netty-архитектуры: прямой io.netty, без Reactor Netty HttpClient

Это дополнение к `/tmp/review-netty.md` после уточнения пользователя. Только план; код обоих проектов не изменён. Исходный транспорт использует Bootstrap/Channel/HttpClientCodec; наличие Reactor Mono для результата команды не означает использование Reactor Netty HTTP-клиента. Текущий pet этому требованию не соответствует.

## 1. Зафиксировать контракт и убрать неподходящий транспорт — P1

- Сохранить Spring MVC + Jetty + Servlet API, `ProxyServlet`, `ProxyCommand`, `ProxyCommandSupport` и небольшой `ChannelManager`/`BackendResponseHandler`.
- Удалить из pet `reactor-netty-http`/`reactor-netty-core`, `HttpClient`, `ConnectionProvider` и глобальные Reactor Netty ресурсы. Использовать прямые `io.netty` зависимости под единым BOM и при необходимости `reactor-core` только для `Mono` результата/отмены, как в исходном примере.
- Самостоятельно создавать и закрывать ограниченный EventLoopGroup; NIO достаточно для минимального переносимого pet. Epoll — отдельная необязательная оптимизация, не цель первой версии.
- Скрытая агрегация GET/HEAD/DELETE в текущем Reactor Netty pet остаётся подтверждённой находкой. Исправление теперь — замена транспорта, а не предложенный ранее локальный обход `send` overload.

«Без пересоздания запроса руками» следует трактовать точно: не разбирать и не пересобирать тело/form/multipart. Между Servlet API и io.netty необходим маленький адаптер метаданных. Один `DefaultHttpRequest` (method + raw request-target + разрешённые headers) неизбежен; `HttpClientCodec` выполняет HTTP-кодирование. Это не `DefaultFullHttpRequest` и не повторное кодирование payload.

## 2. Минимальный pipeline и framing — P1

Pipeline: optional `SslHandler` → `HttpClientCodec` → `FlowControlHandler` → per-exchange `BackendResponseHandler`.

- Никаких `HttpObjectAggregator`, `HttpContentDecompressor`, `HttpPostRequestDecoder`/`Encoder` и накопления body. `ChunkedWriteHandler` не нужен для нашего push-потока `HttpContent`; он нужен при выборе `ChunkedInput`, которого минимальный план не требует.
- Один head: `DefaultHttpRequest(HTTP_1_1, method, rawPathAndQuery)`; затем `DefaultHttpContent(ByteBuf)` порциями; ровно один `LastHttpContent.EMPTY_LAST_CONTENT` при успешном EOF. Можно завершать последним data-bearing LastHttpContent, но не отправлять затем второй терминатор.
- Host/authority берётся из фиксированного backend origin. End-to-end headers сохраняются, включая повторения; hop-by-hop и имена из Connection удаляются.
- Длина известна: корректный Content-Length, без Transfer-Encoding. Длина неизвестна и тело есть: `HttpUtil.setTransferEncodingChunked(head, true)`, без Content-Length. Нет тела: явно выбранное и проверенное framing, не угадывание по HTTP-методу.
- Framing определяется для POST/PUT/PATCH/DELETE одинаково; для GET/HEAD body выбрать и документировать поддержку либо явный отказ. Нельзя молча агрегировать или терять тело. Считать фактически отправленные байты; premature EOF/лишние байты при известной длине — ошибочный обмен, backend channel закрыть, в пул не возвращать.

Приёмка: CL/chunked/empty matrix по методам, exact digest, raw path/query, GET/DELETE gate до EOF, отсутствие CL+TE одновременно, multipart boundary и бинарные payload.

## 3. Отправка servlet body с ограниченным demand — P1

- Все решения состояния обмена/канала выполняются в его event loop. Блокирующий ServletInputStream никогда не читается в event loop.
- Выделить небольшой мост request-body pump: не более одной активной blocking-read задачи и ограниченного числа ещё не завершённых writes (для pet достаточно1), чанк16KiB. Читать сразу в backing array pooled heap ByteBuf. Не использовать полный CompositeByteBuf.
- Запускать следующее чтение только когда обмен активен, канал writable и предыдущая запись подтверждена ChannelFuture. `channelWritabilityChanged` возобновляет pump; WRITE_BUFFER_WATER_MARK — дополнительный транспортный предел, не замена собственного ограничения очереди.
- Успешный write future означает завершение записи в транспорт, а не факт чтения backend; bounded memory обеспечивается собственным лимитом плюс watermarks.
- worker возвращает готовый чанк в event loop; если отмена/timeout/ранний ответ произошли до этого, чанк освобождается и не записывается в новый exchange. Нужны terminal flag/generation token, особенно при pooled channels.
- EOF порождает LastHttpContent только один раз. Ранний backend413 прекращает upload; незавершённый backend request исключает reuse соединения. Для непрочитанного клиентского тела явно выбрать close/drain с конечным лимитом; не читать остаток неограниченно ради keep-alive.

Ownership: до передачи в `channel.write/writeAndFlush` буфер принадлежит pump. После передачи ownership принадлежит outbound pipeline; нельзя дополнительно release по неуспешному write future, если msg уже принят pipeline. При отмене до передачи, отказе eventLoop.execute, потерянном готовом чанке или terminal state до write приложение обязано release. cancel не подтверждает, что заблокированное чтение уже закончилось; этот момент нельзя путать с освобождением ресурсов.

Приёмка: slow backend останавливает продвижение reader в пределах заданного окна; cancel во время read/перед write/при unwritable channel; early413; write failure; ни повторной отправки, ни leaked/double-released буфера.

## 4. Приём ответа и servlet output — P1

- После подключения/TLS handshake установить AUTO_READ=false; сам handshake должен иметь необходимые чтения. Первая `read()` запускает ответ. Следующую выдавать только после того, как предыдущий чанк принят/записан servlet writer и есть место в ограниченной очереди.
- `HttpClientCodec` может декодировать несколько HttpObject из одного socket read. `AUTO_READ=false` само по себе не означает «один буфер». Использовать FlowControlHandler и ограниченные receive allocator/maxMessagesPerRead/decoder chunk size; документировать также конечную очередь уже декодированных сообщений.
- Для минимального pet выбрать один стиль ownership: ChannelInboundHandlerAdapter с явным release либо SimpleChannelInboundHandler с autoRelease и retain для передачи в writer. Не смешивать. Передача в другой поток требует удержания буфера; ровно один release после записи или отмены. Закрытие освобождает pending/queued chunks, включая внутреннюю очередь FlowControlHandler.
- Можно сохранить bounded blocking ServletOutputStream writer на отдельном/virtual worker для первой версии; ни write/flush, ни потенциально блокирующие servlet terminal операции не выполнять в Netty event loop. Неблокирующий WriteListener — отдельное улучшение с собственной проверкой корректности, а не обязательный перенос всей portal-реализации.
- Учитывать informational responses (например100), HEAD/204/304, LastHttpContent и отсутствие тела. Trailers/Upgrade/CONNECT оставить явно вне минимального контракта либо реализовать отдельной задачей. Не завершать ответ по одному только HttpResponse head.

Приёмка: первый чанк до EOF; медленный frontend ограничивает backend reads; HEAD/204/304;100Continue; truncation/error после commit; отмена с queued ByteBuf; prompt release и следующий healthy exchange.

## 5. Единое состояние, timeout, закрытие и pooling — P1/P2

- Ввести компактный ExchangeState/ResponseOwnership: единственный владелец normal response или fallback; один terminal исход. Все transitions проходят через event loop/арбитраж. `isCommitted()` — только состояние servlet, не lock/ownership.
- До commit: определённый502/504/503. После commit: abort/close неполного ответа, без reset, дописывания fallback или ложного успешного EOF. HTTP-ошибка origin (например404/413/500) остаётся нормальным проксируемым ответом.
- Разделить connect/TLS, pool acquire, upload/idle, response idle и общий deadline; задавать их конфигурацией. Общий deadline включает медленный upload. Все timers отменяются terminal cleanup; поздние callbacks не меняют recycled servlet objects/переиспользованный канал.
- Отдельно учитывать: logical command terminal, фактическое прекращение I/O, servlet exchange terminal, освобождение admission permit и eligibility канала для reuse. Один AtomicBoolean release-once не доказывает, что старый worker завершился.
- Минимальный первый correctness gate может использовать одно backend connection на exchange с закрытием после него. Если keep-alive входит в выбранный контракт первой версии, использовать ограниченный Netty FixedChannelPool/аналог с конечной очередью acquisition, без автоматического retry/replay.
- Reuse допустим только после полного request write + валидного полного response/LastHttpContent, отсутствия ошибок/отмены/раннего response при незавершённом upload, HTTP keep-alive и чистого per-exchange pipeline. Удалить handler и отменить timers перед release; сохранить один exchange на channel, HTTP pipelining не вводить.
- Shutdown: прекратить admission, завершить/abort активные exchanges за конечный grace период, закрыть pool/channels, затем event loops и servlet-I/O executor. TLS: SslHandler с hostname verification, доверенными CA и handshake timeout; не trustAll.

Приёмка: гонки timeout vs first write/commit/LastHttpContent/client disconnect; pool size1 последовательные различающиеся запросы без смешения данных; stale/closed pooled channel не приводит к replay; cancelled acquire; early413 не переиспользует нечистое соединение; shutdown во время upload/download; возвращены permits, buffers, channels и tasks.

## Порядок и предел объёма

1. Контракт и direct-Netty зависимости/pipeline.
2. Head+framing и request pump с одним чанком/ограниченным write окном.
3. Response reader/writer и единый ownership/terminal coordinator.
4. Ошибки/timeout/cancellation и resource-oriented tests.
5. Pool/TLS, только если включены в выбранный контракт; иначе явно отдельный этап.
6. README и сравнение с original после подтверждения тестами. Performance/epoll/batching — позже, по измерениям.

Не переносить portal authentication, resilience decorators, HTML rewriting, cookie rewriting, telemetry infrastructure или динамический routing. Не изменять/не собирать исходный backends. Reactor Netty API workaround из прежнего отчёта сохранить только как исследовательское доказательство прежней ошибки, не как принятую реализацию.
