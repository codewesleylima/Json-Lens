package dev.jsonlens.contract;

import dev.jsonlens.model.SchemaNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ContractParserTest {
    @TempDir Path temp;
    private final ContractParser parser = new ContractParser();

    @Test void readsJsonSchemaAndRequired() throws Exception {
        Path source = temp.resolve("contract.json");
        Files.writeString(source, """
            {"type":"object","required":["titulo"],"properties":{"titulo":{"type":"string"},"ano":{"type":"integer"}}}
            """);
        var parsed = parser.parse(source, "POST", "/livros", false);
        assertTrue(parsed.node().required.contains("titulo"));
        assertEquals("integer", parsed.node().properties.get("ano").kind);
    }

    @Test void readsOpenApiYamlRef() throws Exception {
        Path source = temp.resolve("openapi.yaml");
        Files.writeString(source, """
            openapi: 3.0.3
            paths:
              /livros:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Item'
            components:
              schemas:
                Item:
                  type: object
                  required: [isbn]
                  properties:
                    isbn: { type: string }
            """);
        var parsed = parser.parse(source, "POST", "/livros", false);
        assertEquals("OpenAPI", parsed.sourceType());
        assertTrue(parsed.node().required.contains("isbn"));
    }

    @Test void detectsExampleJsonAndInfersTypes() throws Exception {
        Path source = temp.resolve("example.json");
        Files.writeString(source, "{\"titulo\":\"Livro\",\"ano\":2026,\"ativo\":true}");
        var parsed = parser.parse(source, "POST", "/livros", false);
        assertEquals("Exemplo JSON", parsed.sourceType());
        assertEquals("boolean", parsed.node().properties.get("ativo").kind);
    }

    @Test void infersRequestContractFromCurlJsonBodyInMemory() {
        var parsed = parser.parseJsonExample("{\"cliente\":{\"nome\":\"Ana\"},\"ativo\":true}");

        assertEquals("Body JSON do cURL", parsed.sourceType());
        assertTrue(parsed.node().required.contains("cliente"));
        assertEquals("string", parsed.node().properties.get("cliente").properties.get("nome").kind);
        assertTrue(parsed.warnings().getFirst().contains("exemplo"));
        assertThrows(IllegalArgumentException.class, () -> parser.parseJsonExample("{invalid"));
        assertThrows(IllegalArgumentException.class, () -> parser.parseJsonExample("[]"));
    }

    @Test void parsesPastedJsonContractWithoutCreatingATemporaryFile() throws Exception {
        var parsed = parser.parseText("""
            {"type":"object","required":["nomeCompleto"],"properties":{"nomeCompleto":{"type":"string"}}}
            """, "contract.json", "POST", "/clientes", false);

        assertEquals("JSON Schema", parsed.sourceType());
        assertTrue(parsed.node().required.contains("nomeCompleto"));
        assertEquals("string", parsed.node().properties.get("nomeCompleto").kind);
    }

    @Test void resolvesNestedCollectionsMapsEnumsAndCircularReferences() throws Exception {
        Path source = temp.resolve("nested-schema.json");
        Files.writeString(source, """
            {
              "$schema":"https://json-schema.org/draft/2020-12/schema",
              "type":"object",
              "required":["items","state"],
              "properties":{
                "items":{"type":"array","items":{"$ref":"#/$defs/Node"}},
                "index":{"type":"object","additionalProperties":{"type":"integer"}},
                "state":{"type":"string","enum":["READY","PENDING"]}
              },
              "$defs":{
                "Node":{
                  "type":"object",
                  "required":["name"],
                  "properties":{
                    "name":{"type":"string"},
                    "children":{"type":"array","items":{"$ref":"#/$defs/Node"}}
                  }
                }
              }
            }
            """);

        var parsed = parser.parse(source, "GET", "/nodes", false);

        assertEquals("array", parsed.node().properties.get("items").kind);
        var item = parsed.node().properties.get("items").items;
        assertTrue(item.required.contains("name"));
        assertEquals("unknown", item.properties.get("children").items.kind);
        assertEquals("integer", parsed.node().properties.get("index").additionalPropertiesSchema.kind);
        assertEquals(List.of("READY", "PENDING"), parsed.node().properties.get("state").enumValues);
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("Referência circular")));
    }

    @Test void readsOpenApiResponseUsingEquivalentPathParameterNames() throws Exception {
        Path source = temp.resolve("responses.yaml");
        Files.writeString(source, """
            openapi: 3.0.3
            paths:
              /livros/{id}:
                get:
                  responses:
                    '200':
                      description: Livro encontrado
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id]
                            properties:
                              id: { type: string }
            """);

        var parsed = parser.parse(source, "GET", "/livros/{livroId}", true);

        assertEquals("string", parsed.node().properties.get("id").kind);
        assertTrue(parsed.node().required.contains("id"));
    }

    @Test void readsFiapLambdaCustomerAuthenticationRequestAndResponse() throws Exception {
        Path source = Path.of("examples", "fiap-lambda-auth-customer-openapi.json");

        var request = parser.parse(source, "POST", "/auth/customer", false);
        assertEquals("OpenAPI", request.sourceType());
        assertTrue(request.node().required.contains("cpf"));
        assertEquals("string", request.node().properties.get("cpf").kind);

        var response = parser.parse(source, "POST", "/auth/customer", true);
        assertEquals("OpenAPI", response.sourceType());
        assertTrue(response.node().required.containsAll(List.of("accessToken", "expiresAt", "tokenType")));
        assertEquals("integer", response.node().properties.get("expiresAt").kind);
        assertEquals(List.of("Bearer"), response.node().properties.get("tokenType").enumValues);
    }
}
