package dev.jsonlens.contract;

import com.google.gson.GsonBuilder;
import dev.jsonlens.model.EndpointSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Generates an offline, copy-ready example; it never executes the HTTP request. */
public final class CurlGenerator {
    private CurlGenerator() {}

    public static String generate(EndpointSnapshot endpoint) {
        if (endpoint == null || endpoint.path == null || endpoint.verb == null)
            throw new IllegalArgumentException("Selecione um endpoint HTTP.");
        String verb = endpoint.verb.equals("ANY") ? "GET" : endpoint.verb;
        String path = endpoint.path.startsWith("/") ? endpoint.path : "/" + endpoint.path;
        List<String> queries = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        for (String note : endpoint.parameterNotes) {
            if (note.startsWith("query: ")) {
                String name = parameterName(note.substring(7));
                if (!name.isBlank()) queries.add(name + "={" + name + "}");
            } else if (note.startsWith("header: ")) {
                String name = parameterName(note.substring(8));
                if (!name.isBlank() && name.matches("[A-Za-z0-9-]+"))
                    headers.add(name + ": <" + name.toUpperCase().replace("-", "_") + ">");
            }
        }
        String url = "{{baseUrl}}" + path +
            (queries.isEmpty() ? "" : "?" + String.join("&", queries));
        StringBuilder out = new StringBuilder("curl --request ").append(verb)
            .append(" \\\n  ").append(shellQuote(url))
            .append(" \\\n  --header ").append(shellQuote("Accept: application/json"));
        for (String header : headers) out.append(" \\\n  --header ").append(shellQuote(header));
        if (!"null".equals(endpoint.request.kind) && !"unknown".equals(endpoint.request.kind)) {
            String example = new GsonBuilder().setPrettyPrinting().create().toJson(endpoint.request.sample(0));
            out.append(" \\\n  --header ").append(shellQuote("Content-Type: application/json"))
                .append(" \\\n  --data ").append(shellQuote(example));
        }
        return out.toString();
    }

    private static String parameterName(String note) {
        int open = note.indexOf('(');
        return (open >= 0 ? note.substring(0, open) : note).trim();
    }

    static String shellQuote(String input) {
        return "'" + input.replace("'", "'\\''") + "'";
    }
}
