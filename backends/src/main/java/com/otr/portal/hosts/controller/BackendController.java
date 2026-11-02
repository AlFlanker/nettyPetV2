package com.otr.portal.hosts.controller;

import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.health.BackendHealth;
import com.otr.portal.hosts.health.HostsHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.REQUEST_TIMEOUT;

/**
 * @author pobedenniy.alexey
 * @since 22.11.17
 */
@RestController
public class BackendController {
    private HostsService hostsService;
    private HostsHealthService hostsHealthService;


    public BackendController(HostsService hostsService, HostsHealthService hostsHealthService) {
        this.hostsService = hostsService;
        this.hostsHealthService = hostsHealthService;
    }

    @RequestMapping("/admin/meta/backends")
    public ResponseEntity list() {
        return ResponseEntity.ok(Flux.fromIterable(hostsService.getBackends()).map(back -> new BackendView(back.getName(), back.getDescription())).collectList().block());
    }

    @RequestMapping(value = "/admin/backends/check", method = RequestMethod.POST)
    public DeferredResult<ResponseEntity> check(@RequestBody Map<String, String> data) {
        DeferredResult<ResponseEntity> result = new DeferredResult<>();
        hostsHealthService
                .checkBackend("test", null, URI.create(data.get("target")))
                .timeout(Duration.ofMillis(5000), Flux.just(new BackendHealth("test", null, REQUEST_TIMEOUT.value())))
                .subscribe(health -> {
                    if (health.isOK()) {
                        result.setResult(ResponseEntity.ok().build());
                    } else {
                        result.setResult(ResponseEntity.status(BAD_REQUEST).body("Код ответа бэкенда " + health.getCode()));
                    }
                });
        return result;
    }

    private class BackendView {
        private final String name;
        private final String description;

        BackendView(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }
}
