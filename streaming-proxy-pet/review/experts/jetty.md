# Jetty / Servlet review

Scope: read-only source review of original `nettyPetV2/backends` and `streaming-proxy-pet`; original was not built. Confirmed deployed versions inspected by javap: Jetty 12.1.4, Servlet 6.1. Additional runtime probe used installed pet distribution only and local temporary backend; no project files modified.

## J1 — P1, source-confirmed concurrent paths; resulting race needs deterministic reproduction

Locations: pet `ProxyCommand.java:51`, `:63`, `:77`, `:82`; original `ResponseOwnership.java:6`, `:31`.

Original explicitly arbitrates response ownership between backend handling and timeout fallback. Pet sets response status/headers on Netty's loop, writes body on a virtual thread, and independently handles the total timeout on another virtual thread. `isCommitted()` followed by `reset()` is a check-then-act, not ownership or serialization. Cancellation does not synchronously join an already executing blocking servlet write. Timeout near first commit can therefore race backend mutation; after commit dispatch can race outstanding I/O. Do not describe corrupted wire output as reproduced: only code concurrency is established.

Plan: introduce one exchange/response lifecycle contract (minimal atomic states and one terminal action), arbitrate backend/fallback before any response mutation, coordinate abort with active write rather than relying solely on `isCommitted`. Preserve Jetty API compatibility. First add a deterministic latch-controlled integration fixture around first write/commit; trigger timeout before commit and during blocked write; assert either sole 504 or aborted sole backend response, never mixed status/headers/body, no double complete/reset, buffers and tasks eventually released.

## J2 — P2, validation gap in cancellation/admission limit

Locations: pet `ProxyServlet.java:48`, `:63`, `:67`; `ProxyCommandSupport.java:26`, `:38`; tests `ProxyIntegrationTest.java:158`.

`doFinally` releases the admission permit as soon as the publisher terminates/cancels. It does not prove AsyncContext completed or a blocking servlet I/O task actually stopped. Existing client-abort test checks permits and a subsequent request; returning 32 permits can coexist with lingering tasks/connections. This is not a newly demonstrated leak: the older permit leak is fixed.

Plan: explicitly define whether 32 limits logical publishers or physically active exchanges. Add task/connection lifecycle instrumentation in tests, repeated slow-upload/slow-reader cancellation, TCP FIN and RST, total timeout, and server shutdown. Assert actual upstream socket close and stopped read/write workers before declaring resource cleanup. If live tasks can outlast permit return, join/co-ordinate termination or keep a separate bound on servlet I/O resources. Keep AsyncListener and terminal cleanup idempotent.

## J3 — P2, confirmed extra copy and per-chunk flush; improvement requires measurement

Locations: pet `ProxyCommand.java:65` and `:66`; original `streaming/AsyncJettyBodyWriter.java:127`, `:166`, `:178`; original fallback `BlockingBodyWriter.java:36`.

New response path calls ByteBuf.readBytes(OutputStream), so direct Netty buffers are copied through heap by this bridge; then every upstream chunk is flushed. Original normal Jetty writer uses NIO ByteBuffer views and nonblocking HttpOutput, retaining buffers until readiness/callback confirms completion. New virtual-thread path avoids the original blocking fallback's event-loop blocking, but it is not the closest or lowest-copy Jetty path.

Plan: retain current blocking virtual-thread bridge as clear baseline; prototype Servlet6.1/Jetty ByteBuffer output or adapted existing async writer as an isolated optional strategy. Preserve wrappers, readiness rules and ByteBuf lifetime. Benchmark allocation, throughput, slow-client behavior and first-byte latency before choosing. Make flush policy explicit (latency-oriented stream versus coalesced bulk transfer), rather than inheriting arbitrary Netty read chunk boundaries. No promised zero-copy or performance win without measurement.

## J4 — P2, confirmed implementation passes extra chunked-error probe; missing regression coverage

Locations: pet `ProxyServlet.java:26`, `:71`; tests `ProxyIntegrationTest.java:190`, `:257`.

Existing late-abort test declares Content-Length=1000 and closes after a short prefix. Extra raw backend probe sent `200 + Transfer-Encoding: chunked + 5\\r\\nhello\\r\\n` then closed without terminal chunk. Current pet forwarded 200/chunked, and client raised IncompleteRead(5), correctly rejecting successful completion. Probe: `/tmp/jetty_chunked_probe.py`; logs: `/tmp/jetty-chunked-proxy.log`. This is NOT a current failed behavior.

Plan: capture this as permanent regression test; also client abort during response, early backend final response while upload remains incomplete, empty/HEAD/204/304 responses, Expect100 and rejection before reading. Preserve successful EOF for HTTP close-delimited responses: absence of CL/chunk framing means normal connection close marks completion and cannot independently prove application truncation.

## J5 — P3, supported input optimization worth a controlled experiment

Location: pet `ProxyCommandSupport.java:24`–`:26`.

Comments justify heap ByteBuf because ServletInputStream reads byte[]. Servlet6.1 also exposes read(ByteBuffer), and cached Jetty12.1.4 HttpInput overrides it (javap confirmed), copying from its Content.Chunk to target ByteBuffer. Therefore a direct ByteBuf NIO view can avoid the current heap-to-direct transport staging while preserving the same stack. This still copies bytes from Jetty input buffers; it is not cross-stack zero-copy.

Plan: compare current portable heap path and direct ByteBuffer path under exact pinned Jetty version; honor ByteBuffer position/limit and ByteBuf writerIndex, EOF/error/cancel release; keep fallback for wrapping streams whose implementation has different copy behavior. Only adopt if allocation/throughput measurements justify additional complexity.

## Source example observation retained for comparison, not an instruction to modify it

Original BlockingBodyWriter.java36–38 knowingly writes synchronously on Netty event loop for unsupported containers/wrappers. Preserve the pet's rule that blocking servlet I/O is off loop when reusing parts of that writer. Do not transplant fallback unchanged. Original AsyncJettyBodyWriter's wrapper and callback lifecycle is useful as a reference, not automatically proven safe to transplant wholesale.
