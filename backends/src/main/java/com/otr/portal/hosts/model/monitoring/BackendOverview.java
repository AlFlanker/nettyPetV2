package com.otr.portal.hosts.model.monitoring;

/**
 * @author pobedenniy.alexey
 * @since 20.12.17
 */
public class BackendOverview {
    private String name;
    private boolean maintenanceMode;
    private long totalRequests;
    private long activeRequests;
    private long errorRequests;
    private String backendHealth;

    public BackendOverview(String name, boolean maintenanceMode, long totalRequests, long activeRequests, long errorRequests, String backendHealth) {
        this.name = name;
        this.maintenanceMode = maintenanceMode;
        this.totalRequests = totalRequests;
        this.activeRequests = activeRequests;
        this.errorRequests = errorRequests;
        this.backendHealth = backendHealth;
    }

    public String getName() {
        return name;
    }

    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public long getActiveRequests() {
        return activeRequests;
    }

    public long getErrorRequests() {
        return errorRequests;
    }

    public String getBackendHealth() {
        return backendHealth;
    }
}
