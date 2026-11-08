package dev.jsonlens.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.progress.ProgressManager;
import dev.jsonlens.contract.ContractComparator;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.analysis.InteractionMatcher;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, offline contract evaluation.
 * Absence of an external contract is UNVERIFIED, never interpreted as 100% compatible.
 */
public final class WContractAssessment {
    public record Risk(String severity, String confidence, String rule, String endpoint,
                       String integration, String pointer, String message, String evidence,
                       String suggestion, String filePath, int offset) {}
    public record EndpointResult(String key, String title, String verb, String path, String direction,
                                 String status, int checks, int errors, int warnings) {}
    public record Report(WContractProjectState.Run summary, List<EndpointResult> endpoints,
                         List<Risk> risks, WContractGraph.Graph graph) {}

    public interface Progress {
        void step(int finished, int total, String phase);
    }

    private final ContractParser parser = new ContractParser();
    private final InteractionMatcher matcher = new InteractionMatcher();

    public Report evaluate(List<JavaEndpointInspector.EndpointCandidate> endpoints,
                           WContractGraph.Graph graph, List<WContractProjectState.Binding> bindings,
                           String scope, Progress progress) {
        Map<String, List<WContractProjectState.Binding>> contracts = new HashMap<>();
        for (var binding : bindings) contracts.computeIfAbsent(binding.key, key -> new ArrayList<>()).add(binding);
        List<Risk> risks = new ArrayList<>();
        List<EndpointResult> statuses = new ArrayList<>();
        WContractProjectState.Run summary = new WContractProjectState.Run();
        summary.timestamp = OffsetDateTime.now().toString();
        summary.scope = scope;
        summary.total = endpoints.size();
        int passedContracts = 0;
        int totalContracts = 0;
        int completed = 0;
        for (var endpoint : endpoints) {
            ProgressManager.checkCanceled();
            String key = WContractProjectState.key(endpoint.snapshot());
            int endpointChecks = 0, endpointErrors = 0, endpointWarnings = 0;
            for (var binding : contracts.getOrDefault(key, List.of())) {
                ProgressManager.checkCanceled();
                ++totalContracts;
                ++endpointChecks;
                try {
                    String filename = binding.source.isBlank() ? "external.json" : binding.source;
                    ContractParser.Parsed parsed = parser.parseText(binding.document, filename,
                        endpoint.snapshot().verb, endpoint.snapshot().path, binding.response);
                    InteractionMatcher.Review review = binding.response
                        ? matcher.compareResponseContract(endpoint, parsed)
                        : matcher.compareContract(endpoint, parsed);
                    boolean valid = true;
                    for (var finding : review.findings()) {
                        if (finding.severity() == ContractComparator.Severity.INFO) continue;
                        boolean error = finding.severity() == ContractComparator.Severity.ERROR;
                        if (error) { endpointErrors++; summary.errors++; valid = false; }
                        else { endpointWarnings++; summary.warnings++; }
                        boolean temporal = finding.message().startsWith("Risco temporal:")
                            || finding.message().contains("date-time");
                        risks.add(new Risk(error ? "HIGH" : "MEDIUM", error ? "CONFIRMED" : "LIMITED",
                            temporal ? "TIME_FORMAT" : "SCHEMA_" + finding.severity(), endpoint.snapshot().title,
                            binding.source, finding.pointer(), finding.message(),
                            "Comparação estática " + (binding.response ? "Response" : "Request") +
                                " com contrato externo '" + binding.source + "'.",
                            "Corrija o DTO, serialização ou atualize o contrato se a mudança for acordada.",
                            path(endpoint), endpoint.offset()));
                    }
                    for (String warning : parsed.warnings()) {
                        endpointWarnings++; summary.warnings++;
                        risks.add(new Risk("LOW", "LIMITED", "PARSER_WARNING", endpoint.snapshot().title,
                            binding.source, "$", warning, "Interpretação parcial de esquema.",
                            "Revise a referência ou composição de schemas apontada.", path(endpoint), endpoint.offset()));
                    }
                    if (valid && review.findings().stream().noneMatch(f -> f.severity() == ContractComparator.Severity.WARNING))
                        passedContracts++;
                } catch (Exception ex) {
                    endpointWarnings++; summary.warnings++;
                    risks.add(new Risk("MEDIUM", "UNVERIFIED", "INVALID_CONTRACT", endpoint.snapshot().title,
                        binding.source, "$", "Contrato não analisável: " + ex.getMessage(),
                        "Documento associado ao endpoint.", "Revise o JSON/YAML ou a operação no OpenAPI.",
                        path(endpoint), endpoint.offset()));
                }
            }

            // Suspicion only: naming mismatch does not prove a business-value error.
            if (endpoint.snapshot().direction.startsWith("OUTBOUND")) {
                for (var mapping : endpoint.valueMappings()) {
                    if (!mapping.hasDifferentSource() || mapping.targetField().equals("$")) continue;
                    ++summary.suspected;
                    ++endpointWarnings;
                    risks.add(new Risk("MEDIUM", "SUSPECTED", "VALUE_ORIGIN",
                        endpoint.snapshot().title, endpoint.snapshot().path, "$." + mapping.targetField(),
                        "Origem do valor possivelmente incompatível com o nome do campo.",
                        mapping.targetField() + " ← " + mapping.expression() +
                            " (origem inferida: " + mapping.sourceField() + ")",
                        "Confirme a semântica do campo e examine a origem. Não aplique correção automática sem evidência.",
                        mapping.sourceFile().isBlank() ? path(endpoint) : mapping.sourceFile(),
                        mapping.sourceOffset() < 0 ? endpoint.offset() : mapping.sourceOffset()));
                }
            }
            summary.evaluated += endpointChecks > 0 ? 1 : 0;
            statuses.add(new EndpointResult(key, endpoint.snapshot().title,
                endpoint.snapshot().verb, endpoint.snapshot().path, endpoint.snapshot().direction,
                endpointChecks == 0 ? "NOT_VERIFIED"
                    : endpointErrors > 0 ? "INCOMPATIBLE"
                    : endpointWarnings > 0 ? "PARTIAL" : "COMPATIBLE",
                endpointChecks, endpointErrors, endpointWarnings));
            progress.step(++completed, endpoints.size(), "Comparando contratos e mapeamentos");
        }
        for (WContractGraph.Connection link : graph.connections()) {
            if (!link.kind().equals("HTTP_NAO_RESOLVIDO")) continue;
            summary.unresolved++;
            risks.add(new Risk("LOW", "UNVERIFIED", "UNRESOLVED_HTTP",
                link.source(), link.destination(), "$", "Destino HTTP não determinado.",
                link.detail(), "Associe um contrato externo ou documente o endpoint remoto.",
                link.filePath(), link.offset()));
        }
        summary.passed = passedContracts;
        summary.compatibility = totalContracts == 0 ? -1
            : Math.round(100f * passedContracts / totalContracts);
        summary.summary = "Verificados " + summary.evaluated + "/" + summary.total +
            " endpoints com contrato associado; " + totalContracts + " comparações, " +
            summary.errors + " erro(s) estruturais, " + summary.suspected +
            " suspeita(s) de valor, " + summary.unresolved + " destino(s) não resolvido(s).";
        return new Report(summary, List.copyOf(statuses), List.copyOf(risks), graph);
    }

    private static String path(JavaEndpointInspector.EndpointCandidate endpoint) {
        return endpoint.file() == null ? "" : endpoint.file().getPath();
    }
}
