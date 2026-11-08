package dev.jsonlens.analysis;

import dev.jsonlens.model.EndpointSnapshot;
import dev.jsonlens.model.SchemaNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WContractAssessmentTest {
    private static JavaEndpointInspector.EndpointCandidate candidate() {
        EndpointSnapshot endpoint = new EndpointSnapshot();
        endpoint.title = "PedidoController.criar()";
        endpoint.verb = "POST";
        endpoint.path = "/api/pedidos";
        endpoint.direction = "INBOUND (Controller)";
        endpoint.request = SchemaNode.of("object");
        endpoint.request.field("clienteId", SchemaNode.of("string"), true);
        endpoint.response = SchemaNode.of("null");
        return new JavaEndpointInspector.EndpointCandidate(endpoint, null, 0);
    }

    @Test void noContractMeansNotVerifiedNotPerfectCompatibility() {
        var report = new WContractAssessment().evaluate(List.of(candidate()),
            new WContractGraph.Graph(List.of(),0), List.of(), "isolated", (n,total,stage)->{});
        assertEquals(-1, report.summary().compatibility);
        assertEquals(0, report.summary().evaluated);
        assertEquals("NOT_VERIFIED", report.endpoints().getFirst().status());
    }

    @Test void missingRequiredPropertyIsReportedWithEvidence() {
        var endpoint = candidate();
        String jsonSchema = "{\"type\":\"object\",\"properties\":{\"pedidoId\":{\"type\":\"string\"}},\"required\":[\"pedidoId\"]}";
        var binding = new WContractProjectState.Binding(
            WContractProjectState.key(endpoint.snapshot()),"consumer.schema.json",jsonSchema,false);
        var report = new WContractAssessment().evaluate(List.of(endpoint),
            new WContractGraph.Graph(List.of(),0),List.of(binding),"isolated",(n,total,stage)->{});
        assertEquals(1, report.summary().evaluated);
        assertEquals(1, report.summary().total);
        assertTrue(report.summary().errors > 0);
        assertEquals("INCOMPATIBLE",report.endpoints().getFirst().status());
        assertTrue(report.risks().stream().anyMatch(r->r.rule().equals("SCHEMA_ERROR")));
    }

    @Test void suspiciousValueMappingShowsSuspectedRatherThanConfirmedRisk() {
        var endpoint = candidate();
        endpoint.snapshot().direction = "OUTBOUND (Feign)";
        var mapped = new JavaEndpointInspector.EndpointCandidate(endpoint.snapshot(), null, 0,
            List.of(new JavaEndpointInspector.ValueMapping("nomeCompleto","idade",
                "cliente.getIdade()","/projeto/PagamentoMapper.java",32)));
        var report = new WContractAssessment().evaluate(List.of(mapped),
            new WContractGraph.Graph(List.of(),0), List.of(), "isolated",(n,total,stage)->{});
        assertEquals(1,report.summary().suspected);
        assertTrue(report.risks().stream().anyMatch(r->r.rule().equals("VALUE_ORIGIN")
            && r.confidence().equals("SUSPECTED") && r.offset()==32));
    }
}
