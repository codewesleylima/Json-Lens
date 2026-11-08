package dev.jsonlens.contract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CollectionRequestParserTest {
    private final CollectionRequestParser parser = new CollectionRequestParser();

    @Test void parsesNestedPostmanRequestsAndTemplateVariables() {
        var requests = parser.parse("""
            {
              "info":{"schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "item":[{"name":"Autenticação","item":[
                {"name":"Criar cliente","request":{
                  "method":"POST",
                  "url":{"raw":"https://{{apiHost}}/auth/customer/{{customerId}}?source=test"},
                  "body":{"mode":"raw","raw":"{\\"cpf\\":\\"123\\"}"}
                }}
              ]}]
            }
            """);

        assertEquals(1, requests.size());
        assertEquals("Criar cliente", requests.getFirst().name());
        assertEquals("POST", requests.getFirst().method());
        assertEquals("/auth/customer/:customerId", requests.getFirst().path());
        assertEquals("{\"cpf\":\"123\"}", requests.getFirst().body());
    }

    @Test void parsesInsomniaJsonExports() {
        var requests = parser.parse("""
            {
              "_type":"export",
              "resources":[
                {"_type":"request","name":"Buscar cliente","method":"GET",
                 "url":"{{ _.base_url }}/customers/:customerId",
                 "body":{"mimeType":"application/json","text":"{\\"enabled\\":true}"}}
              ]
            }
            """);

        assertEquals(1, requests.size());
        assertEquals("GET", requests.getFirst().method());
        assertEquals("/customers/:customerId", requests.getFirst().path());
        assertEquals("{\"enabled\":true}", requests.getFirst().body());
    }

    @Test void rejectsUnsupportedOrEmptyCollections() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"items\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"item\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{invalid"));
    }
}
