package dev.jsonlens.analysis;

import dev.jsonlens.contract.ContractComparator;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.contract.HttpRequestSample;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ranks project endpoints against an HTTP request sample using only static metadata. */
public final class InteractionMatcher {
    public record Match(JavaEndpointInspector.EndpointCandidate endpoint, int score,
                        List<String> evidence, List<ContractComparator.Finding> bodyFindings,
                        int adherencePercent, String contractBody,
                        List<JavaEndpointInspector.ValueMapping> valueMappings) {}
    public record Review(int adherencePercent, List<ContractComparator.Finding> findings,
                         List<JavaEndpointInspector.ValueMapping> valueMappings) {}

    private static final int MAX_ROUTE_SCORE = 45;
    private static final int MAX_METHOD_SCORE = 25;
    private static final int MAX_BODY_SCORE = 30;
    private final ContractParser parser = new ContractParser();
    private final ContractComparator comparator = new ContractComparator();

    public List<Match> rank(HttpRequestSample request, List<JavaEndpointInspector.EndpointCandidate> endpoints) {
        BodyProfile body = bodyProfile(request.body());
        List<Match> matches = new ArrayList<>();
        for (JavaEndpointInspector.EndpointCandidate endpoint : endpoints) {
            var snapshot = endpoint.snapshot();
            int routeScore = routeScore(snapshot.path, request.path());
            int methodScore = methodScore(snapshot.verb, request.method());
            Review review = body.parsed() == null ? null : compareContract(endpoint, body.parsed());
            List<ContractComparator.Finding> bodyFindings = review == null ? List.of() : review.findings();
            int bodyScore = body.parsed() == null ? 0 : bodyScore(bodyFindings);
            List<String> evidence = new ArrayList<>();
            evidence.add(routeEvidence(snapshot.path, request.path(), routeScore));
            evidence.add(methodEvidence(snapshot.verb, request.method(), methodScore));
            if (body.parsed() != null) {
                long errors = bodyFindings.stream().filter(f -> f.severity() == ContractComparator.Severity.ERROR).count();
                long warnings = bodyFindings.stream().filter(f -> f.severity() == ContractComparator.Severity.WARNING).count();
                long valueMismatches = review.valueMappings().stream()
                    .filter(JavaEndpointInspector.ValueMapping::hasDifferentSource).count();
                long unresolvedMappings = review.valueMappings().stream()
                    .filter(mapping -> mapping.sourceField() == null).count();
                evidence.add(errors == 0 ? "Body: sem incompatibilidade estrutural detectada" :
                    "Body: " + errors + " incompatibilidade(s) estrutural(is)");
                if (warnings > 0) evidence.add("Body: " + warnings + " ponto(s) sem comprovação");
                if (valueMismatches > 0) evidence.add("Mapeamento: " + valueMismatches + " possível(is) divergência(s) de valor");
                if (unresolvedMappings > 0) evidence.add("Mapeamento: " + unresolvedMappings + " origem(ns) não comprovada(s) estaticamente");
                bodyFindings.stream()
                    .filter(finding -> finding.severity() != ContractComparator.Severity.INFO)
                    .limit(2)
                    .forEach(finding -> evidence.add(finding.pointer() + " — " + finding.message()));
            } else if (body.note() != null) {
                evidence.add(body.note());
            }
            int mappingPenalty = review == null ? 0 : (int) review.valueMappings().stream()
                .filter(JavaEndpointInspector.ValueMapping::hasDifferentSource).count() * 8
                + (int) review.valueMappings().stream().filter(mapping -> mapping.sourceField() == null).count() * 3;
            int score = routeScore + methodScore + Math.max(0, bodyScore - mappingPenalty);
            if (routeScore == 0) score = Math.min(score, 49);
            int adherence = review == null ? -1 : review.adherencePercent();
            matches.add(new Match(endpoint, score, List.copyOf(evidence), bodyFindings, adherence,
                request.body(), review == null ? endpoint.valueMappings() : review.valueMappings()));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
            .thenComparing(match -> match.endpoint().snapshot().title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(matches);
    }

    public Review compareContract(JavaEndpointInspector.EndpointCandidate endpoint, ContractParser.Parsed contract) {
        boolean inbound = endpoint.snapshot().direction.startsWith("INBOUND");
        List<ContractComparator.Finding> findings = inbound
            ? comparator.compare(contract.node(), endpoint.snapshot().request)
            : comparator.compare(endpoint.snapshot().request, contract.node());
        List<JavaEndpointInspector.ValueMapping> mappings = inbound ? List.of() : endpoint.valueMappings();
        return new Review(adherencePercent(contract.node(), findings, mappings), findings, mappings);
    }

    public Review compareResponseContract(JavaEndpointInspector.EndpointCandidate endpoint, ContractParser.Parsed contract) {
        boolean inbound = endpoint.snapshot().direction.startsWith("INBOUND");
        List<ContractComparator.Finding> findings = inbound
            ? comparator.compare(endpoint.snapshot().response, contract.node())
            : comparator.compare(contract.node(), endpoint.snapshot().response);
        return new Review(adherencePercent(contract.node(), findings, List.of()), findings, List.of());
    }

    private BodyProfile bodyProfile(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return new BodyProfile(null, "Sem body JSON; ranking baseado em método e rota.");
        try {
            var parsed = parser.parseJsonExample(rawBody);
            return new BodyProfile(parsed, null);
        } catch (IllegalArgumentException ex) {
            return new BodyProfile(null, "Body não analisado como JSON: " + ex.getMessage());
        }
    }

    private int bodyScore(List<ContractComparator.Finding> findings) {
        int errors = (int) findings.stream().filter(f -> f.severity() == ContractComparator.Severity.ERROR).count();
        int warnings = (int) findings.stream().filter(f -> f.severity() == ContractComparator.Severity.WARNING).count();
        return Math.max(0, MAX_BODY_SCORE - errors * 12 - warnings * 4);
    }

    private static int adherencePercent(dev.jsonlens.model.SchemaNode expected,
                                        List<ContractComparator.Finding> findings,
                                        List<JavaEndpointInspector.ValueMapping> mappings) {
        Set<String> failedFields = new HashSet<>();
        int unresolvedChecks = 0;
        for (ContractComparator.Finding finding : findings) {
            if (finding.severity() != ContractComparator.Severity.INFO) failedFields.add(finding.pointer());
        }
        for (JavaEndpointInspector.ValueMapping mapping : mappings) {
            if (mapping.targetField().equals("$")) {
                failedFields.add("$");
                unresolvedChecks++;
            } else if (mapping.hasDifferentSource() || mapping.sourceField() == null) {
                failedFields.add("$." + mapping.targetField());
            }
        }
        int totalFields = countFields(expected);
        int checks = totalFields + unresolvedChecks;
        if (checks == 0) return failedFields.isEmpty() ? 100 : 0;
        return Math.max(0, Math.round(100f * (checks - failedFields.size()) / checks));
    }

    private static int countFields(dev.jsonlens.model.SchemaNode node) {
        if (node == null) return 0;
        if (node.kind.equals("object")) {
            if (node.properties.isEmpty()) return 0;
            return node.properties.values().stream().mapToInt(InteractionMatcher::countFields).sum();
        }
        if (node.kind.equals("array")) return countFields(node.items);
        return 1;
    }

    private static int methodScore(String endpointMethod, String requestMethod) {
        if (endpointMethod == null || requestMethod == null) return 0;
        if (endpointMethod.equalsIgnoreCase(requestMethod)) return MAX_METHOD_SCORE;
        return endpointMethod.equalsIgnoreCase("ANY") ? MAX_METHOD_SCORE / 2 : 0;
    }

    private static int routeScore(String endpointPath, String requestPath) {
        List<String> expected = segments(endpointPath);
        List<String> actual = segments(requestPath);
        if (expected.isEmpty() && actual.isEmpty()) return MAX_ROUTE_SCORE;
        int length = Math.max(expected.size(), actual.size());
        int matching = 0;
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (isVariable(expected.get(i)) || isVariable(actual.get(i)) || expected.get(i).equals(actual.get(i))) matching++;
        }
        if (expected.size() != actual.size()) return Math.round(MAX_ROUTE_SCORE * 0.6f * matching / length);
        return Math.round(MAX_ROUTE_SCORE * matching / (float) Math.max(1, length));
    }

    private static List<String> segments(String path) {
        if (path == null || path.isBlank() || path.equals("/")) return List.of();
        return List.of(path.replaceAll("^/|/$", "").split("/"));
    }

    private static boolean isVariable(String segment) {
        return (segment.startsWith("{") && segment.endsWith("}"))
            || (segment.startsWith(":") && segment.length() > 1)
            || segment.equals("placeholder");
    }

    private static String routeEvidence(String endpointPath, String requestPath, int score) {
        if (score == MAX_ROUTE_SCORE) return "Rota compatível: " + requestPath;
        if (score > 0) return "Rota parcialmente compatível: " + requestPath + " ↔ " + endpointPath;
        return "Rota diferente: " + requestPath + " ↔ " + endpointPath;
    }

    private static String methodEvidence(String endpointMethod, String requestMethod, int score) {
        if (score == MAX_METHOD_SCORE) return "Método " + requestMethod + " compatível";
        if (score > 0) return "Método aceito como genérico: " + endpointMethod;
        return "Método diferente: " + requestMethod + " ↔ " + endpointMethod;
    }

    private record BodyProfile(ContractParser.Parsed parsed, String note) {}
}
