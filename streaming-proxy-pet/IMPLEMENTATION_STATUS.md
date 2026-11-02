# Выполнение плана

Целевой транспорт теперь **прямой io.netty**. Reactor Netty HttpClient,
ConnectionProvider и Reactor удалены из приложения и runtime dependencies.
Исходный модуль `backends` не изменялся и не собирался.

## Сопоставление с ревью

| Пункт | Результат |
|---|---|
| P-00: прямой Netty | Bootstrap, собственный NIO EventLoopGroup, HttpClientCodec, FlowControlHandler, BackendResponseHandler; HTTP/HTTPS; одно соединение на exchange |
| P-01: агрегация/framing | Только DefaultHttpRequest head, HttpContent chunks и LastHttpContent. POST/PUT/PATCH/DELETE/GET/HEAD/OPTIONS, Content-Length/chunked/empty; никакой методозависимой агрегации |
| P-02: ERROR-dispatch | Локальные setStatus+CL0 вместо sendError; внутренний ERROR не отправляется backend. Регрессия освобождения capacity при отказе не создаёт `/error` upstream |
| P-03: владение ответом | Один response-worker меняет servlet response. Timeout только сигнализирует/прерывает; при активной записи — HTTP/1.1 endpoint close. Барьерный тест первой записи доказывает отсутствие второго fallback/reset |
| P-04: реальное завершение | Cancel/join upload worker, закрытие channel, очистка queued content, завершение AsyncContext даже после endpoint abort, затем освобождение capacity; проверяются задачи, каналы, exchanges и контейнерный lifecycle |
| P-05: backpressure/ByteBuf | Один upload write in flight, writable gate, manual response demand, очередь в один объект, bounded socket read/decoder. Проверки stalled peers и настоящих refCnt через EmbeddedChannel |
| P-06: HTTP regression matrix | Truncation отличим от client timeout, missing last chunk, close-delimited EOF, interim100, HEAD204304, early413, gzip, raw path/query, headers, no retry/redirect, client abort |
| P-07: конфигурация | ProxyProperties с валидируемыми origin/портом, лимитами и Duration; короткие deadlines в тестах; нет ненужных pool/acquire настроек, поскольку reuse не вводился |
| P-08: output ByteBuffer/flush benchmark | Необязательный этап не внедрён: сохранён проверенный bounded OutputStream baseline с ранней доставкой. Сравнительный benchmark альтернатив не выполнялся, выигрыш не заявляется |
| P-09: input direct ByteBuffer | Необязательный эксперимент не внедрён: используется простой heap ByteBuf с чтением прямо в backing array. Абсолютный zero-copy не заявляется |
| P-10: тестовый контейнер | Tomcat server зависимости исключены из test runtime; проверяется Jetty factory и отсутствие Reactor Netty/Tomcat container классов |
| P-11: смешанный MVC | README описывает route-aware обход consuming filters и исходный ProxyAwareFormContentFilter. Сам pet остаётся выделенным proxy; локальные MVC controllers не добавлялись |
| P-12: документация | README переписан под прямой Netty; устаревший Gateway-комментарий удалён; ограничения копирования, deadlines, reuse и scope явно описаны |

Найденный при реализации дополнительный риск стандартного блокирующего DNS resolver
устранён: транспорт использует собственный асинхронный Netty DnsAddressResolverGroup.
Локальный неотвечающий DNS fixture проверяет, что event-loop deadline продолжает
выполняться во время lookup. Resolver закрывается вместе с транспортом.

## Что показали проверки

* Большой upload идёт до EOF производителя; неизвестная длина GET/DELETE больше
  не приводит к скрытой агрегации или потере тела.
* Поздний сбой неизвестной длины не маскируется close-delimited успешным EOF:
  HTTP/1.1 response получает явный chunked framing.
* Jetty state.abort до commit мог сформировать собственный HTML500. Используется
  закрытие HTTP/1.1 endpoint после захвата writer; барьерный тест видит обрыв,
  а не второй ответ. Это решение относится к HTTP/1.1, не к HTTP/2 stream abort.
* Permit остаётся занят, пока намеренно заблокированный тестовый writer ещё жив,
  даже после отправки interrupt. Возвращение счётчика не подменяет остановку I/O.
* Повторное ревью выявило, что закрытие endpoint без async.complete оставляло
  запросы активными в Jetty при нулевых счётчиках transport. Завершение AsyncContext
  теперь не пропускается после abort/onError; только onComplete означает его конец.
* TLS проверяет доверие и имя сервера; проверен полный production путь через
  proxy к доверенному локальному HTTPS backend без замены response handler.
* Tests проверяют не только отсутствие LEAK в логах, но и refCnt queued/late
  buffers, отказ переполнения очереди и освобождение FlowControlHandler при close.

## Повторение проверки

Последний полный прогон 2026-11-02: **41 тест, 0 ошибок, 0 пропусков**;
`test installDist` из отдельного каталога подготовки PR завершился успешно за 33 с.
При редактировании комментариев исполняемый код не менялся: это дополнительно
проверено сравнением Java-токенов. `ProxyServletCompletionTest`
проверил реальный `AsyncListener.onComplete` после abort и закрытие Spring-контекста
раньше настроенного graceful timeout. В полном логе нет ожиданий shutdown до timeout.
OPTIONS с телом/без тела и stalled downstream также прошли общий прогон.

Собранный дистрибутив отдельно запущен с DemoBackend: form POST и chunked DELETE
размером 2 MiB сохранили URI, Content-Type, длину и SHA-256 тела. Исходный модуль
остался без изменений. Подробные выводы и необязательные улучшения —
в [повторной оценке](review/ASSESSMENT.md).

```bash
./gradlew test installDist
```

В текущей облачной среде с настроенным proxy/CA/JDK:

```bash
python3 /workspace/setup/streaming-proxy-pet/gradle.py test installDist
```

JUnit XML: `build/test-results/test/`. HTML: `build/reports/tests/test/index.html`.
Дистрибутив: `build/install/streaming-proxy-pet/`.

Набор — функциональная и lifecycle-проверка, не production benchmark или
универсальное доказательство отсутствия любых утечек. Ограничения из README
сохраняются: нет connection reuse, HTTP/2, CONNECT/Upgrade/trailers, portal auth,
HTML rewriting и динамического routing. Необязательные оптимизации P-08/P-09
требуют отдельных измерений и не являются условием завершения основной части плана.
