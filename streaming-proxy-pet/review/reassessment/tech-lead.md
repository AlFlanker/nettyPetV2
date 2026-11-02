# Повторная оценка техлида: текущий прямой Netty pet

> **Обновление после итоговой валидации, 2026-11-02.** Найденный ниже
> AsyncContext blocker исправлен: socket abort/onError отделены от настоящего
> servlet completion. Полный `test installDist` прошёл: **41 тест, 0 ошибок,
> 0 пропусков**, сборка за 30 с. Реальный AsyncListener/context-close regression,
> OPTIONS и SlowDownstreamTest прошли; graceful shutdown всех контекстов завершился
> без прежних 30-секундных ожиданий. Ниже сохранён исходный анализ на момент ревью;
> его старые статусы blocker/38 тестов/неисполненный тест не описывают итоговую
> версию. Актуальный сводный результат — [ASSESSMENT.md](../ASSESSMENT.md).

Проверен текущий код `streaming-proxy-pet` после замены транспорта, README,
IMPLEMENTATION_STATUS и тесты. Исходный `backends` не собирался и не менялся.
Код не редактировался; Gradle не запускался. Результаты ниже относятся к прочитанному
snapshot до исправления замечания R-TL-1. Root выполняет итоговый прогон отдельно.

## Вердикт по пяти требованиям пользователя

| Требование | Вердикт и доказательства |
|---|---|
| 1. Request всегда потоковый, буфер ограничен | **Соответствует для заявленного обычного HTTP/1.1.** `ProxyCommand:209-248` отправляет только head, затем порции heap ByteBuf заданной capacity, ждёт writable и завершения каждой записи перед следующим чтением. Методозависимых агрегаторов нет. `ProxyCommandSupport:22-50` задаёт явный CL/chunked/empty framing. Backpressure подтверждает тест64MiB stalled backend и ранняя доставка до EOF. Нельзя распространять «всегда» на явно исключённые CONNECT/Upgrade/HTTP2/trailers. |
| 2. Основные HTTP-методы | **Соответствует основным сценариям.** POST/PUT/PATCH/DELETE/GET/HEAD используют единый pump. Тесты проверяют known/unknown length GET/DELETE/HEAD, form/multipart и бинарные POST/PUT/PATCH. OPTIONS не выделен отдельным тестом, но не имеет отличающейся ветки; небольшое расширение матрицы желательно, не blocker. |
| 3. Оптимальное использование памяти | **Подтверждена ограниченность памяти относительно размера body; абсолютная оптимальность не доказана.** Нет накопления O(body). Один upload read/write in-flight, response queue1, FixedRecvByteBufAllocator/maxMessagesPerRead1, bounded codec chunks. Сохранён прямой read в backing array heap ByteBuf. Размер RSS, allocator retention, число копий TLS/heap→direct и сравнение с альтернативами не измерялись. Поэтому корректный вывод — bounded streaming без полного дублирования, не минимальная память/zero-copy. Response OutputStream copy документирована и допустима по требованию5. |
| 4. Основные кейсы покрыты тестами | **Сильное функциональное покрытие; пока неполная lifecycle-приёмка.** 38 тестов существующего последнего прогона прошли; они включают byte fidelity, framing, cancellation, early413, TLS, DNS deadline, response race, refCnt. Но лог этого же прогона содержит4×30с graceful-shutdown timeout Jetty — это не покрыто локальными counters. Новый SlowDownstreamTest читается как полезный дополнительный тест, но на момент оценки XML его успешного исполнения нет. После R-TL-1 нужен повторный полный прогон и проверка shutdown без warning. |
| 5. Ответы как исходный пример, текущего достаточно | **Функциональная передача достаточна для согласованного pet-scope.** Head/body отдельно, bounded response demand, один owner, abort после commit, headers/status/gzip/interim/HEAD204304/close-delimited проверяются. Неблокирующий ByteBuffer writer, rewrite и connection reuse не нужны для выполнения текущей задачи. Единственное обязательное завершение — R-TL-1: HTTP-обрыв должен завершать и Jetty AsyncContext. |

## Замечания

### R-TL-1 — P1/P2 lifecycle: hard endpoint abort не завершает Jetty AsyncContext

**Подтверждённый симптом, причина проверяется Jetty-экспертом/root.**

* Место: `ProxyCommand.java:165-183` — `async.complete()` пропускается при
  `servletAborted=true`; `343-350` закрывает HTTP/1.1 endpoint.
* Evidence: `/tmp/direct-netty-final.log:95,102,109,116` — четыре контекста
  завершаются только после30000ms таймаута `webServerGracefulShutdown`.
  При этом собственные ioTasks/channels/exchanges и semaphore уже обнулены.
* Следствие: утверждение «фактическая очистка ресурсов завершена» шире доказательств:
  container request bookkeeping остаётся активным. Это реальный незавершённый
  lifecycle, не пожелание production-функций.
* Действие: обеспечить корректное exactly-once завершение AsyncContext после
  abort/error, не создавать второй response; конкретный Jetty lifecycle путь
  согласовать с экспертом. Не маскировать отключением graceful shutdown.
* Приёмка: после timeout-after-commit, truncation, client RST и blocked-write race
  одновременно zero transport tasks/channels/exchanges и zero Jetty active requests;
  закрытие Spring context укладывается в обычный короткий интервал без30с предупреждений.
  Сохранить ограничения ownership — timer не должен писать fallback.

### R-TL-2 — P2: итоговая приёмка должна проверять контейнер, а не только транспортные counters

**Подтверждён пробел тестового наблюдения, связан с R-TL-1.**

`ProxyIntegrationTest:70+`, `ResponseOwnershipRaceTest:71+` и аналогичные cleanup
проверяют прикладные счётчики. Их успех не обнаружил active Jetty request.
Добавить наблюдение фактического окончания AsyncContext/request либо явный тест
context close после аварийного exchange. Не считать Gradle exit0 или38passed
доказательством полного lifecycle до анализа shutdown сообщений.

### R-TL-3 — P3: закрыть маленький пробел HTTP-матрицы

`ProxyIntegrationTest:277-345` хорошо проверяет нетипичные GET/HEAD bodies; простой
OPTIONS без body пока отдельно не проверен. Добавить компактный тест forwarding
method/status/Allow/CORS headers без локальной обработки. Это не причина менять
реализацию: `ProxyCommandSupport:25` применяет общий адаптер ко всем методам.

### R-TL-4 — P3: формулировать memory-result согласно измерениям

README уже честно отделяет bounded buffer от zero-copy и RSS. Сохранить эту
формулировку в финальном ответе. Не объявлять P-08/P-09 обязательными оптимизациями
для текущей задачи. При отдельном будущем запросе об оптимальности — сравнить
allocation/RSS/direct memory при одинаковой concurrency и body sizes, включая
TLS и slow peers; текущие tests не являются таким benchmark.

## Что исправлено относительно предыдущего ревью

* Reactor Netty и скрытая агрегация GET/HEAD/DELETE удалены; direct `io.netty`
  Bootstrap/Channel/HttpClientCodec соответствует уточнённому стеку.
* Один response-worker заменил гонку между write и fallback; тест барьером
  проверяет blocked first write и отсутствие reset/другого статуса.
* Локальные setStatus+CL0 и запрет ERROR dispatch убрали проксирование `/error`.
* Возврат permit происходит после join upload task и закрытия backend channel;
  regression проверяет живой writer, а не только interrupt.
* Truncation test теперь получает prefix, исключает HttpTimeoutException и требует
  оперативного обрыва. Есть missing LastHttpContent и close-delimited cases.
* RefCnt проверяются напрямую, не только отсутствием сообщений LEAK.
* Конфигурация валидирована; DNS вынесен на асинхронный Netty resolver; TLS проверяет
  trust/hostname. Это уже достаточная глубина для pet — расширение в портал не нужно.

## Итог

Основная цель request streaming, same-stack и методов достигнута. Сдерживающий
фактор готовности — конкретный Jetty lifecycle issue R-TL-1 и его проверка R-TL-2,
а не недостаток high-level features или необходимость абсолютного zero-copy.
После исправления и успешной финальной проверки shutdown не вижу необходимости
расширять объём задачи обязательными оптимизациями ответа.
