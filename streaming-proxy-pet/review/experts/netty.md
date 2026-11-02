# Netty / Reactor Netty expert review

Scope: read-only source review of `/workspace/nettyPetV2/backends` and `/workspace/streaming-proxy-pet`. Original module was neither compiled nor modified. Pet source was not modified. Runtime experiments used its installed distribution and optional single-class prototypes under `/tmp`.

## N1 — P1: hidden full-body aggregation for GET, HEAD and DELETE (confirmed)

Location: `streaming-proxy-pet/src/main/java/example/proxy/ProxyCommand.java:49`; contradictory contract in README method-comparison and bounded-memory paragraphs.

Reactor Netty 1.3.0 `HttpClientOperations.send(Publisher)` checks GET/HEAD/DELETE and runs `Flux.collectList()` before writing. The application's Flux does not guarantee streaming when the selected library API aggregates downstream. A body of arbitrary length is retained; upstream receives no first chunk until EOF. DELETE-with-body is a practical use case; GET/HEAD body semantics may be intentionally restricted but such a restriction is currently absent.

Evidence:
- `/tmp/httpclientoperations.javap` (lines 865 onward) and `/tmp/httpclientoperations.verbose`: actual cached reactor-netty-http-1.3.0.jar bytecode, GET/HEAD/DELETE branch invokes collectList.
- `/tmp/netty-review-stream.py`: start unmodified installed pet on ephemeral port, POST/DELETE/GET with Content-Length=131072, send first65536 bytes and wait for backend observation before sending remainder.
- Results: POST first_chunk_before_EOF=True, DELETE=False, GET=False; all ultimately HTTP200.

Plan:
1. Define method/body contract explicitly (HEAD request bodies included or rejected with deliberate status).
2. Use a nonaggregating supported outbound API; changing only to `.send((req,out) -> out.send(body))` does NOT fix it.
3. Tested prototype: `.send((req,out) -> out.send(body, chunk -> true))` invokes inherited two-argument ChannelOperations.send and streams known-length POST/DELETE/GET. `chunk -> true` is a deliberately simple flush policy, not a performance recommendation.
4. Couple API change to regenerated framing. Two-argument send alone loses unknown-length GET/DELETE bodies: backend sees neither Content-Length nor Transfer-Encoding and reads0 bytes even though final status200. Public HttpClient/HttpClientRequest 1.3.0 do not expose chunkedTransfer(boolean). Prototype sets `req.header("Transfer-Encoding", "chunked")` in the BiFunction when servlet length is unknown, then calls two-argument send. This streams POST/DELETE/GET correctly with131072 bytes and TE:chunked. Production implementation must decide whether a body is present and avoid contradictory CL/TE, rather than blindly retaining a client hop-by-hop header.
5. Add parameterized integration matrix: POST/PUT/PATCH/DELETE plus intended GET/HEAD policy; known CL and unknown/chunked; empty body; first chunk before EOF; exact length/digest; no contradictory framing; no replay on failure.
6. Update README only after these tests pass.

Prototype evidence: `/tmp/netty-review-src/example/proxy/ProxyCommand.java` (final isolated prototype); `/tmp/netty-review-classes`; `/tmp/netty-review-fix.py` (known length), `/tmp/netty-review-fix-chunked.py` (unknown length). Final unknown-length output: POST/DELETE/GET each first_chunk_before_EOF=True, TE=chunked, CL=None, bytes131072, HTTP200. These files are experiments, not applied changes.

## N2 — P1/P2: timeout fallback lacks response ownership arbitration (structural, runtime reproduction pending)

Locations: ProxyCommand.java:50-74 vs :77-90. Normal response headers/writes occur on event-loop/servlet virtual worker, while total timeout cancels upstream and schedules fail() on a different virtual task. Cancellation does not wait for an already executing blocking servlet write to return. `isCommitted()` then reset/setStatus is a time-of-check/time-of-use race, not an ownership primitive. The original module explicitly solved this class of race in `ResponseOwnership.java:6-10,31-33`; the pet omitted it.

Plan: model exactly-one terminal/response owner and serialize servlet response mutations; define abort after normal-response ownership and after commit; do not append fallback body or reset concurrently. Add deterministic latch-based tests for timeout competing with first backend response header/write and blocked output. Verify single status/body, deliberate connection abort where necessary, no IllegalStateException/refcount warnings, and cleanup. Choose minimal implementation appropriate for pet rather than transplanting entire resilience stack. Root/tech-lead reviewer is consolidating this finding.

## N3 — P2: tests demonstrate streaming but not bounded-memory/refcount behavior under slow consumers (coverage improvement)

Locations: ProxyCommandSupport.java:20-39; ProxyCommand.java:62-74; ProxyIntegrationTest.largeChunkedUploadReachesBackendBeforeProducerCanFinish.

The gated16MiB test excludes whole-body buffering for POST only. It does not show that producer demand stops when backend/client stalls, nor prove request/response ByteBufs return to refCnt0 after cancellation. PARANOID logging without forced collection/allocation tracking is not such a proof. Existing retain -> publishOn(prefetch1) -> release plus discard is a reasonable ownership pattern; no demonstrated double release or leak in this path was found.

Plan: targeted slow-backend and slow-client tests with bounded progress assertions, early backend413 while upload continues, cancellation with queued response chunk, and tracked-buffer ownership or allocator instrumentation where practical. Keep a benchmark separate and optional; no claim of zero-copy or measured RSS bound until measured. Original test utilities under backends/src/test/.../rewriters/bytes/TrackingAllocator.java and memory/AllocationProbe.java may inspire instrumentation without building original module.

## N4 — P3: flush-on-every-received-chunk is a performance tradeoff, not established defect

Location ProxyCommand.java:66. Flushing every chunk demonstrates low latency but can increase writes/system calls for fragmented backend output. Plan only after correctness work: benchmark representative small chunks and large transfers, compare bounded batch/time flush strategies without violating first-byte-latency/streaming requirements. Do not silently remove flush and break gated response test.

## Reference-only original findings (separate backlog; no changes authorized here)

- R1 P1/static: original ProxyCommandSupport.java:231-233, when keepAlive=true filter keeps ONLY exactly-cased Connection and drops all other collected headers. Plan a separate original-module fix for HTTP header selection/connection semantics and tests covering Authorization, Content-Type, multiple header values and casing. Pet must not copy this pattern.
- R2 P2/static: original ProxyCommandSupport.java:98 passes nullable servlet getCharacterEncoding() into Charset.isSupported; null throws IllegalArgumentException. Trigger: POST without charset and without earlier filter assigning one. Plan a separate null/invalid charset fallback fix if original is later authorized; pet's byte-preserving path should remain charset independent and retain no-charset regression.
- Known motivating behavior: original Support.java:77-81 + RequestBodyReader.java:30-36 retains entire body and copies heap chunks into direct components; Support.java:96-156 may reconstruct form/multipart. Record as architectural baseline, not a new regression introduced by pet.

Positive choices worth preserving: fixed target origin, no automatic retry/redirect/decompression, request source runs outside Netty event loop, pooled heap chunks avoid an extra application staging array, response retain/release across scheduler boundary is explicit, tests exercise real sockets and payload equality. None establishes complete proxy correctness without the missing cases above.
