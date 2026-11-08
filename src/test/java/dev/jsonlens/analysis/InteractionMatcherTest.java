package dev.jsonlens.analysis;

import dev.jsonlens.contract.HttpRequestSample;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.model.EndpointSnapshot;
import dev.jsonlens.model.SchemaNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InteractionMatcherTest {
    private final InteractionMatcher matcher = new InteractionMatcher();

    @Test void ranksByMethodRouteAndBodyShape() {
        var exact = endpoint("AuthClient.create()", "OUTBOUND (Feign)", "POST", "/auth/customer",
            SchemaNode.of("object").field("cpf", SchemaNode.of("string"), true));
        var other = endpoint("CustomerController.get()", "INBOUND (Controller)", "GET", "/customers/{id}",
            SchemaNode.of("object").field("id", SchemaNode.of("integer"), true));
        var request = new HttpRequestSample("Criar cliente", "POST", "https://api.test/auth/customer",
            "/auth/customer", "{\"cpf\":\"123\"}");

        var matches = matcher.rank(request, List.of(other, exact));

        assertEquals(exact, matches.getFirst().endpoint());
        assertTrue(matches.getFirst().score() > matches.getLast().score());
        assertTrue(matches.getFirst().evidence().stream().anyMatch(reason -> reason.contains("Rota compatível")));
    }

    @Test void capsCandidatesWithDifferentRoutesAtLowConfidenceScores() {
        var endpoint = endpoint("OrdersController.create()", "INBOUND (Controller)", "POST", "/orders",
            SchemaNode.of("object"));
        var request = new HttpRequestSample("Criar cliente", "POST", "https://api.test/customers",
            "/customers", "{\"cpf\":\"123\"}");

        assertTrue(matcher.rank(request, List.of(endpoint)).getFirst().score() < 55);
    }

    @Test void matchesConcreteUrlSegmentsToControllerPathVariables() {
        var endpoint = endpoint("CustomerController.get()", "INBOUND (Controller)", "GET", "/customers/{customerId}",
            SchemaNode.of("null"));
        var request = new HttpRequestSample("Buscar cliente", "GET", "https://api.test/customers/42",
            "/customers/42", null);

        assertEquals(70, matcher.rank(request, List.of(endpoint)).getFirst().score());
    }

    @Test void flagsLikelyWrongValueSourceEvenWhenTheFieldNameAndTypeMatch() {
        var snapshot = new EndpointSnapshot();
        snapshot.title = "CustomerClient.send()";
        snapshot.direction = "OUTBOUND (Feign)";
        snapshot.verb = "POST";
        snapshot.path = "/customers";
        snapshot.request = SchemaNode.of("object").field("nomeCompleto", SchemaNode.of("string"), true);
        var endpoint = new JavaEndpointInspector.EndpointCandidate(snapshot, null, 0,
            List.of(new JavaEndpointInspector.ValueMapping("nomeCompleto", "idade", "customer.getIdade()")));
        var request = new HttpRequestSample("Contrato", "POST", "https://api.test/customers",
            "/customers", "{\"nomeCompleto\":\"42\"}");

        var match = matcher.rank(request, List.of(endpoint)).getFirst();

        assertTrue(match.adherencePercent() < 100);
        assertTrue(match.evidence().stream().anyMatch(line -> line.contains("divergência(s) de valor")));
        assertTrue(match.valueMappings().getFirst().hasDifferentSource());
    }

    @Test void comparesControllerResponseWithPastedResponseContract() {
        var snapshot = new EndpointSnapshot();
        snapshot.title = "CustomerController.create()";
        snapshot.direction = "INBOUND (Controller)";
        snapshot.verb = "POST";
        snapshot.path = "/customers";
        snapshot.request = SchemaNode.of("object");
        snapshot.response = SchemaNode.of("object")
            .field("id", SchemaNode.of("string"), true);
        var endpoint = new JavaEndpointInspector.EndpointCandidate(snapshot, null, 0);
        var contract = new ContractParser().parseJsonExample("{\"id\":42}");

        var review = matcher.compareResponseContract(endpoint, contract);

        assertTrue(review.adherencePercent() < 100);
        assertTrue(review.findings().stream().anyMatch(finding -> finding.pointer().equals("$.id")));
    }

    private static JavaEndpointInspector.EndpointCandidate endpoint(
        String title, String direction, String method, String path, SchemaNode request
    ) {
        EndpointSnapshot snapshot = new EndpointSnapshot();
        snapshot.title = title;
        snapshot.direction = direction;
        snapshot.verb = method;
        snapshot.path = path;
        snapshot.request = request;
        return new JavaEndpointInspector.EndpointCandidate(snapshot, null, 0);
    }
}
