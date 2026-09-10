package com.dfs.metadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class HealthMonitor {

    private final ConcurrentHashMap<String, Boolean> alive = new ConcurrentHashMap<>();
    private final List<String> addresses;
    private ScheduledExecutorService scheduler;
    private final HttpClient probeClient;

    public HealthMonitor(List<String> addresses) {
        this.addresses = addresses;
        this.probeClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        for (String addr : addresses) {
            alive.put(addr, true);
        }
    }

    public void start(long periodSeconds) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollAll, 0, periodSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public boolean isAlive(String address) {
        return alive.getOrDefault(address, false);
    }

    public List<String> liveOnly(List<String> addresses) {
        List<String> result = new ArrayList<>();
        for (String addr : addresses) {
            if (isAlive(addr)) {
                result.add(addr);
            }
        }
        return result;
    }

    private void pollAll() {
        for (String addr : addresses) {
            probe(addr);
        }
    }

    private void probe(String addr) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + addr + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = probeClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean nowAlive = response.statusCode() == 200;
            flipIfChanged(addr, nowAlive);
        } catch (Exception e) {
            flipIfChanged(addr, false);
        }
    }

    private void flipIfChanged(String addr, boolean nowAlive) {
        Boolean wasAlive = alive.get(addr);
        if (wasAlive == null || wasAlive != nowAlive) {
            alive.put(addr, nowAlive);
            System.out.println("[health] node " + addr + " -> " + (nowAlive ? "UP" : "DOWN"));
        }
    }
}