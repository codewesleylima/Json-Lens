package dev.jsonlens.contract;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jsonlens.model.SchemaNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Supports JSON examples, JSON Schema, and OpenAPI 3.x JSON/YAML (local $refs). */
public final class ContractParser {
    public record Parsed(SchemaNode node, String sourceType, List<String> warnings) {}
    private final Gson gson = new Gson();

    public Parsed parseJsonExample(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("O cURL não contém um body JSON.");
        if (raw.length() > 2_000_000) throw new IllegalArgumentException("Body JSON do cURL acima de 2 MB.");

        final JsonElement document;
        try {
            document = JsonParser.parseString(raw);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("O body do cURL não é um JSON válido.", ex);
        }
        if (!document.isJsonObject()) throw new IllegalArgumentException("O body do cURL deve ser um objeto JSON.");

        return new Parsed(inferFromExample(document, 0), "Body JSON do cURL",
            List.of("Body tratado como exemplo; campos presentes são considerados esperados, mas obrigatoriedade formal não pode ser inferida."));
    }

    public Parsed parse(Path path, String httpMethod, String endpointPath, boolean response) throws Exception {
        if (Files.size(path) > 2_000_000) throw new IllegalArgumentException("Contrato acima de 2 MB; use um arquivo menor.");
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        return parseText(raw, path.toString(), httpMethod, endpointPath, response);
    }

    public Parsed parseText(String raw, String sourceName, String httpMethod, String endpointPath, boolean response) throws Exception {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Cole um contrato JSON ou YAML.");
        if (raw.length() > 2_000_000) throw new IllegalArgumentException("Contrato acima de 2 MB; use um arquivo menor.");
        JsonElement document;
        if (sourceName.toLowerCase(Locale.ROOT).matches(".*\\.(yaml|yml)$")) {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(30);
            options.setCodePointLimit(2_000_000);
            Object yaml = new Yaml(new SafeConstructor(options)).load(raw);
            document = gson.toJsonTree(yaml);
        } else document = JsonParser.parseString(raw);
        if (document == null || !document.isJsonObject())
            throw new IllegalArgumentException("O arquivo deve conter um objeto JSON ou YAML.");
        JsonObject root = document.getAsJsonObject();
        List<String> warnings = new ArrayList<>();
        if (root.has("openapi") || root.has("swagger")) {
            JsonObject paths = object(root, "paths");
            if (paths == null) throw new IllegalArgumentException("OpenAPI não contém paths.");
            JsonObject operationPath = findOperationPath(paths, endpointPath);
            if (operationPath == null) throw new IllegalArgumentException("Path não encontrado no contrato: " + endpointPath);
            JsonObject operation = object(operationPath, httpMethod.toLowerCase(Locale.ROOT));
            if (operation == null) throw new IllegalArgumentException("Método " + httpMethod + " ausente no contrato para " + endpointPath);
            JsonObject schema;
            if (response) {
                JsonObject responses = object(operation, "responses");
                if (responses == null) throw new IllegalArgumentException("Operação sem responses.");
                JsonObject chosen = null;
                for (String code : List.of("200", "201", "202", "204", "default")) {
                    chosen = object(responses, code);
                    if (chosen != null) break;
                }
                if (chosen == null) throw new IllegalArgumentException("Não existe resposta 2xx/default suportada.");
                schema = contentSchema(chosen);
                if (schema == null && responses.has("204")) return new Parsed(SchemaNode.of("null"), "OpenAPI", warnings);
            } else {
                JsonObject requestBody = object(operation, "requestBody");
                if (requestBody != null && requestBody.has("$ref")) requestBody = resolveRef(root, requestBody, new LinkedHashSet<>(), warnings);
                schema = contentSchema(requestBody);
            }
            if (schema == null) throw new IllegalArgumentException("Não há application/json/schema para a direção selecionada.");
            return new Parsed(parseSchema(schema, root, new LinkedHashSet<>(), warnings, 0), "OpenAPI", warnings);
        }
        if (root.has("$schema") || root.has("properties") || root.has("type") || root.has("$ref") || root.has("allOf"))
            return new Parsed(parseSchema(root, root, new LinkedHashSet<>(), warnings, 0), "JSON Schema", warnings);
        warnings.add("JSON de exemplo: todos os campos presentes foram tratados como esperados; o arquivo não define obrigatoriedade formal.");
        return new Parsed(inferFromExample(root, 0), "Exemplo JSON", warnings);
    }

    private JsonObject findOperationPath(JsonObject paths, String target) {
        if (paths.has(target)) return object(paths, target);
        String normalized = target.replaceAll("\\{[^}]+}", "{}");
        for (String key : paths.keySet())
            if (key.replaceAll("\\{[^}]+}", "{}").equals(normalized)) return object(paths, key);
        return null;
    }

    private JsonObject contentSchema(JsonObject requestOrResponse) {
        JsonObject content = object(requestOrResponse, "content");
        if (content == null) return null;
        JsonObject media = object(content, "application/json");
        if (media == null) for (String key : content.keySet()) {
            if (key.endsWith("+json") || key.equals("*/*")) { media = object(content, key); break; }
        }
        return object(media, "schema");
    }

    private SchemaNode parseSchema(JsonObject schema, JsonObject root, Set<String> seen, List<String> warnings, int depth) {
        if (depth > 20) { warnings.add("Profundidade máxima do contrato externo excedida."); return SchemaNode.of("unknown"); }
        if (schema.has("$ref")) {
            String ref = string(schema, "$ref", "");
            if (!seen.add(ref)) { warnings.add("Referência circular: " + ref); return SchemaNode.of("unknown"); }
            JsonObject resolved = resolveRef(root, schema, seen, warnings);
            SchemaNode node = resolved == null ? SchemaNode.of("unknown") : parseSchema(resolved, root, seen, warnings, depth + 1);
            seen.remove(ref);
            return node;
        }
        if (schema.has("allOf") && schema.get("allOf").isJsonArray()) {
            SchemaNode merged = SchemaNode.of("object");
            for (JsonElement e : schema.getAsJsonArray("allOf")) {
                if (!e.isJsonObject()) continue;
                SchemaNode branch = parseSchema(e.getAsJsonObject(), root, seen, warnings, depth + 1);
                merged.properties.putAll(branch.properties);
                merged.required.addAll(branch.required);
                if (branch.additionalPropertiesAllowed != null) merged.additionalPropertiesAllowed = branch.additionalPropertiesAllowed;
            }
            // Own properties / required may be added alongside allOf.
            JsonObject rest = schema.deepCopy();
            rest.remove("allOf");
            SchemaNode own = parseSchema(rest, root, seen, warnings, depth + 1);
            merged.properties.putAll(own.properties);
            merged.required.addAll(own.required);
            return merged;
        }
        if (schema.has("oneOf") || schema.has("anyOf")) {
            String key = schema.has("oneOf") ? "oneOf" : "anyOf";
            warnings.add(key + " contém alternativas: apenas a primeira foi analisada (validação parcial).");
            JsonArray choices = schema.getAsJsonArray(key);
            if (!choices.isEmpty() && choices.get(0).isJsonObject())
                return parseSchema(choices.get(0).getAsJsonObject(), root, seen, warnings, depth + 1);
        }
        String type = string(schema, "type", "");
        if (schema.has("type") && schema.get("type").isJsonArray()) {
            for (JsonElement t : schema.getAsJsonArray("type")) if (t.isJsonPrimitive() && !t.getAsString().equals("null")) { type = t.getAsString(); break; }
        }
        if (type.isEmpty() && schema.has("properties")) type = "object";
        if (type.isEmpty() && schema.has("items")) type = "array";
        if (type.isEmpty()) type = "unknown";
        SchemaNode result = SchemaNode.of(type);
        result.format = string(schema, "format", null);
        result.description = string(schema, "description", null);
        result.nullable = bool(schema, "nullable") || isNullableTypeArray(schema);
        JsonArray enums = array(schema, "enum");
        if (enums != null) for (JsonElement e : enums) if (!e.isJsonNull()) result.enumValues.add(e.getAsString());
        if (type.equals("array")) {
            JsonObject items = object(schema, "items");
            if (items != null) result.items = parseSchema(items, root, seen, warnings, depth + 1);
        }
        if (type.equals("object")) {
            JsonArray req = array(schema, "required");
            if (req != null) for (JsonElement e : req) result.required.add(e.getAsString());
            JsonObject properties = object(schema, "properties");
            if (properties != null) for (String name : properties.keySet()) {
                JsonObject property = object(properties, name);
                if (property != null) result.field(name, parseSchema(property, root, seen, warnings, depth + 1), result.required.contains(name));
            }
            if (schema.has("additionalProperties")) {
                JsonElement additional = schema.get("additionalProperties");
                if (additional.isJsonPrimitive() && additional.getAsJsonPrimitive().isBoolean())
                    result.additionalPropertiesAllowed = additional.getAsBoolean();
                else if (additional.isJsonObject()) result.additionalPropertiesSchema = parseSchema(additional.getAsJsonObject(), root, seen, warnings, depth + 1);
            }
        }
        return result;
    }

    private JsonObject resolveRef(JsonObject root, JsonObject refObj, Set<String> seen, List<String> warnings) {
        String ref = string(refObj, "$ref", "");
        if (!ref.startsWith("#/")) { warnings.add("$ref externo não suportado: " + ref); return null; }
        JsonElement cursor = root;
        for (String token : ref.substring(2).split("/")) {
            String name = token.replace("~1", "/").replace("~0", "~");
            if (!cursor.isJsonObject() || !cursor.getAsJsonObject().has(name)) {
                warnings.add("$ref não encontrado: " + ref); return null;
            }
            cursor = cursor.getAsJsonObject().get(name);
        }
        return cursor.isJsonObject() ? cursor.getAsJsonObject() : null;
    }

    private SchemaNode inferFromExample(JsonElement element, int depth) {
        if (depth > 16 || element == null || element instanceof JsonNull) return SchemaNode.of("unknown");
        if (element.isJsonObject()) {
            SchemaNode object = SchemaNode.of("object");
            for (var e : element.getAsJsonObject().entrySet())
                object.field(e.getKey(), inferFromExample(e.getValue(), depth + 1), true);
            return object;
        }
        if (element.isJsonArray()) {
            SchemaNode array = SchemaNode.of("array");
            if (!element.getAsJsonArray().isEmpty()) array.items = inferFromExample(element.getAsJsonArray().get(0), depth + 1);
            return array;
        }
        if (element.getAsJsonPrimitive().isBoolean()) return SchemaNode.of("boolean");
        if (element.getAsJsonPrimitive().isNumber()) return SchemaNode.of(element.getAsString().matches("-?\\d+") ? "integer" : "number");
        return SchemaNode.of("string");
    }

    private static boolean isNullableTypeArray(JsonObject object) {
        JsonArray values = array(object, "type");
        if (values == null) return false;
        for (JsonElement e : values) if (e.isJsonPrimitive() && "null".equals(e.getAsString())) return true;
        return false;
    }
    private static JsonObject object(JsonObject from, String key) {
        if (from == null || !from.has(key) || !from.get(key).isJsonObject()) return null;
        return from.getAsJsonObject(key);
    }
    private static JsonArray array(JsonObject from, String key) {
        if (from == null || !from.has(key) || !from.get(key).isJsonArray()) return null;
        return from.getAsJsonArray(key);
    }
    private static String string(JsonObject from, String key, String fallback) {
        return from == null || !from.has(key) || !from.get(key).isJsonPrimitive() ? fallback : from.get(key).getAsString();
    }
    private static boolean bool(JsonObject from, String key) {
        return from != null && from.has(key) && from.get(key).isJsonPrimitive() && from.get(key).getAsJsonPrimitive().isBoolean() && from.get(key).getAsBoolean();
    }
}
