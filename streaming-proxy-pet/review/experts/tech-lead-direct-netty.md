# Корректировка техлида: прямой Netty transport

## P00 — обязательное исправление архитектурного несоответствия

`streaming-proxy-pet` использует Reactor Netty `HttpClient` (`ProxyTransport`, `ProxyCommand`, build.gradle), тогда как пользователь требует транспорт исходного примера на прямом `io.netty`. Это несогласованная подмена механизма транспорта. Предыдущие рекомендации выбрать иной overload `HttpClient.send` больше не являются целевым исправлением.

Сохранить Spring MVC + Jetty + Servlet API, структуры ProxyCommand/ProxyCommandSupport и, при необходимости, Reactor Core Mono как оболочку результата команды, как в исходнике. Убрать зависимость reactor-netty-http и применение HttpClient; не заменять его другим high-level gateway/client.

## Контракт «без пересоздания запроса руками»

На границе Servlet → отдельное Netty-соединение необходим адаптер метаданных: один HttpRequest head (метод, raw URI/query, end-to-end headers). HTTP-байты и framing кодирует штатный HttpClientCodec. Это не повторная сборка тела: нельзя использовать FullHttpRequest, HttpObjectAggregator, getParameterMap/getParts и HttpPostRequestEncoder. Payload поступает последовательностью HttpContent с исходными байтами, завершаемой LastHttpContent. Если пользователь подразумевает отсутствие даже head-adapter, это несовместимо с прямым Netty на границе Servlet; в плане следует прямо объяснить эту границу, не молча менять стек.

## Этапы замены

1. **Зафиксировать транспортный контракт и тесты.** Определить поддерживаемые обычные HTTP/1.1 методы и тела (fixed Content-Length/chunked), raw URI/query, request/response header policy, ошибки и таймауты. Существующие byte-for-byte и streaming тесты сохранить; расширить их GET/DELETE/HEAD, ранним ответом backend и ошибками после commit. Сохранить P1 локального ERROR dispatch и response ownership.
2. **Минимальный Netty transport.** Отдельный управляемый Spring ресурс: EventLoopGroup, Bootstrap, bounded pool для одного фиксированного backend, HttpClientCodec и per-exchange response handler. При HTTPS — SslHandler с обычной проверкой доверия/имени. Один обмен на канал, без HTTP pipelining. Без универсальной маршрутизации, retries и дополнительного resilience framework. Версии Netty согласовать с выбранным Boot BOM/исходной совместимостью, не выдумывать отсутствующий nettyVersion исходника.
3. **Поток отправки запроса.** После получения канала записать только head. Маленький request-body pump читает исходный ServletInputStream порциями вне event loop. Один read in-flight и ограниченное число pending chunks; следующее чтение разрешают writable-состояние канала и завершение предыдущей записи. Передавать DefaultHttpContent/LastHttpContent через codec. Описать ownership: до write буфер принадлежит pump, после успешной передачи outbound — Netty; при cancel до handoff освобождает pump. Не исправлять Content-Length на усечённый фактический размер: при недочитанном запросе обмен оборвать.
4. **Поток ответа и lifecycle.** Взять минимальные контракты исходных BackendResponseHandler, ResponseOwnership и ServletBodyWriter. Метаданные обрабатывать отдельно от HttpContent; следующий inbound read разрешать после приёма предыдущего чанка servlet writer. Все изменения response/terminal state арбитрируются. После commit только abort. Остановить request pump при раннем terminal response/backend close; незавершённый обмен не возвращать в pool.
5. **Пул, отмена и завершение.** Явные состояния acquired/head-sent/uploading/responding/completed/aborted, без разрастания framework. В pet можно возвращать канал только после полного корректного завершения request и response и освобождения writer; при любой неоднозначности/ошибке закрывать. Идемпотентные cancel/close/release для socket, buffer, pool slot и servlet async; timeout не равен завершению физического I/O. Shutdown закрывает собственные EventLoopGroup/pool.
6. **Приёмка и документация.** Проверить реальные каналы/буферы и read-ahead у медленных peers, а не только semaphore; повторы отмен и early response; correct HEAD/no-body responses; несколько Set-Cookie, dynamic Connection tokens, Expect. Удалить HttpClient/Gateway и все утверждения, относящиеся к ним. Описать head-only adapter и отсутствие полного накопления/перекодирования payload. Исходный backends остается неизменным и не собирается.

## Критерии завершения P00

* В build/runtime отсутствует reactor-netty-http; transport построен на io.netty Bootstrap/Channel/Pipeline/HttpClientCodec.
* Mono, если сохранён, только выражает жизненный цикл команды; HTTP транспорт им не подменяется.
* В production-коде pet отсутствуют FullHttpRequest/HttpObjectAggregator/form encoders и полный сбор request/response body.
* Существующие функциональные тесты сохранены; методозависимая полная буферизация исключена проверками; response ownership/local ERROR dispatch исправлены по общему плану.
* Документация не обещает абсолютный zero-copy: Servlet/netty boundary требует chunk adapters и сетевых буферов.

## Как переоценить прежние находки

* Агрегация GET/HEAD/DELETE в Reactor Netty остается доказательством недостатка текущего варианта, но исправляется удалением этого транспорта, а не другим send-overload.
* Response race, некорректный локальный ERROR dispatch, ранний возврат capacity, слабые abort/backpressure assertions и устаревшая документация остаются применимыми требованиями к новому варианту.
* Не переносить из исходного reference его полную буферизацию, form rebuild, keepAlive-header filter и nullable charset bug. Заимствовать прежде всего контракты ownership/abort, а не код без проверки.
