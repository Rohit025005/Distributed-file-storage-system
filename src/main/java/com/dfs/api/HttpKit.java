package com.dfs.api;

import com.dfs.types.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public class HttpKit {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = Logger.getLogger(HttpKit.class.getName());

    @FunctionalInterface
    public interface Endpoint {
        void handle(HttpExchange exchange) throws IOException;
    }

    public static void guard(HttpExchange exchange, Endpoint endpoint) {
        long start = System.currentTimeMillis();
        try {
            endpoint.handle(exchange);
        } catch (ApiException e) {
            sendErrorQuiet(exchange, e.getStatus(), e.getMessage());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sendErrorQuiet(exchange, 500, message);
        } finally {
            long ms = System.currentTimeMillis() - start;
            log.info(String.format("%s %s %d %dms",
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getResponseCode(),
                ms));
        }
    }

    private static void sendErrorQuiet(HttpExchange exchange, int status, String message) {
        try {
            sendJson(exchange, status, new ErrorResponse(message, null));
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }

    public static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, new ErrorResponse(message, null));
    }

    public static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return null;
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes());
    }

    public static Map<String, String> errorMap(String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
}