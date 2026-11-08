package dev.jsonlens.contract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CurlRequestParserTest {
    private final CurlRequestParser parser = new CurlRequestParser();

    @Test void parsesMultilineJsonPostWithoutExecutingCommand() {
        String command = "curl --request POST 'https://api.example.test/v1/customers?source=web' \\\n"
            + "  -H 'Content-Type: application/json' --data-raw '{\"name\":\"Ada\"}'";

        var request = parser.parse(command);

        assertEquals("POST", request.method());
        assertEquals("/v1/customers", request.path());
        assertEquals("{\"name\":\"Ada\"}", request.body());
    }

    @Test void infersPostFromDataAndHonorsGetOverride() {
        assertEquals("POST", parser.parse("curl https://api.example.test/items -d '{\"id\":1}'").method());
        assertEquals("GET", parser.parse("curl --get https://api.example.test/items").method());
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("curl --get https://api.example.test/items -d '{\"id\":1}'"));
    }

    @Test void rejectsMissingUrlUnclosedQuotesAndUnsupportedFormEncoding() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("curl -X POST"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("curl 'https://api.example.test/items"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("curl https://api.example.test/items --data-urlencode 'id=1'"));
    }

    @Test void rejectsMultipleBodiesAndNonCurlInput() {
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("curl https://api.example.test/items -d '{}' --data-raw '{}'"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("wget https://api.example.test/items"));
    }
}
