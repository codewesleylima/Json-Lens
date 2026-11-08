package dev.jsonlens.contract;

import dev.jsonlens.model.SchemaNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContractComparatorTest {
    private final ContractComparator comparator = new ContractComparator();

    @Test void detectsMissingRequiredField() {
        SchemaNode local = SchemaNode.of("object").field("titulo", SchemaNode.of("string"), true);
        SchemaNode external = SchemaNode.of("object").field("titulo", SchemaNode.of("string"), true)
            .field("isbn", SchemaNode.of("string"), true);
        assertTrue(comparator.compare(local, external).stream().anyMatch(f ->
            f.pointer().equals("$.isbn") && f.severity() == ContractComparator.Severity.ERROR));
    }

    @Test void suggestsMovingIdSuffixToMatchRequiredFieldName() {
        SchemaNode local = SchemaNode.of("object").field("idNome", SchemaNode.of("string"), true);
        SchemaNode external = SchemaNode.of("object").field("nomeId", SchemaNode.of("string"), true);

        List<ContractComparator.Finding> findings = comparator.compare(local, external);

        assertTrue(findings.stream().anyMatch(f -> f.severity() == ContractComparator.Severity.WARNING
            && f.message().contains("sufixo \"Id\"") && f.message().contains("\"idNome\"")));
    }

    @Test void detectsIncompatibleTypeAndRestrictedAdditionalProperty() {
        SchemaNode local = SchemaNode.of("object").field("ano", SchemaNode.of("string"), true)
            .field("campoExtra", SchemaNode.of("boolean"), true);
        SchemaNode external = SchemaNode.of("object").field("ano", SchemaNode.of("integer"), true);
        external.additionalPropertiesAllowed = false;
        assertEquals(2, comparator.compare(local, external).stream().filter(f ->
            f.severity() == ContractComparator.Severity.ERROR).count());
    }

    @Test void integerCompatibleWithNumberButNotReverse() {
        assertEquals(ContractComparator.Severity.INFO, comparator.compare(SchemaNode.of("integer"), SchemaNode.of("number")).get(0).severity());
        assertEquals(ContractComparator.Severity.ERROR, comparator.compare(SchemaNode.of("number"), SchemaNode.of("integer")).get(0).severity());
    }

    @Test void validatesArraysRecursively() {
        SchemaNode local = SchemaNode.of("array"); local.items = SchemaNode.of("string");
        SchemaNode external = SchemaNode.of("array"); external.items = SchemaNode.of("integer");
        assertTrue(comparator.compare(local, external).stream().anyMatch(f -> f.pointer().equals("$[]")));
    }

    @Test void detectsNestedRequiredFieldsOptionalityAndEnumMismatch() {
        SchemaNode local = SchemaNode.of("object")
            .field("customer", SchemaNode.of("object").field("name", SchemaNode.of("string"), false), true);
        local.properties.get("customer").properties.get("name").nullable = true;
        local.properties.get("customer").properties.get("name").enumValues.add("unknown");

        SchemaNode external = SchemaNode.of("object")
            .field("customer", SchemaNode.of("object").field("name", SchemaNode.of("string"), true), true);
        external.properties.get("customer").properties.get("name").enumValues.add("known");

        List<ContractComparator.Finding> findings = comparator.compare(local, external);

        assertTrue(findings.stream().anyMatch(f -> f.pointer().equals("$.customer.name")
            && f.severity() == ContractComparator.Severity.WARNING && f.message().contains("null")));
        assertTrue(findings.stream().anyMatch(f -> f.pointer().equals("$.customer.name")
            && f.severity() == ContractComparator.Severity.ERROR && f.message().contains("enum")));
        assertTrue(findings.stream().anyMatch(f -> f.pointer().equals("$.customer.name")
            && f.severity() == ContractComparator.Severity.WARNING && f.message().contains("garantido")));
    }

    @Test void warnsForUnspecifiedAdditionalFieldsAndAcceptsCompatibleShapes() {
        SchemaNode local = SchemaNode.of("object").field("id", SchemaNode.of("integer"), true)
            .field("extra", SchemaNode.of("string"), false);
        SchemaNode external = SchemaNode.of("object").field("id", SchemaNode.of("number"), true);

        List<ContractComparator.Finding> findings = comparator.compare(local, external);

        assertTrue(findings.stream().anyMatch(f -> f.pointer().equals("$.extra")
            && f.severity() == ContractComparator.Severity.WARNING));
        assertFalse(findings.stream().anyMatch(f -> f.severity() == ContractComparator.Severity.ERROR));
    }
}
