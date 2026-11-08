package dev.jsonlens.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads Postman v2.1 and Insomnia JSON exports without sending their requests. */
public final class CollectionRequestParser {
    private static final int MAX_BYTES = 5_000_000;
    private static final int MAX_REQUESTS = 200;

    public List<HttpRequestSample> parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Selecione uma collection JSON.");
        if (raw.length() > MAX_BYTES) throw new IllegalArgumentException("Collection acima de 5 MB.");

        JsonElement document;
        try {
            document = JsonParser.parseString(raw);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("O arquivo não contém JSON válido.", ex);
        }
        if (!document.isJsonObject()) throw new IllegalArgumentException("A collection precisa ser um objeto JSON.");

        JsonObject root = document.getAsJsonObject();
        List<HttpRequestSample> requests = new ArrayList<>();
        if (root.has("item") && root.get("item").isJsonArray()) {
            readPostmanItems(root.getAsJsonArray("item"), requests);
        } else if (root.has("resources") && root.get("resources").isJsonArray()) {
            readInsomniaResources(root.getAsJsonArray("resources"), requests);
        } else {
            throw new IllegalArgumentException("Formato não reconhecido. Use export Postman v2.1 ou Insomnia JSON.");
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("A collection não contém requests com URL.");
        if (requests.size() > MAX_REQUESTS)
            throw new IllegalArgumentException("A collection contém mais de 200 requests; divida o arquivo para analisar.");
        return List.copyOf(requests);
    }

    private static void readPostmanItems(JsonArray items, List<HttpRequestSample> requests) {
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonElement nested = item.get("item");
            if (nested != null && nested.isJsonArray()) readPostmanItems(nested.getAsJsonArray(), requests);

            JsonElement requestElement = item.get("request");
            if (requestElement == null || !requestElement.isJsonObject()) continue;
            JsonObject request = requestElement.getAsJsonObject();
            String url = postmanUrl(request.get("url"));
            if (url.isBlank()) continue;
            String method = string(request, "method", "GET").toUpperCase(Locale.ROOT);
            String name = string(item, "name", method + " " + pathFromUrl(url));
            String body = null;
            JsonObject bodyObject = object(request, "body");
            if (bodyObject != null && "raw".equals(string(bodyObject, "mode", "raw")))
                body = string(bodyObject, "raw", null);
            requests.add(new HttpRequestSample(name, method, url, pathFromUrl(url), body));
        }
    }

    private static void readInsomniaResources(JsonArray resources, List<HttpRequestSample> requests) {
        for (JsonElement element : resources) {
            if (!element.isJsonObject()) continue;
            JsonObject resource = element.getAsJsonObject();
            if (!"request".equals(string(resource, "_type", ""))) continue;
            String url = string(resource, "url", "");
            if (url.isBlank()) continue;
            String method = string(resource, "method", "GET").toUpperCase(Locale.ROOT);
            String name = string(resource, "name", method + " " + pathFromUrl(url));
            String body = null;
            JsonObject bodyObject = object(resource, "body");
            if (bodyObject != null) body = string(bodyObject, "text", null);
            requests.add(new HttpRequestSample(name, method, url, pathFromUrl(url), body));
        }
    }

    private static String postmanUrl(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (!value.isJsonObject()) return "";
        JsonObject url = value.getAsJsonObject();
        String raw = string(url, "raw", "");
        if (!raw.isBlank()) return raw;
        JsonElement pathElement = url.get("path");
        if (pathElement == null || !pathElement.isJsonArray()) return "";
        List<String> segments = new ArrayList<>();
        for (JsonElement segment : pathElement.getAsJsonArray()) {
            if (segment.isJsonPrimitive()) segments.add(segment.getAsString());
        }
        return "/" + String.join("/", segments);
    }

    private static String pathFromUrl(String url) {
        String value = url.trim();
        int scheme = value.indexOf("://");
        int start;
        if (scheme >= 0) {
            int authorityEnd = value.indexOf('/', scheme + 3);
            start = authorityEnd < 0 ? value.length() : authorityEnd;
        } else if (value.startsWith("/")) {
            start = 0;
        } else {
            int firstSlash = value.indexOf('/');
            start = firstSlash < 0 ? value.length() : firstSlash;
        }
        String path = value.substring(start);
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        int end = query < 0 ? path.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        path = path.substring(0, end).replaceAll("\\{\\{\\s*([^}]+?)\\s*}}", ":$1");
        return path.isBlank() ? "/" : path;
    }

    private static JsonObject object(JsonObject value, String key) {
        JsonElement element = value.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject value, String key, String fallback) {
        JsonElement element = value.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }
}
