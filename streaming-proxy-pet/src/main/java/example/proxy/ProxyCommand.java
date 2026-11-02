package example.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Один обмен HTTP-запросом и ответом через прямой транспорт Netty.
 * <p>
 * После запуска отдельный виртуальный поток владеет сервлетным ответом: задаёт
 * статус и заголовки, передаёт тело и завершает {@link AsyncContext}. Другой
 * виртуальный поток читает тело запроса и отправляет его в канал. Благодаря
 * этому блокирующий Servlet API не занимает потоки обработки событий Netty,
 * а ранний ответ бэкенда можно принять до окончания загрузки запроса.
 * <p>
 * Обработчики Netty и таймеры только сообщают об ошибке и останавливают ввод-вывод.
 * Они не формируют второй HTTP-ответ параллельно с основным. Разрешение на новый
 * обмен возвращается после остановки загрузки, закрытия канала и очистки очереди.
 */
final class ProxyCommand {
    private static final Logger log = LoggerFactory.getLogger(ProxyCommand.class);
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final AsyncContext async;
    private final ProxyTransport transport;
    final BackendResponseHandler inbound = new BackendResponseHandler(this);
    // Первая причина отмены сохраняется: последующий обрыв канала не должен
    // подменить исходный таймаут или ошибку записи.
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean servletAborted = new AtomicBoolean();
    private final CountDownLatch uploadExited = new CountDownLatch(1);
    private final AtomicBoolean writing = new AtomicBoolean();
    private volatile Channel channel;
    private volatile Thread responseWorker;
    private volatile Thread uploadWorker;
    private volatile boolean stopUpload;
    private volatile boolean uploadComplete;
    private volatile long uploadCompleteAt;
    private volatile boolean finishing;
    // Ошибка контейнера уже запрещает новые записи, но ещё не означает onComplete.
    // Эти события различаются: закрытое соединение всё равно требует завершить AsyncContext.
    private volatile boolean servletEnded;
    private volatile boolean servletCompleted;
    private boolean uploadScheduled;
    private java.util.concurrent.ScheduledFuture<?> totalTimer;

    ProxyCommand(HttpServletRequest request, HttpServletResponse response,
                 AsyncContext async, ProxyTransport transport) {
        this.request = request;
        this.response = response;
        this.async = async;
        this.transport = transport;
    }

    void start() {
        // Учитываем задачу до передачи исполнителю, включая время ожидания её запуска.
        transport.exchanges.add(this);
        transport.ioTasks.incrementAndGet();
        try {
            transport.servletIo.execute(() -> {
                responseWorker = Thread.currentThread();
                try {
                    runExchange();
                } finally {
                    responseWorker = null;
                    transport.ioTasks.decrementAndGet();
                    releaseCapacity();
                }
            });
        } catch (RejectedExecutionException error) {
            transport.ioTasks.decrementAndGet();
            try {
                // Рабочий поток не запущен, поэтому локальный отказ формирует
                // вызывающий поток сервлета; конкурирующего владельца ответа ещё нет.
                response.setStatus(503);
                response.setHeader("Connection", "close");
                response.setContentLength(0);
                finishing = true;
                async.complete();
            } finally {
                releaseCapacity();
            }
        }
    }

    private void runExchange() {
        boolean responseComplete = false;
        try {
            if (transport.closing) throw new RejectedExecutionException("Proxy is stopping");
            checkFailure();
            // Общий срок включает подключение, TLS, загрузку запроса и передачу ответа.
            // Сам таймер не меняет HTTP-ответ: результат оформит его единственный владелец.
            totalTimer = transport.eventLoops.next().schedule(
                    () -> cancel(new TimeoutException("Whole exchange deadline exceeded")),
                    transport.properties.exchangeTimeout().toMillis(), TimeUnit.MILLISECONDS);
            ChannelFuture connection = transport.bootstrap(this).connect(transport.backend.getHost(), transport.port);
            channel = connection.channel();
            checkFailure();
            connection.sync();
            SslHandler ssl = channel.pipeline().get(SslHandler.class);
            if (ssl != null) ssl.handshakeFuture().sync();
            checkFailure();
            startUpload();
            boolean informational = false;
            while (true) {
                Object message = nextResponseObject();
                try {
                    if (message instanceof HttpResponse head) {
                        if (head.status().code() == 101) throw new IOException("Backend upgrade unsupported");
                        // Промежуточные 1xx не заменяют окончательный ответ бэкенда.
                        informational = head.status().code() < 200;
                        if (!informational) {
                            // Бэкенд вправе ответить, например 413, не дочитав запрос.
                            // Прекращаем загрузку, сохраняя возможность передать этот ответ клиенту.
                            if (!uploadComplete) {
                                stopUpload = true;
                                Thread uploader = uploadWorker;
                                if (uploader != null) uploader.interrupt();
                            }
                            checkFailure();
                            response.setStatus(head.status().code());
                            var excluded = ProxyCommandSupport.hopByHop(
                                    head.headers().getAll(HttpHeaderNames.CONNECTION));
                            head.headers().forEach(header -> {
                                if (!excluded.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                                    response.addHeader(header.getKey(), header.getValue());
                                }
                            });
                            // При неизвестной длине явно задаём chunked-кодирование для HTTP/1.1.
                            // Даже с Connection: close отсутствие завершающего чанка должно
                            // позволять клиенту отличить обрыв ответа от нормального конца тела.
                            boolean noBody = "HEAD".equals(request.getMethod())
                                    || head.status().code() == 204 || head.status().code() == 304;
                            if (!noBody && !head.headers().contains(HttpHeaderNames.CONTENT_LENGTH)
                                    && "HTTP/1.1".equals(request.getProtocol())) {
                                response.setHeader("Transfer-Encoding", "chunked");
                            }
                            // В этом примере клиентское соединение также не переиспользуется.
                            // После раннего ответа остаток тела незавершённого запроса
                            // не должен быть принят за следующий запрос на том же соединении.
                            response.setHeader("Connection", "close");
                        }
                    }
                    if (message instanceof HttpContent content && !informational) {
                        if (content.content().isReadable()) writeResponse(content.content());
                        if (message instanceof LastHttpContent) {
                            responseComplete = true;
                            break;
                        }
                    }
                } finally {
                    // Обработчик передал владение сообщением целиком. Освобождение HttpContent
                    // освобождает и его ByteBuf; отдельный release тела здесь был бы повторным.
                    ReferenceCountUtil.release(message);
                }
            }
        } catch (Throwable error) {
            Throwable actual = failure.get();
            if (actual == null) actual = error;
            // Прерывание будило владельца при отмене. Перед оформлением результата
            // снимаем этот сигнал; исходная причина уже сохранена отдельно.
            Thread.interrupted();
            failResponse(actual);
        } finally {
            // Логическое завершение команды ещё не означает остановку ввода-вывода.
            // Сначала прекращаем загрузку и закрываем канал, затем ждём выхода
            // читающего потока и освобождаем сообщения, оставшиеся в очереди.
            finishing = true;
            if (totalTimer != null) totalTimer.cancel(false);
            stopUpload = true;
            Thread uploader = uploadWorker;
            if (uploader != null) uploader.interrupt();
            Channel backend = channel;
            if (backend != null) backend.close().awaitUninterruptibly();
            if (uploadScheduled) awaitUploadExit();
            inbound.releaseQueued();
            if (!servletCompleted) {
                try {
                    // Закрытие сетевого соединения и onError сами по себе не завершают
                    // AsyncContext. После остановки загрузки и канала освобождаем также
                    // асинхронный запрос контейнера; новых записей в HTTP-ответ здесь нет.
                    async.complete();
                } catch (IllegalStateException alreadyEnded) {
                    if (responseComplete) log.debug("Servlet exchange already ended");
                }
            }
        }
    }

    private void startUpload() {
        transport.ioTasks.incrementAndGet();
        uploadScheduled = true;
        try {
            transport.servletIo.execute(() -> {
                uploadWorker = Thread.currentThread();
                try {
                    sendRequest();
                } catch (Throwable error) {
                    // Прерывание по уже принятому раннему ответу не заменяет этот ответ
                    // ошибкой загрузки; самостоятельная ошибка чтения отменяет весь обмен.
                    if (!stopUpload && !finishing) cancel(error);
                } finally {
                    uploadWorker = null;
                    transport.ioTasks.decrementAndGet();
                    uploadExited.countDown();
                }
            });
        } catch (RejectedExecutionException error) {
            transport.ioTasks.decrementAndGet();
            uploadExited.countDown();
            throw error;
        }
    }

    private void sendRequest() throws Exception {
        if (stopUpload) return;
        awaitWritable();
        // Один объект с методом, адресом и заголовками. Тело в него не включается;
        // байтовое представление HTTP-сообщения формирует HttpClientCodec.
        channel.writeAndFlush(ProxyCommandSupport.requestHead(request, transport)).sync();
        long expected = request.getContentLengthLong();
        long count = 0;
        if (ProxyCommandSupport.hasBody(request)) {
            while (!stopUpload) {
                checkFailure();
                awaitWritable();
                if (stopUpload) return;
                ByteBuf chunk = PooledByteBufAllocator.DEFAULT.heapBuffer(
                        transport.properties.chunkSize(), transport.properties.chunkSize());
                boolean handedOff = false;
                try {
                    // ServletInputStream сразу заполняет массив выделенного ByteBuf.
                    // Промежуточный массив и накопление всех порций тела не нужны.
                    int read = request.getInputStream().read(chunk.array(), chunk.arrayOffset(), chunk.capacity());
                    if (read < 0) break;
                    if (stopUpload) return;
                    if (expected >= 0 && count + read > expected) throw new IOException("Request exceeds Content-Length");
                    count += read;
                    chunk.writerIndex(read);
                    // Одновременно ожидается не более одной записи. sync() блокирует
                    // виртуальный поток, а не поток событий Netty. Завершение записи
                    // разрешает следующее чтение, но не доказывает, что бэкенд уже прочитал байты.
                    var message = new DefaultHttpContent(chunk);
                    handedOff = true;
                    channel.writeAndFlush(message).sync();
                } finally {
                    // До передачи сообщения каналу буфер освобождаем сами. После передачи
                    // его освобождением управляет Netty, включая ошибочный результат записи.
                    if (!handedOff) chunk.release();
                }
            }
        }
        if (stopUpload) return;
        // Недочитанный запрос нельзя выдавать за корректный, уменьшив Content-Length.
        // Завершающее сообщение отправляется только после нормального конца исходного тела.
        if (expected >= 0 && count != expected) throw new EOFException("Request ended before Content-Length");
        awaitWritable();
        if (stopUpload) return;
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).sync();
        uploadCompleteAt = System.nanoTime();
        uploadComplete = true;
    }

    private void awaitWritable() throws Exception {
        while (!stopUpload && !channel.isWritable()) {
            checkFailure();
            if (!channel.isActive()) throw new java.nio.channels.ClosedChannelException();
            // channelWritabilityChanged будит единственный читающий поток. Повторная
            // проверка условия учитывает сигнал до засыпания и отмену. Ограничение
            // незавершённых записей действует отдельно от порогов writable-состояния канала.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            if (Thread.interrupted()) throw new InterruptedException("Request upload cancelled");
        }
    }

    void onBackendWritable() {
        Thread uploader = uploadWorker;
        if (uploader != null) LockSupport.unpark(uploader);
    }

    private Object nextResponseObject() throws Exception {
        checkFailure();
        // На каждый запрос чтения FlowControlHandler выдаёт один HttpObject.
        // Следующий запрос делаем после обработки предыдущего объекта: медленная
        // запись клиенту не должна приводить к неограниченному чтению ответа бэкенда.
        channel.read();
        long waitStarted = System.nanoTime();
        while (true) {
            checkFailure();
            Object item = inbound.next(100);
            if (item != null) return item;
            if (uploadComplete || stopUpload) {
                // Ожидание ответа не ограничивает ещё продолжающуюся загрузку запроса.
                // Этот таймаут отсчитывается после её завершения либо раннего ответа;
                // время записи клиенту также не входит в ожидание следующего объекта.
                long start = uploadComplete ? Math.max(waitStarted, uploadCompleteAt) : waitStarted;
                if (System.nanoTime() - start >= transport.properties.readTimeout().toNanos()) {
                    throw new TimeoutException("Backend response idle timeout");
                }
            }
        }
    }

    private void writeResponse(ByteBuf chunk) throws Exception {
        // Отмечаем вход в потенциально блокирующую запись до проверки отмены.
        // Если таймер сработает здесь, он сможет закрыть соединение и разбудить поток.
        writing.set(true);
        try {
            checkFailure();
            chunk.readBytes(response.getOutputStream(), chunk.readableBytes());
            // Немедленная отправка порции даёт клиенту первые данные до конца ответа.
            // Это выбранная политика задержки; она не исключает копий внутри Servlet API.
            response.flushBuffer();
            checkFailure();
        } finally {
            writing.set(false);
        }
    }

    void cancel(Throwable error) {
        if (finishing || !failure.compareAndSet(null, error)) return;
        Channel backend = channel;
        if (backend != null) backend.close();
        Thread uploader = uploadWorker;
        if (uploader != null && uploader != Thread.currentThread()) uploader.interrupt();
        Thread owner = responseWorker;
        if (owner != null && owner != Thread.currentThread()) owner.interrupt();
        // Отмена не меняет статус и заголовки. При активной записи разрываем
        // соединение, чтобы разблокировать поток, который ждёт медленного клиента.
        if (writing.get()) abortServlet(error);
    }

    void onServletComplete() {
        servletCompleted = true;
        servletEnded = true;
        if (!finishing) cancel(new IOException("Client servlet exchange ended"));
    }

    void onServletError(Throwable error) {
        servletEnded = true;
        cancel(error);
    }

    private void checkFailure() throws Exception {
        Throwable error = failure.get();
        if (error instanceof Exception exception) throw exception;
        if (error != null) throw new IOException("Proxy exchange failed", error);
    }

    private void failResponse(Throwable error) {
        if (servletAborted.get() || servletEnded) return;
        if (response.isCommitted()) {
            abortServlet(error);
        } else {
            // Локальную ошибку формирует тот же поток, который обслуживал ответ бэкенда.
            // До отправки заголовков он может заменить подготовленный ответ; после неё
            // допустим только обрыв, а не дописывание второго ответа.
            response.reset();
            int status = error instanceof TimeoutException || error instanceof io.netty.channel.ConnectTimeoutException
                    || error instanceof io.netty.handler.ssl.SslHandshakeTimeoutException
                    ? 504 : error instanceof RejectedExecutionException ? 503 : 502;
            response.setStatus(status);
            response.setHeader("Connection", "close");
            response.setContentLength(0);
            log.debug("Proxy exchange failed before response: {}", error.toString());
        }
    }

    private void abortServlet(Throwable error) {
        if (!servletEnded && servletAborted.compareAndSet(false, true)) {
            // Для HTTP/1.1 закрываем именно сетевое соединение. Jetty state.abort()
            // до отправки заголовков может сформировать собственную HTML-страницу 500.
            // Запись основного ответа уже могла начаться: подменять её вторым ответом нельзя.
            var base = ServletContextResponse.getServletContextResponse(response);
            base.getResponse().getRequest().getConnectionMetaData().getConnection().getEndPoint().close(error);
        }
    }

    private void awaitUploadExit() {
        boolean interrupted = Thread.interrupted();
        try {
            if (!uploadExited.await(transport.properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                // Прерывание не гарантирует выхода из блокирующего ServletInputStream.
                // Если поток не остановился за отведённое время, закрываем соединение
                // и продолжаем ждать его выхода, не возвращая преждевременно разрешение.
                abortServlet(new IOException("Upload did not stop after cancellation"));
                uploadExited.await();
            }
        } catch (InterruptedException error) {
            interrupted = true;
            abortServlet(error);
            while (uploadExited.getCount() != 0) {
                try { uploadExited.await(); } catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void releaseCapacity() {
        // В обычном пути вызов следует за очисткой канала, очереди и выходом обоих
        // рабочих потоков из обслуживаемого ввода-вывода. Флаг защищает от повторного возврата.
        if (released.compareAndSet(false, true)) {
            transport.exchanges.remove(this);
            transport.inFlight.release();
        }
    }
}
