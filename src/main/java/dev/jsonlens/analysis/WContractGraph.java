package dev.jsonlens.analysis;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import dev.jsonlens.model.EndpointSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evidence-based call discovery. Does not infer network calls from method names alone. */
public final class WContractGraph {
    public record Connection(String fromKey, String toKey, String source, String destination,
                             String kind, String detail, String filePath, int offset,
                             String targetFilePath, int targetOffset,
                             List<JavaEndpointInspector.ValueMapping> mappings) {}
    public record Graph(List<Connection> connections, int unresolvedHttp) {}

    private final Project project;
    public WContractGraph(Project project) { this.project = project; }

    public Graph build(List<JavaEndpointInspector.EndpointCandidate> endpoints) {
        Map<PsiMethod, JavaEndpointInspector.EndpointCandidate> indexed = new HashMap<>();
        for (var endpoint : endpoints) {
            ProgressManager.checkCanceled();
            PsiMethod method = resolveMethod(endpoint);
            if (method != null) indexed.put(method, endpoint);
        }
        Map<String, Connection> links = new LinkedHashMap<>();
        int unresolved = 0;
        for (var endpoint : endpoints) {
            ProgressManager.checkCanceled();
            PsiMethod method = resolveMethod(endpoint);
            if (method == null) continue;
            unresolved += trace(endpoint, method, indexed, new HashSet<>(), links, 0, 0);
        }
        return new Graph(List.copyOf(links.values()), unresolved);
    }

    private int trace(JavaEndpointInspector.EndpointCandidate source, PsiMethod method,
                      Map<PsiMethod, JavaEndpointInspector.EndpointCandidate> indexed,
                      Set<PsiMethod> visited, Map<String,Connection> result, int depth, int traversed) {
        if (depth > 3 || traversed > 120 || !visited.add(method)) return 0;
        ProgressManager.checkCanceled();
        int unknown = 0;
        for (PsiMethodCallExpression invocation : PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class)) {
            ProgressManager.checkCanceled();
            PsiMethod destination = invocation.resolveMethod();
            if (destination == null) continue;
            var known = indexed.get(destination);
            String file = path(invocation.getContainingFile().getVirtualFile());
            if (known != null && known != source) {
                String targetKind = known.snapshot().direction.startsWith("OUTBOUND") ? "FEIGN_HTTP"
                    : "CHAMADA_JAVA_ENDPOINT";
                String sourceKey = WContractProjectState.key(source.snapshot());
                String targetKey = WContractProjectState.key(known.snapshot());
                String dedupe = sourceKey + "|" + targetKey + "|" + targetKind;
                result.putIfAbsent(dedupe, new Connection(sourceKey, targetKey,
                    source.snapshot().title, known.snapshot().title, targetKind,
                    targetKind.equals("FEIGN_HTTP")
                        ? "Cliente Feign resolvido pelo PSI; a chamada Java foi encontrada."
                        : "Chamada Java direta; não comprova tráfego HTTP entre endpoints.",
                    file, invocation.getTextOffset(), path(known.file()), known.offset(),
                    known.valueMappings()));
                continue;
            }
            if (looksLikeDynamicHttp(destination)) {
                unknown++;
                String sourceKey = WContractProjectState.key(source.snapshot());
                String targetKey = sourceKey + "|dynamic|" + invocation.getTextOffset();
                result.putIfAbsent(targetKey, new Connection(sourceKey, "", source.snapshot().title,
                    destination.getName(), "HTTP_NAO_RESOLVIDO",
                    "Chamada a cliente HTTP identificada, mas o endpoint remoto não pode ser determinado estaticamente.",
                    file, invocation.getTextOffset(), "", -1, List.of()));
                continue;
            }
            // Follow project-owned service/mapper calls, never library or JDK methods.
            PsiFile targetFile = destination.getContainingFile();
            VirtualFile targetVirtual = targetFile == null ? null : targetFile.getVirtualFile();
            String base = project.getBasePath();
            if (depth < 3 && targetVirtual != null && base != null
                && targetVirtual.getPath().startsWith(base + "/")
                && !visited.contains(destination)
                && destination.getBody() != null) {
                unknown += trace(source, destination, indexed, visited, result, depth + 1, traversed + 1);
            }
        }
        visited.remove(method);
        return unknown;
    }

    private static boolean looksLikeDynamicHttp(PsiMethod target) {
        var clazz = target.getContainingClass();
        String owner = clazz == null ? "" : String.valueOf(clazz.getQualifiedName());
        if (owner.contains("org.springframework.web.client.RestTemplate"))
            return List.of("exchange", "getForObject", "postForObject", "postForEntity", "execute").contains(target.getName());
        if (owner.contains("org.springframework.web.client.RestClient"))
            return List.of("get", "post", "put", "delete", "method", "retrieve", "exchange").contains(target.getName());
        if (owner.contains("org.springframework.web.reactive.function.client.WebClient"))
            return List.of("get", "post", "put", "patch", "delete", "method", "retrieve", "exchange").contains(target.getName());
        return false;
    }

    private PsiMethod resolveMethod(JavaEndpointInspector.EndpointCandidate candidate) {
        VirtualFile file = candidate.file();
        if (file == null || !file.isValid()) return null;
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (!(psi instanceof PsiJavaFile)) return null;
        PsiElement leaf = psi.findElementAt(Math.max(0, Math.min(candidate.offset(), psi.getTextLength()-1)));
        return PsiTreeUtil.getParentOfType(leaf, PsiMethod.class, false);
    }

    private static String path(VirtualFile file) { return file == null ? "" : file.getPath(); }
}
