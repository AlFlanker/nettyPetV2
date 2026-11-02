# Потоковый Servlet → io.netty proxy

Самостоятельный pet-проект. В репозитории он расположен в каталоге
`streaming-proxy-pet`, рядом с исходным модулем `backends`, и собирается независимо.
В облачной рабочей среде также сохранена отдельная копия `/workspace/streaming-proxy-pet`.
На входе — Spring MVC, Jetty и
Servlet API; на выходе — **прямой io.netty**: Bootstrap, Channel и HttpClientCodec.
Reactor Netty HttpClient, Gateway и Reactor здесь не используются.
Исходный `backends` не подключён к сборке и не изменяется.

Java 21, Spring Boot 4.0.0, Jetty 12.1.4, Servlet 6.1, Netty 4.2.7.Final.
Версии зависимостей фиксирует `gradle.lockfile`, Gradle Wrapper проверяет SHA-256
дистрибутива. Тестовый и обычный runtime используют Jetty.

## Как устроен обмен

```text
Клиент → Jetty / HttpServletRequest → ProxyServlet / AsyncContext
                                      ↓
                                 ProxyCommand
                             один владелец ответа
                                      ↓
ServletInputStream → request worker → DefaultHttpRequest (только head)
  virtual thread       16 KiB         DefaultHttpContent (исходные байты)
                                     LastHttpContent (корректный EOF)
                                      ↓
                         Netty Channel / HttpClientCodec → backend

backend → HttpClientCodec → FlowControlHandler → BackendResponseHandler
                                                     очередь 1 объект
                                                           ↓
                                      response worker → ServletOutputStream
                                         virtual thread
```

* `ProxyTransport` владеет EventLoopGroup, virtual-thread executor и каналами.
  У каждого обмена одно backend-соединение. Пула/reuse, HTTP pipelining, retry,
  redirect-following и автоматической распаковки контента нет.
  DNS выполняет асинхронный Netty resolver с ограничением времени запроса;
  lookup не блокирует event loop и общий deadline.
* `ProxyCommandSupport` адаптирует **только head**: метод, raw path/query,
  end-to-end заголовки. Servlet request нельзя записать в другое Netty-соединение
  как готовый объект; формирование исходящего head неизбежно. HTTP-кодированием
  занимается HttpClientCodec.
* Request worker читает порцию непосредственно в backing array pooled heap
  ByteBuf. Следующее чтение ждёт готовности канала и завершения предыдущего
  write future. Блокирующие операции выполняются вне Netty event loop.
* После передачи HttpContent outbound pipeline владеет его буфером. Порцию,
  которая не была передана из-за EOF/отмены, освобождает request worker.
* `AUTO_READ=false` и FlowControlHandler позволяют response worker запросить
  следующий HTTP-объект после обработки предыдущего. Один socket read может
  декодировать несколько объектов: receive allocator/decoder chunk size также
  ограничены, а очередь BackendResponseHandler вмещает один объект.
* Response worker — единственный владелец status/headers/body и fallback.
  Таймер сообщает ошибку и останавливает I/O; он не пишет конкурентный 504.
  При заблокированном output закрывается HTTP/1.1 endpoint Jetty,
  чтобы разблокировать writer без второго ответа.
* После записи response HttpContent освобождается целиком. Late callbacks и
  оставшиеся в очередях буферы учитываются при остановке обмена.
* Permit возвращается после завершения upload worker и закрытия backend channel.
  Для диагностики/тестов есть счётчики реальных I/O задач, каналов и exchanges.
  Даже после аварийного закрытия соединения владелец завершает AsyncContext:
  закрытие сокета и onError сами по себе не означают завершения запроса в Jetty.

**Это потоковая передача без полного накопления и перекодирования payload,
а не обещание абсолютного zero-copy.** Servlet API, TLS и сетевой транспорт
используют свои буферы. На ответе текущий простой OutputStream bridge копирует
порции через heap. Размер целого файла не определяет размер прикладного буфера.
Помимо него существуют ограниченные очереди codec/transport и буферы ОС.

## HTTP-контракт

Тела POST/PUT/PATCH/DELETE/GET/HEAD/OPTIONS передаются одинаковыми HttpContent без
методозависимого агрегатора. В частности, HEAD request может иметь body, но HEAD
response не содержит body. Нет `FullHttpRequest`, `HttpObjectAggregator`,
`HttpPostRequestEncoder`, `getParameterMap()` или `getParts()` на proxy-пути.
Форма, multipart boundary, бинарные и сжатые данные передаются исходными байтами.

* Известная длина → Content-Length; неизвестная длина тела → chunked; пустое тело
  имеет явный framing. CL и TE не отправляются одновременно. Недочитанное тело
  не превращается в успешно отправленное тело меньшего размера.
* Host назначается для фиксированного backend. Hop-by-hop заголовки и имена из
  Connection удаляются в обоих направлениях; повторные end-to-end headers и
  Set-Cookie сохраняются.
* Для HTTP/1.1 response неизвестной длины явно формируется chunked даже при
  Connection: close. Это позволяет обнаружить обрыв по отсутствию последнего
  чанка. HEAD/204/304 обрабатываются как ответы без тела.
* Промежуточные informational responses не подменяют финальный ответ.
  `Expect: 100-continue` со стороны клиента обслуживает Jetty при чтении body.
* До commit транспортная ошибка даёт 502, deadline — 504, отказ по capacity или
  остановке — 503. HTTP-статус самого backend (включая 413/500) сохраняется.
  После commit ошибка обрывает ответ; fallback к частичному телу не дописывается.
* Ранний финальный ответ backend останавливает upload. Client и backend
  соединения закрываются: непрочитанное тело не становится следующим запросом.
* Локальные 501/503 используют setStatus, а внутренний ERROR-dispatch никогда
  не создаёт backend request. Это исключает повторное проксирование `/error`.

HTTPS upstream использует SslHandler с проверкой цепочки доверия и hostname,
таймаутом handshake; trust-all режима нет. Целевой origin задаётся конфигурацией,
не выбирается клиентским Host или URL. HTTP/1.1 — основной проверяемый протокол;
HTTP/2 frontend/ALPN, CONNECT, Upgrade/WebSocket и trailers не реализованы.
Для close-delimited ответов без CL/chunked закрытие соединения само является EOF:
протокол не позволяет обнаружить усечение по одному только закрытию.

## Запуск

Нужен **JDK 21**, не только JRE. Из директории `streaming-proxy-pet`:

```bash
./gradlew test installDist
```

В двух терминалах:

```bash
# Терминал 1: backend на 127.0.0.1:9090
./gradlew backend

# Терминал 2: proxy на 127.0.0.1:8080
./gradlew run
```

После `installDist` можно запускать без Gradle-процессов:

```bash
java -cp 'build/install/streaming-proxy-pet/lib/*' example.proxy.DemoBackend
build/install/streaming-proxy-pet/bin/streaming-proxy-pet
```

Demo backend читает тело порциями и возвращает метод, URI, Content-Type, число
байт и SHA-256. Он не хранит весь upload. Маленький диагностический ответ
собирается в памяти только в demo backend.

Для проверки больших файлов и неисправностей есть отдельный набор
[Python-эмуляторов backend](backend-emulators/README.md): потоковая отправка
и приём файлов от 1 GiB, медленные стороны, ранние отказы, таймауты и усечённые
ответы. Проверяющий клиент считает длину и SHA-256 порциями без сохранения всего
тела; при необходимости отдельный скрипт создаёт настоящий файл на диске.

```bash
curl -sS 'http://127.0.0.1:8080/form?x=query&x=again' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-binary 'x=body&x=second&space=a+b&encoded=a%20b'

printf 'binary\000payload\377' > /tmp/proxy-payload.bin
curl -sS http://127.0.0.1:8080/upload \
  -F 'label=demo' -F 'file=@/tmp/proxy-payload.bin'

sha256sum /tmp/proxy-payload.bin
curl -sS -X DELETE http://127.0.0.1:8080/file \
  -H 'Content-Type: application/octet-stream' --data-binary @/tmp/proxy-payload.bin
```

В текущей облачной среде:

```bash
export JAVA_HOME=/workspace/tools/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
python3 /workspace/setup/streaming-proxy-pet/gradle.py test installDist
```

Последняя команда — локальный helper среды для Gradle proxy/CA settings; для
обычного окружения достаточно JDK и Wrapper. Настройки Gradle HTTP-прокси не
меняют транспорт pet-приложения, которое подключается к своему backend напрямую.

## Конфигурация

Все свойства доступны через Spring configuration/аргументы командной строки.
Неверные origin, порты, неположительные лимиты и интервалы отклоняются до старта.

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `proxy.backend-url` / `BACKEND_URL` | `http://127.0.0.1:9090` | Origin без пути/query/userinfo |
| `proxy.max-in-flight` / `PROXY_MAX_IN_FLIGHT` | 32 | Активные exchanges, каждый с одним backend channel |
| `proxy.io-threads` | 2 | Netty event loops |
| `proxy.chunk-size` | 16384 | Размер порции upload и ограничение socket read/decoder |
| `proxy.connect-timeout` | 3s | Connect, TLS handshake и отдельный DNS query |
| `proxy.read-timeout` | 30s | Ожидание следующего backend object после upload/раннего final response; не время занятости servlet writer |
| `proxy.exchange-timeout` | 60s | Общий deadline, включая клиентский upload |
| `proxy.shutdown-timeout` | 5s | Интервал ожидания остановки ресурсов |
| `PROXY_PORT` | 8080 | Loopback-порт proxy |
| `BACKEND_PORT` | 9090 | Порт только demo backend |

Общий deadline включает медленные загрузки: для больших файлов увеличьте его
через конфигурацию, а не меняйте Java. Read timeout не используется как таймер
медленного downstream; незавершённый обмен всё равно ограничивает общий deadline.
32 exchanges не означают ровно 32 сокета во всём JVM или количественную гарантию RSS.

## Перенос в исходный стек

В выделенном pet servlet `/*` обслуживает все запросы, а FormContentFilter и
multipart resolver глобально выключены. Это обеспечивает сырой body на входе.

В смешанном MVC-портале так делать глобально нельзя: локальные endpoints должны
продолжать разбирать формы/файлы. Сначала определяйте proxy route/backend, затем
пропускайте consuming filters только для этого маршрута. В исходном модуле уже
есть `ProxyAwareFormContentFilter`; его маршрутный подход подходит для такой
интеграции. До proxy никто не должен вызвать getParameter*, getParts, getReader
или прочитать InputStream. Уже потреблённый поток нельзя восстановить без копии.

Использованы контракты исходного примера — отдельный command, Netty codec,
владение response/ByteBuf и отмена. Не переносились полная агрегация,
восстановление POST по карте параметров и проблемная фильтрация keep-alive headers.
Авторизация, HTML/cookie/Location rewriting, динамическая маршрутизация,
telemetry и circuit breaker остаются за пределами этого pet.

## Проверки и дальнейшие измерения

Тесты используют реальные Jetty/Netty sockets и локальные backend на случайных
портах. Они проверяют точные bytes/query, multipart, известную/неизвестную длину,
раннюю доставку GET/DELETE/HEAD body, OPTIONS/Allow, статусы/headers, gzip, 100 Continue,
ограничение read-ahead у остановивших чтение backend и клиента, таймауты до/после commit,
локальные отказы, early 413, отмену и фактическую очистку ресурсов.
Отдельные EmbeddedChannel-проверки контролируют refCnt при cancel/overflow/close.

[План ревью](REVIEW_PLAN.md) сохранён как история найденных проблем;
[статус реализации](IMPLEMENTATION_STATUS.md) сопоставляет пункты с кодом и тестами.
[Повторная оценка четырёх экспертов](review/ASSESSMENT.md) содержит вердикт
по пяти требованиям пользователя и план оставшихся измерений.
Неблокирующий ByteBuffer writer, direct ByteBuffer input и изменение flush policy
остаются необязательными измеряемыми экспериментами P-08/P-09. Они не включены
в основной transport без сравнительного benchmark. Текущий вариант сознательно
сохраняет простой bounded bridge; абсолютный zero-copy не заявляется.
