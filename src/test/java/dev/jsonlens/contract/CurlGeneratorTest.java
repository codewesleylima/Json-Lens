package dev.jsonlens.contract;

import dev.jsonlens.model.EndpointSnapshot;
import dev.jsonlens.model.SchemaNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CurlGeneratorTest {
    @Test void getGeneratesOfflineCurlWithPathQueryAndHeaders() {
        EndpointSnapshot endpoint = new EndpointSnapshot();
        endpoint.verb = "GET";
        endpoint.path = "/api/pedidos/{id}";
        endpoint.request = SchemaNode.of("null");
        endpoint.parameterNotes.add("path: id (String)");
        endpoint.parameterNotes.add("query: versao (Long)");
        endpoint.parameterNotes.add("header: X-Client (String)");
        String curl = CurlGenerator.generate(endpoint);
        assertTrue(curl.contains("curl --request GET"));
        assertTrue(curl.contains("'{{baseUrl}}/api/pedidos/{id}?versao={versao}'"));
        assertTrue(curl.contains("X-Client: <X_CLIENT>"));
        assertFalse(curl.contains("--data"));
    }

    @Test void postIncludesRequestAndEscapesShellQuotes() {
        EndpointSnapshot endpoint = new EndpointSnapshot();
        endpoint.verb = "POST";
        endpoint.path = "/api/pedidos";
        endpoint.request = SchemaNode.of("object");
        endpoint.request.field("nomeCompleto", SchemaNode.of("string"), true);
        String curl = CurlGenerator.generate(endpoint);
        assertTrue(curl.contains("--data"));
        assertTrue(curl.contains("Content-Type: application/json"));
        assertTrue(curl.contains("nomeCompleto"));
        assertEquals("'abc'\\''def'", CurlGenerator.shellQuote("abc'def"));
    }
}
