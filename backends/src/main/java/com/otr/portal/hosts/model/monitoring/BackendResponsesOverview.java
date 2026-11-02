package com.otr.portal.hosts.model.monitoring;

import com.otr.portal.core.metrics.SnapshotDataHolder;

/**
 * @author pobedenniy.alexey
 * @since 20.12.17
 */
public class BackendResponsesOverview {
    private final String name;
    private SnapshotDataHolder responseTime;
    private SnapshotDataHolder responseSize;

    public BackendResponsesOverview(String name, SnapshotDataHolder responseTime, SnapshotDataHolder responseSize) {
        this.name = name;
        this.responseTime = responseTime;
        this.responseSize = responseSize;
    }

    public String getName() {
        return name;
    }

    public SnapshotDataHolder getResponseSize() {
        return responseSize;
    }

    public SnapshotDataHolder getResponseTime() {
        return responseTime;
    }
}
