# Техлид: план замечаний к streaming-proxy-pet

Область: read-only review текущего pet и исходного backends, без сборки исходного модуля. Ниже подтвержденные дефекты отделены от проверяемых рисков. Pet должен остаться Servlet/Jetty/Reactor Netty; возврат к Gateway или перенос всего портала не требуется.

## TL-1 — P1: арбитраж ответа между backend и timeout/fallback

* Места: pet `ProxyCommand.java:50-70,77-90`; reference `ResponseOwnership.java:5-10,17-33`.
* По коду подтверждено отсутствие синхронизации/единого владельца. Заголовки ставятся в callback Netty; body write — на servletIo; timeout/fail — на другой задаче servletIo. isCommitted/check/reset не атомарны и отмена reactive subscription не является ожиданием окончания уже выполняющегося servlet write.
* Сценарий: общий timeout пересекается с первыми response headers/первой записью или медленным output. Fallback видит неcommitted, а другой поток начинает запись. Возможны смешанные статус/заголовки/тело, IllegalStateException, обращение к уже завершаемому request.
* План: восстановить минимальную модель ownership и терминальных состояний; сериализовать изменение servlet response и завершение async; после начала отправки разрешать только abort, а не reset/new response. Взять контракт исходного ResponseOwnership, не переносить всю resilience-обвязку.
* Приемка: управляемый барьерами тест одновременного timeout и первого чанка, timeout во время blocked write, cancel сразу перед commit. Должен быть ровно один исход (полный backend response либо определенный error/abort), без записи после terminal state.
* Это подтвержденная гонка по структуре кода; конкретный runtime interleaving техлидом не воспроизведен.

## TL-2 — P1: обещание потоковости для всех методов нужно подтвердить на API библиотеки

* Места: `ProxyCommand.java:46-49`, `README.md:30-47,55`, tests:91-143.
* Netty-эксперт отдельно подтвердил в RN 1.3.0 специальную ветку send(Publisher) с collectList для GET/HEAD/DELETE, включая runtime GET/DELETE probe. Данный пункт объединить с его finding; не считать независимым дубликатом.
* План: выбрать поддерживаемый streaming send API без этой агрегации либо явно ограничить методы с телом; добавить gated DELETE/GET/HEAD tests, fixed Content-Length и chunked. README должен отражать фактически поддержанный контракт.

## TL-3 — P2: возвращенный permit не доказывает окончания физического I/O

* Места: `ProxyServlet.java:48-59,63-72`, `ProxyCommandSupport.java:26,38`, `ProxyCommand.java:62-79`; tests:59-61,158-180.
* Подтвержден факт: cleanup возвращает semaphore при terminal/cancel, не дожидаясь окончания блокирующего I/O. Реальное накопление подвисших virtual threads/соединений именно в этой Jetty-конфигурации пока НЕ доказано.
* План проверки: stalled upload без RST; медленный/нечитающий клиент ответа; timeout; ранний backend response (413) при продолжающемся upload. Проверять завершение read/write задач и закрытие/возврат backend connection, а не только permits. Лишь если проблема воспроизвелась — согласовать выдачу capacity с окончанием I/O, явный container abort или переход на ReadListener/WriteListener.
* Приемка: повторные волны отмен не наращивают выполняющиеся I/O tasks, pooled connections и удержанные buffers; здоровый запрос продолжает работать. Уточнить в README что ограничивает semaphore, без заявления 32 как абсолютного лимита всех сокетов/буферов процесса.

## TL-4 — P2: регрессии в streaming/backpressure и abort требуют более строгих тестов

* Места: tests:109-155 и190-192; README:142-149.
* Gated 16MiB upload доказывает начало forwarding до EOF, но не исключает "передать первый чанк, затем накопить остаток". Тесты не измеряют demand/read-ahead у медленных peers. Это пробел проверки, не доказанная утечка.
* `assertThrows(IOException)` для truncation принимает и HttpTimeoutException, следовательно будущий proxy может зависать до25с и этот тест все еще будет считаться успешным. Текущая короткая длительность успешного прогона не устраняет слабость assertion.
* План: slow backend и slow client с телом больше доступных socket buffers; доказать остановку producer до EOF и ограниченное read-ahead; проверять refCnt/discard целевых buffers. Для truncated response явно получить prefix и проверить своевременный обрыв, исключить timeout как допустимый успех. Добавить chunked response без Content-Length: именно framing должен сообщать недозавершенность.
* Приемка: заведомо агрегирующая/неостанавливающаяся версия проваливает тест; зависание до client timeout не считается корректным abort; после cancel освобождены действительные ресурсы, а не только permit.

## TL-5 — P2: минимальная параметризация таймаутов и лимитов для экспериментов

* Места: `ProxyCommand.java:77`, `ProxyTransport.java:17-25`, `ProxyCommandSupport.java:17`, tests:61,170,174.
* Сейчас total60s, response30s, connect3s и capacity32 зашиты в код; capacity еще и дублируется в тестах. Это ограничение сопровождаемости, а не доказанный дефект обычных запросов.
* План: небольшой валидируемый config record/@ConfigurationProperties для нужных учебному примеру limits/timeouts, явно разделить total deadline и idle timeout; тестам задавать короткие значения. Не добавлять универсальный routing/resilience framework.
* Приемка: неверные/нулевые значения fail-fast; override работает; исходные defaults сохранены и описаны; timeout-регрессии детерминированы и не требуют ждать минуту.

## TL-6 — P3: исправить устаревший комментарий

* `DemoBackend.java:40-41`: "Прокси таких циклов не содержит: поток передаёт Gateway" противоречит фактическому стеку.
* План: описать Flux.generate bridge/HttpClient; согласовать README с итогами TL-2 и TL-3. Приемка: в описании архитектуры нет Gateway и недоказанных обещаний memory/zero-copy.

## Reference-only: исходный модуль остается неизменным

* `ProxyCommandSupport.java:231-234`: при keepAlive=true фильтр оставляет ТОЛЬКО exact-case Connection, отбрасывая Content-Type/Auth/прочие headers. Подтверждено статическим чтением. Внести в сравнительные сценарии pet; не исправлять исходный модуль в этом плане без отдельной задачи.
* `ProxyCommandSupport.java:98-100`: Charset.isSupported(null) бросает IllegalArgumentException для POST без request character encoding. Нужен regression сценарий POST без charset в pet; reference bug не переносить.
* `ProxyCommandSupport.java:77-81,96-156`: полная агрегация и повторное кодирование формы — известный мотив pet, не новая находка. Сохранить byte-for-byte tests.
* `ResponseOwnership.java` и `AsyncJettyBodyWriter.java:11-22,89-101` полезны как исходные контракты владения response/ByteBuf и abort. Упрощение pet не должно убирать эти гарантии.

## Порядок работ

1. Зафиксировать regression tests для TL-1/TL-2 и договориться о поддержке bodies по HTTP-методам.
2. Исправить response lifecycle и streaming send path; затем перепроверить существующие8 cases.
3. Проверить TL-3, расширить backpressure/abort assertions TL-4; исправлять только воспроизведенные проблемы.
4. Минимальная параметризация TL-5 для повторяемых опытов.
5. Обновить комментарии/README и сохранить reference-only замечания отдельным приложением.

Опционально после корректности: измерить throughput/latency flushBuffer каждого чанка (`ProxyCommand.java:66`) и сравнить с контролируемым batching; не менять flush policy до измерений, поскольку первый response chunk должен оставаться своевременным. Production features (auth/HTML rewrite/resilience/observability stack) не входят в обязательный pet-план.
