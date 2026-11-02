package com.otr.portal.hosts.controller.monitoring;

import com.otr.portal.core.metrics.MonitoringMetricsConsumer;
import com.otr.portal.core.metrics.SnapshotDataHolder;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.health.BackendHealthHistory;
import com.otr.portal.hosts.health.HostsHealthService;
import com.otr.portal.hosts.model.monitoring.BackendOverview;
import com.otr.portal.hosts.model.monitoring.BackendResponsesOverview;
import com.otr.portal.monitoring.model.HistogramDataHolder;
import com.otr.portal.monitoring.model.ResponseStatusOverview;
import com.otr.portal.proxy.metrics.MicrometerBackendsMetricsImpl;
import com.otr.portal.proxy.metrics.RequestHistoryBackendsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.DeferredResult;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.otr.portal.proxy.metrics.MicrometerBackendsMetricsImpl.*;
import static java.util.Optional.ofNullable;

/**
 * @author pobedenniy.alexey
 * @since 18.12.18
 */
@Controller
@RequestMapping("/admin/dashboard")
public class HostsMonitoringController extends MonitoringMetricsConsumer {

    private final HostsService hostsService;
    private final MeterRegistry metricRegistry;
    private final HostsHealthService hostsHealthService;
    private final MicrometerBackendsMetricsImpl backendsMetrics;
    private final RequestHistoryBackendsMetrics requestHistoryBackendsMetrics;

    public HostsMonitoringController(HostsService hostsService, MeterRegistry metricRegistry,
                                     HostsHealthService hostsHealthService,
                                     MicrometerBackendsMetricsImpl backendsMetrics,
                                     RequestHistoryBackendsMetrics requestHistoryBackendsMetrics) {
        super(metricRegistry);
        this.hostsService = hostsService;
        this.metricRegistry = metricRegistry;
        this.hostsHealthService = hostsHealthService;
        this.backendsMetrics = backendsMetrics;
        this.requestHistoryBackendsMetrics = requestHistoryBackendsMetrics;
    }

    @RequestMapping(path = "/data/backends")
    @ResponseBody
    public DeferredResult<List<BackendOverview>> backendsData() {
        DeferredResult<List<BackendOverview>> result = new DeferredResult<>();
        Flux
                .fromIterable(hostsService.getBackends())
                .map(backend -> {
                    final long totalRequests = acquireCounter(REQUESTS_METRIC_NAME, BACKEND_TAG, backend.getName()).orElse(0L);
                    final long activeRequests = acquireGauge(ACTIVE_REQUESTS_METRIC_NAME, BACKEND_TAG, backend.getName()).orElse(0L);
                    final long errRequests = acquireCounter(ERR_REQUESTS_METRIC_NAME, BACKEND_TAG, backend.getName()).orElse(0L);
                    String health = hostsHealthService.getHealth(backend.getName()).map(h -> h.isOK() ? "OK" : "FAIL").orElse("-");
                    return new BackendOverview(backend.getName(), backend.isMaintenanceMode(), totalRequests, activeRequests, errRequests, health);
                })
                .collectList()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(result::setResult);
        return result;
    }

    @RequestMapping(path = "/data/backends-history")
    @ResponseBody
    public Map<String, List<BackendHealthHistory.BackendHealthView>> backendsHistoryData() {
        return hostsHealthService.getHealthHistory();
    }

    @RequestMapping(path = "/data/backend-logs")
    @ResponseBody
    public Flux<RequestHistoryBackendsMetrics.RequestHistoryView> backendRequestLog() {
        return requestHistoryBackendsMetrics.view();
    }


    @RequestMapping(path = "/data/responses")
    @ResponseBody
    public DeferredResult<List<BackendResponsesOverview>> responsesData() {
        DeferredResult<List<BackendResponsesOverview>> result = new DeferredResult<>();
        Flux
                .fromIterable(hostsService.getBackends())
                .map(backend -> {
                    SnapshotDataHolder responseTimes = acquireSnapshot(RESPONSE_TIME_METRIC_NAME, BACKEND_TAG, backend.getName())
                            .map(SnapshotDataHolder::new)
                            .orElseGet(SnapshotDataHolder::empty);
                    SnapshotDataHolder responseSize = acquireDSnapshot(RESPONSE_SIZE_METRIC_NAME, BACKEND_TAG, backend.getName())
                            .map(SnapshotDataHolder::new)
                            .orElseGet(SnapshotDataHolder::empty);
                    return new BackendResponsesOverview(backend.getName(), responseTimes, responseSize);
                })
                .collectList()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(result::setResult);
        return result;
    }

    @RequestMapping(path = "/data/responsecodes")
    @ResponseBody
    public DeferredResult<List<ResponseStatusOverview>> responseCode() {
        final DeferredResult<List<ResponseStatusOverview>> result = new DeferredResult<>();
        // новый prometheus-metrics-клиент не имеет filteredMetricFamilySamples(); scrape() возвращает готовые снэпшоты
        MetricSnapshots snapshots = ((PrometheusMeterRegistry) metricRegistry).getPrometheusRegistry().scrape();
        Map<String, List<CounterSnapshot.CounterDataPointSnapshot>> collect = snapshots.stream()
                .filter(snapshot -> snapshot.getMetadata().getPrometheusName().equals("backend_responses_code"))
                .filter(CounterSnapshot.class::isInstance)
                .map(CounterSnapshot.class::cast)
                .flatMap(snapshot -> snapshot.getDataPoints().stream())
                .collect(Collectors.groupingBy(snapshot -> getBackendName(snapshot)));
        Flux
                .fromIterable(hostsService.getBackends())
                .flatMap(backend -> {
                    List<CounterSnapshot.CounterDataPointSnapshot> byBackendName = collect.getOrDefault(backend.getName(), Collections.emptyList());
                    return Flux
                            .fromIterable(byBackendName)
                            .map(counter -> Pair.of(
                                    counter.getLabels().get("status") == null ? "" : counter.getLabels().get("status"),
                                    counter.getValue()))
                            .collect(HashMap::new, (map, pair) -> map.put(pair.getKey(), pair.getValue()))
                            .map(data -> new ResponseStatusOverview(backend.getName(), data));
                })
                .collectList()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(res -> {
                    result.setResult(res);
                });
        return result;
    }

    private String getBackendName(DataPointSnapshot snapshot) {
        String name = snapshot.getLabels().get("backend");
        return name == null ? "" : name;
    }

    @RequestMapping(path = "/data/{backendname}/{type}")
    @ResponseBody
    public DeferredResult<List<HistogramDataHolder>> responseHist(@PathVariable(name = "backendname") String backendName, @PathVariable(name = "type") String type) {
        DeferredResult<List<HistogramDataHolder>> result = new DeferredResult<>();
        Flux<CountAtBucket> statistics;
        switch (type) {
            case "size":
                statistics = ofNullable(backendsMetrics.getSizeHistogram(backendName)).map(HistogramSnapshot::histogramCounts).map(Flux::just).orElse(Flux.empty());
                break;
            case "time":
                statistics = ofNullable(backendsMetrics.getTimeHistogram(backendName)).map(HistogramSnapshot::histogramCounts).map(Flux::just).orElse(Flux.empty());
                break;
            default:
                throw new NotFoundException();
        }

        statistics
                .map(rv -> new HistogramDataHolder(Math.round(rv.bucket()), Math.round(rv.bucket()), Math.round(rv.count())))
                .collectList()
                .subscribe(result::setResult);
        return result;
    }

    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    public class NotFoundException extends RuntimeException {
        NotFoundException() {
            super("Неверный тип гистограммы");
        }
    }
}
