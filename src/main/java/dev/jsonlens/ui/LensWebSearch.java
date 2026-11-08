package dev.jsonlens.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jsonlens.analysis.InteractionMatcher;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.contract.HttpRequestSample;
import java.util.List;
import java.util.function.ToIntFunction;

/** Browser DTOs: match score is not contract adherence, and no HTTP request is ever sent. */
final class LensWebSearch {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    record Result(List<JavaEndpointInspector.EndpointCandidate> endpoints,
                  List<HttpRequestSample> requests,
                  List<List<InteractionMatcher.Match>> matches) {}

    private LensWebSearch() {}

    static JsonObject toJson(String source, Result result,
                             ToIntFunction<JavaEndpointInspector.EndpointCandidate> register) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "search");
        root.addProperty("source", source);
        root.addProperty("scanned", result.endpoints().size());
        JsonArray requests = new JsonArray();
        for (int i = 0; i < result.requests().size(); i++) {
            HttpRequestSample request = result.requests().get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("name", request.name());
            entry.addProperty("method", request.method());
            entry.addProperty("path", request.path());
            JsonArray inbound = new JsonArray(), outbound = new JsonArray();
            int receive = 0, send = 0;
            for (InteractionMatcher.Match match : result.matches().get(i)) {
                boolean feign = match.endpoint().snapshot().direction.startsWith("OUTBOUND");
                if (feign) {
                    if (send++ < 3) outbound.add(matchJson(match, register.applyAsInt(match.endpoint())));
                } else if (receive++ < 3) {
                    inbound.add(matchJson(match, register.applyAsInt(match.endpoint())));
                }
                if (receive >= 3 && send >= 3) break;
            }
            entry.add("inbound", inbound);
            entry.add("outbound", outbound);
            requests.add(entry);
        }
        root.add("requests", requests);
        return root;
    }

    private static JsonObject matchJson(InteractionMatcher.Match match, int id) {
        var endpoint = match.endpoint();
        var snapshot = endpoint.snapshot();
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("title", snapshot.title);
        item.addProperty("method", snapshot.verb);
        item.addProperty("path", snapshot.path);
        item.addProperty("direction", snapshot.direction);
        item.addProperty("score", match.score());
        item.addProperty("adherence", match.adherencePercent());
        item.add("evidence", JSON.toJsonTree(match.evidence()));
        item.add("findings", JSON.toJsonTree(match.bodyFindings()));
        item.add("mappings", JSON.toJsonTree(match.valueMappings()));
        item.add("request", JSON.toJsonTree(snapshot.request.sample(0)));
        item.add("response", JSON.toJsonTree(snapshot.response.sample(0)));
        item.addProperty("file", endpoint.file() == null ? "" : endpoint.file().getName());
        return item;
    }
}
