package dev.jsonlens.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Representation of JSON wire data, not a claim about business logic or executed output. */
public final class SchemaNode {
    public final String kind;
    public String format;
    /** Source Java type when known; used only for static diagnostics. */
    public String javaType;
    public String description;
    public boolean nullable;
    public Boolean additionalPropertiesAllowed;
    public SchemaNode additionalPropertiesSchema;
    public SchemaNode items;
    public final Map<String, SchemaNode> properties = new LinkedHashMap<>();
    public final Set<String> required = new LinkedHashSet<>();
    public final List<String> enumValues = new ArrayList<>();

    public SchemaNode(String kind) { this.kind = kind; }
    public static SchemaNode of(String kind) { return new SchemaNode(kind); }
    public SchemaNode field(String name, SchemaNode value, boolean isRequired) {
        properties.put(name, value);
        if (isRequired) required.add(name);
        return this;
    }

    public JsonElement sample(int depth) {
        if (depth > 12) return JsonNull.INSTANCE;
        if (!enumValues.isEmpty()) {
            String first = enumValues.get(0);
            try {
                if (kind.equals("integer")) return new JsonPrimitive(Long.parseLong(first));
                if (kind.equals("number")) return new JsonPrimitive(Double.parseDouble(first));
                if (kind.equals("boolean")) return new JsonPrimitive(Boolean.parseBoolean(first));
            } catch (NumberFormatException ignored) { /* Invalid enum sample; keep original token. */ }
            return new JsonPrimitive(first);
        }
        return switch (kind) {
            case "object" -> {
                JsonObject result = new JsonObject();
                for (var e : properties.entrySet()) result.add(e.getKey(), e.getValue().sample(depth + 1));
                if (additionalPropertiesSchema != null && properties.isEmpty())
                    result.add("chave", additionalPropertiesSchema.sample(depth + 1));
                yield result;
            }
            case "array" -> {
                JsonArray result = new JsonArray();
                if (items != null) result.add(items.sample(depth + 1));
                yield result;
            }
            case "string" -> new JsonPrimitive(switch (format == null ? "" : format) {
                case "uuid" -> "550e8400-e29b-41d4-a716-446655440000";
                case "date" -> "2026-01-01";
                case "date-time" -> "2026-01-01T12:00:00Z";
                case "email" -> "usuario@exemplo.com";
                default -> "exemplo";
            });
            case "integer" -> new JsonPrimitive(1);
            case "number" -> new JsonPrimitive(1.5);
            case "boolean" -> new JsonPrimitive(true);
            default -> JsonNull.INSTANCE;
        };
    }

    public JsonObject toSchema(int depth) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", kind.equals("unknown") ? "unknown" : kind);
        if (format != null) obj.addProperty("format", format);
        if (javaType != null) obj.addProperty("x-wcontract-java-type", javaType);
        if (description != null) obj.addProperty("description", description);
        if (nullable) obj.addProperty("nullable", true);
        if (additionalPropertiesAllowed != null) obj.addProperty("additionalProperties", additionalPropertiesAllowed);
        if (!enumValues.isEmpty()) {
            JsonArray values = new JsonArray();
            enumValues.forEach(values::add);
            obj.add("enum", values);
        }
        if (depth < 12) {
            if (items != null) obj.add("items", items.toSchema(depth + 1));
            if (additionalPropertiesSchema != null) obj.add("additionalProperties", additionalPropertiesSchema.toSchema(depth + 1));
            if (!properties.isEmpty()) {
                JsonObject props = new JsonObject();
                properties.forEach((key, value) -> props.add(key, value.toSchema(depth + 1)));
                obj.add("properties", props);
            }
        }
        if (!required.isEmpty()) {
            JsonArray req = new JsonArray();
            required.forEach(req::add);
            obj.add("required", req);
        }
        return obj;
    }
}
