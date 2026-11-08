package dev.jsonlens.contract;

import dev.jsonlens.model.SchemaNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractTemporalTest {
    @Test void localDateTimeWithoutOffsetIsFlaggedAsRiskNotCertainFailure() {
        var producer=SchemaNode.of("object");
        var field=SchemaNode.of("string");
        field.format="date-time";
        field.javaType="java.time.LocalDateTime";
        producer.field("criadoEm",field,true);
        var expected=SchemaNode.of("object");
        var external=SchemaNode.of("string");
        external.format="date-time";
        expected.field("criadoEm",external,true);
        var findings=new ContractComparator().compare(producer,expected);
        assertTrue(findings.stream().anyMatch(x->x.pointer().equals("$.criadoEm")
            && x.severity()==ContractComparator.Severity.WARNING
            && x.message().contains("offset")));
    }
    @Test void zonedDateTimeIsNotMistakenForLocalDateTime() {
        var producer=SchemaNode.of("string");
        producer.javaType="java.time.OffsetDateTime";
        producer.format="date-time";
        var expected=SchemaNode.of("string");
        expected.format="date-time";
        assertTrue(new ContractComparator().compare(producer,expected).stream()
            .noneMatch(x->x.severity()!=ContractComparator.Severity.INFO));
    }
}
