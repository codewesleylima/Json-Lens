package dev.jsonlens.contract;

import dev.jsonlens.model.SchemaNode;
import java.util.ArrayList;
import java.util.List;

/** Checks whether a locally declared payload can satisfy a receiver's contract. */
public final class ContractComparator {
    public enum Severity { ERROR, WARNING, INFO }
    public record Finding(Severity severity, String pointer, String message) {
        @Override public String toString() { return severity + " " + pointer + " — " + message; }
    }

    public List<Finding> compare(SchemaNode actual, SchemaNode expected) {
        List<Finding> result = new ArrayList<>();
        compareNodes(actual, expected, "$", result, 0);
        if (result.isEmpty()) result.add(new Finding(Severity.INFO, "$", "Nenhuma incompatibilidade estrutural identificada. Isso não substitui um teste de integração."));
        return result;
    }

    private void compareNodes(SchemaNode actual, SchemaNode expected, String pointer, List<Finding> out, int depth) {
        if (depth > 16) { out.add(new Finding(Severity.WARNING, pointer, "Profundidade máxima atingida; verificação parcial.")); return; }
        if (expected.kind.equals("unknown") || actual.kind.equals("unknown")) {
            out.add(new Finding(Severity.WARNING, pointer, "Tipo não resolvido; compatibilidade não comprovada (" + actual.kind + " → " + expected.kind + ")."));
            return;
        }
        boolean typesMatch = expected.kind.equals(actual.kind) || (actual.kind.equals("integer") && expected.kind.equals("number"));
        if (!typesMatch) {
            out.add(new Finding(Severity.ERROR, pointer, "Tipo incompatível: projeto envia " + actual.kind + ", contrato exige " + expected.kind + "."));
            return;
        }
        if (!expected.nullable && actual.nullable) {
            out.add(new Finding(Severity.WARNING, pointer, "Projeto permite null, mas o contrato não declara null como aceito."));
        }
        if (!expected.enumValues.isEmpty()) {
            if (actual.enumValues.isEmpty()) {
                out.add(new Finding(Severity.WARNING, pointer, "Não foi possível provar que todos os valores enviados respeitam o enum externo."));
            } else {
                for (String value : actual.enumValues) if (!expected.enumValues.contains(value))
                    out.add(new Finding(Severity.ERROR, pointer, "Valor de enum não aceito pelo contrato: " + value));
            }
        }
        if (actual.kind.equals("array") && actual.items != null && expected.items != null)
            compareNodes(actual.items, expected.items, pointer + "[]", out, depth + 1);
        if (actual.kind.equals("object")) {
            for (String requiredName : expected.required) {
                if (!actual.properties.containsKey(requiredName))
                    out.add(new Finding(Severity.ERROR, pointer + "." + requiredName, "Campo obrigatório exigido pelo receptor não existe no payload declarado."));
                else if (!actual.required.contains(requiredName))
                    out.add(new Finding(Severity.WARNING, pointer + "." + requiredName, "Campo exigido pelo receptor não está garantido como presente pelo contrato local."));
            }
            for (var entry : actual.properties.entrySet()) {
                SchemaNode wanted = expected.properties.get(entry.getKey());
                if (wanted != null) compareNodes(entry.getValue(), wanted, pointer + "." + entry.getKey(), out, depth + 1);
                else if (expected.additionalPropertiesSchema != null)
                    compareNodes(entry.getValue(), expected.additionalPropertiesSchema, pointer + "." + entry.getKey(), out, depth + 1);
                else if (Boolean.FALSE.equals(expected.additionalPropertiesAllowed))
                    out.add(new Finding(Severity.ERROR, pointer + "." + entry.getKey(), "Campo extra proibido por additionalProperties:false."));
                else out.add(new Finding(Severity.WARNING, pointer + "." + entry.getKey(), "Campo extra não declarado no contrato externo (pode ser aceito)."));
            }
        }
    }
}
