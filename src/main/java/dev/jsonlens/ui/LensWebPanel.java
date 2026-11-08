package dev.jsonlens.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.jsonlens.analysis.WContractGraph;
import dev.jsonlens.analysis.WContractAssessment;
import dev.jsonlens.analysis.WContractProjectState;
import dev.jsonlens.contract.CurlGenerator;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.jsonlens.analysis.InteractionMatcher;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.contract.CollectionRequestParser;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.contract.CurlRequestParser;
import dev.jsonlens.contract.HttpRequestSample;
import dev.jsonlens.model.EndpointSnapshot;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** The approved web layout is the view; IntelliJ PSI and the existing Java parsers remain authoritative. */
public final class LensWebPanel implements Disposable {
    public static final Key<LensWebPanel> ACTIVE = Key.create("wcontract.active.web.panel");
    private static final int MAX_MESSAGE = 5_500_000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JBCefBrowser browser = new JBCefBrowser();
    private final JBCefJSQuery commands = JBCefJSQuery.create((JBCefBrowserBase) browser);
    private final Timer refreshTimer = new Timer(350, event -> refresh());
    private final AtomicLong inspectionGeneration = new AtomicLong();
    private final AtomicLong curlScanGeneration = new AtomicLong();
    private final AtomicLong collectionScanGeneration = new AtomicLong();
    private final AtomicLong compareGeneration = new AtomicLong();
    private final AtomicInteger candidateId = new AtomicInteger();
    private final LensWebSearch.EndpointCache endpointCache = new LensWebSearch.EndpointCache();
    private final Map<Integer, JavaEndpointInspector.EndpointCandidate> candidates = new HashMap<>();
    private final Map<String, List<Integer>> candidateIdsBySource = new HashMap<>();
    private final Map<String, String> priorScans = new HashMap<>();
    private final Map<String, String> priorInputs = new HashMap<>();
    private final Map<String, Long> scanRevisions = new HashMap<>();
    private Future<?> compareTask;
    private final AtomicLong assessmentGeneration = new AtomicLong();
    private volatile ProgressIndicator activeAssessment;
    private List<JavaEndpointInspector.EndpointCandidate> lastGraphCandidates = List.of();
    private WContractGraph.Graph lastGraph = new WContractGraph.Graph(List.of(), 0);
    private long lastGraphRevision = -1;
    private String navigationAfterReady = null;
    private boolean ready;
    private boolean disposed;
    private boolean pinned;
    private VirtualFile pinnedFile;
    private int pinnedOffset;
    private String forcedMethodKey;
    private JavaEndpointInspector.Inspection inspection;
    private EndpointSnapshot snapshot;

    public LensWebPanel(Project project) {
        this.project = project;
        project.putUserData(ACTIVE, this);
        refreshTimer.setRepeats(false);
        commands.addHandler(raw -> {
            if (raw == null || raw.length() > MAX_MESSAGE) return null;
            try {
                JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
                SwingUtilities.invokeLater(() -> {
                    if (!disposed) handle(message);
                });
            } catch (RuntimeException ignored) {
                // Malformed messages do not escape the browser bridge.
            }
            return null;
        });
        root.add(browser.getComponent(), BorderLayout.CENTER);
        String html = resource("/web/wcontract.html");
        String nativeScript = resource("/web/wcontract-native.js");
        String inject = "<script>window.__jsonLensNative=function(action){" +
            commands.inject("JSON.stringify(action)") + "};</script>";
        browser.loadHTML(html.replace("</body>",
            inject + "<script>" + nativeScript + "</script></body>"));
    }

    public JComponent component() { return root; }

    public void start() {
        project.getMessageBus().connect(this).subscribe(PsiModificationTracker.TOPIC,
            (PsiModificationTracker.Listener) this::schedule);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override public void selectionChanged(FileEditorManagerEvent event) {
                    if (!pinned) { forcedMethodKey = null; schedule(); }
                }
            });
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent event) {
                if (!pinned && event.getEditor().getProject() == project) {
                    forcedMethodKey = null;
                    schedule();
                }
            }
        }, this);
        schedule();
    }

    private void handle(JsonObject message) {
        String action = string(message, "type");
        switch (action) {
            case "ready" -> {
                ready = true;
                JsonObject init = new JsonObject();
                init.addProperty("type", "ready");
                init.addProperty("language", LensPreferences.language());
                init.addProperty("theme", LensPreferences.theme());
                init.addProperty("infoFont", LensPreferences.infoFont());
                init.addProperty("infoSize", LensPreferences.infoSize());
                init.addProperty("jsonFont", LensPreferences.jsonFont());
                init.addProperty("jsonSize", LensPreferences.jsonSize());
                init.addProperty("curlFont", LensPreferences.curlFont());
                init.addProperty("curlSize", LensPreferences.curlSize());
                send(init);
                sendHistory();
                if (navigationAfterReady != null) {
                    JsonObject nav = new JsonObject();
                    nav.addProperty("type","navigate");
                    nav.addProperty("view",navigationAfterReady);
                    send(nav);
                    navigationAfterReady = null;
                }
                schedule();
            }
            case "select" -> {
                String key = string(message, "key");
                if (inspection != null && inspection.options().stream().anyMatch(o -> o.key().equals(key))) {
                    forcedMethodKey = key;
                    schedule();
                }
            }
            case "pin" -> togglePin();
            case "refresh" -> schedule();
            case "integrations" -> requestIntegrations();
            case "evaluate" -> beginAssessment(string(message, "scope"));
            case "cancel-evaluation" -> cancelAssessment();
            case "history" -> sendHistory();
            case "bind-contract" -> bindContract(message);
            case "unbind-contract" -> unbindContract(message);
            case "open-location" -> openLocation(string(message, "path"), number(message, "offset"));
            case "scan" -> scan(string(message, "source"), string(message, "raw"));
            case "source" -> {
                String cached = priorScans.get(string(message, "source"));
                if (cached != null) sendJson(cached);
            }
            case "compare" -> compare(string(message, "raw"), string(message, "mode"),
                number(message, "candidateId"), string(message, "key"));
            case "open" -> open(number(message, "id"));
            case "copy" -> CopyPasteManager.getInstance().setContents(
                new StringSelection(string(message, "text")));
            case "preferences" -> savePreferences(message);
            default -> { }
        }
    }

    /** Invoked by native EditorPopupMenu actions once the tool window has been activated. */
    public void focusFromEditor(VirtualFile file, int offset, String view) {
        pinned = false;
        pinnedFile = null;
        forcedMethodKey = null;
        if (!ready) navigationAfterReady = view;
        else {
            JsonObject nav = new JsonObject();
            nav.addProperty("type", "navigate");
            nav.addProperty("view", view);
            send(nav);
        }
        schedule();
    }

    private void savePreferences(JsonObject message) {
        LensPreferences.save(
            stringOr(message, "language", "pt-BR"),
            stringOr(message, "theme", "Ember"),
            stringOr(message, "infoFont", LensPreferences.infoFont()),
            clamp(numberOr(message, "infoSize", LensPreferences.infoSize()), 9, 28),
            stringOr(message, "jsonFont", LensPreferences.jsonFont()),
            clamp(numberOr(message, "jsonSize", LensPreferences.jsonSize()), 9, 28),
            stringOr(message, "curlFont", LensPreferences.curlFont()),
            clamp(numberOr(message, "curlSize", LensPreferences.curlSize()), 9, 28));
    }

    private void togglePin() {
        if (pinned) {
            pinned = false;
            pinnedFile = null;
            forcedMethodKey = null;
        } else {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
            if (editor == null || files.length == 0) {
                notice("Abra primeiro um método Java para fixar.");
                return;
            }
            pinnedFile = files[0];
            pinnedOffset = editor.getCaretModel().getOffset();
            pinned = true;
        }
        JsonObject update = new JsonObject();
        update.addProperty("type", "pin");
        update.addProperty("pinned", pinned);
        send(update);
        schedule();
    }

    private void schedule() {
        if (disposed) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::schedule);
            return;
        }
        refreshTimer.restart();
    }

    private void refresh() {
        if (!ready || disposed || project.isDisposed()) return;
        long revision = PsiModificationTracker.getInstance(project).getModificationCount();
        for (var entry : List.copyOf(priorInputs.entrySet())) {
            if (!Long.valueOf(revision).equals(scanRevisions.get(entry.getKey())))
                scan(entry.getKey(), entry.getValue());
        }
        VirtualFile file;
        int offset;
        if (pinned) {
            file = pinnedFile;
            offset = pinnedOffset;
        } else {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
            if (editor == null || files.length == 0) {
                noEndpoint("Abra um arquivo Java e posicione o cursor em um controller ou Feign.");
                return;
            }
            file = files[0];
            offset = editor.getCaretModel().getOffset();
        }
        if (file == null || !file.isValid()) {
            noEndpoint("Arquivo Java indisponível.");
            return;
        }
        String method = forcedMethodKey;
        long generation = inspectionGeneration.incrementAndGet();
        ReadAction.nonBlocking(() -> new JavaEndpointInspector(project).inspect(file, offset, method))
            .inSmartMode(project).expireWith(this).coalesceBy(this, "inspect")
            .finishOnUiThread(ModalityState.any(), current -> {
                if (disposed || generation != inspectionGeneration.get()) return;
                inspection = current;
                snapshot = current.snapshot();
                forcedMethodKey = current.selectedKey();
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "preview");
                payload.addProperty("note", current.note());
                payload.addProperty("pinned", pinned);
                JsonArray methods = new JsonArray();
                for (JavaEndpointInspector.Option option : current.options()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("key", option.key());
                    entry.addProperty("label", option.label());
                    methods.add(entry);
                }
                payload.add("methods", methods);
                payload.addProperty("selectedKey", current.selectedKey());
                if (snapshot != null) payload.add("endpoint", serialize(snapshot));
                send(payload);
                // A contract open in the browser is compared against the newest snapshot on edit.
                JsonObject refresh = new JsonObject();
                refresh.addProperty("type", "recompare");
                send(refresh);
            }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void noEndpoint(String note) {
        snapshot = null;
        inspection = null;
        JsonObject result = new JsonObject();
        result.addProperty("type", "preview");
        result.addProperty("note", note);
        result.addProperty("pinned", pinned);
        result.add("methods", new JsonArray());
        send(result);
    }

    private JsonObject serialize(EndpointSnapshot data) {
        JsonObject result = new JsonObject();
        result.addProperty("title", data.title);
        result.addProperty("verb", data.verb);
        result.addProperty("path", data.path);
        result.addProperty("direction", data.direction);
        result.addProperty("key", WContractProjectState.key(data));
        result.addProperty("curl", CurlGenerator.generate(data));
        result.add("request", JSON.toJsonTree(data.request.sample(0)));
        result.add("response", JSON.toJsonTree(data.response.sample(0)));
        result.add("requestSchema", JSON.toJsonTree(data.request.toSchema(0)));
        result.add("responseSchema", JSON.toJsonTree(data.response.toSchema(0)));
        result.add("warnings", JSON.toJsonTree(data.warnings));
        result.add("notes", JSON.toJsonTree(data.parameterNotes));
        return result;
    }

    private void scan(String source, String raw) {
        if (!source.equals("curl") && !source.equals("collection")) return;
        if (raw.length() > 5_000_000) { notice("Entrada acima do limite permitido."); return; }
        AtomicLong sequence = source.equals("curl") ? curlScanGeneration : collectionScanGeneration;
        long generation = sequence.incrementAndGet();
        priorInputs.put(source, raw);
        scanRevisions.put(source, PsiModificationTracker.getInstance(project).getModificationCount());
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            final List<HttpRequestSample> requests;
            try {
                if (source.equals("curl")) {
                    CurlRequestParser.Request parsed = new CurlRequestParser().parse(raw);
                    requests = List.of(new HttpRequestSample("cURL", parsed.method(), parsed.url(),
                        parsed.path(), parsed.body()));
                } else {
                    requests = new CollectionRequestParser().parse(raw);
                }
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed || generation != sequence.get()) return;
                    priorInputs.remove(source);
                    scanRevisions.remove(source);
                    JsonObject error = new JsonObject();
                    error.addProperty("type", "scan-error");
                    error.addProperty("source", source);
                    error.addProperty("message", ex.getMessage());
                    send(error);
                });
                return;
            }
            if (generation != sequence.get()) return;
            ReadAction.nonBlocking(() -> {
                long revision = PsiModificationTracker.getInstance(project).getModificationCount();
                return new IndexedEndpoints(revision,
                    endpointCache.get(revision, () -> new JavaEndpointInspector(project).inspectAll()));
            }).inSmartMode(project).expireWith(this)
                .expireWhen(() -> generation != sequence.get())
                .coalesceBy(this, "search", source)
                .finishOnUiThread(ModalityState.any(), indexed -> {
                    if (disposed || generation != sequence.get()) return;
                    if (indexed.revision() != PsiModificationTracker.getInstance(project).getModificationCount()) {
                        scan(source, raw);
                        return;
                    }
                    scanRevisions.put(source, indexed.revision());
                    prepareSearch(source, requests, indexed.endpoints(), sequence, generation);
                }).submit(AppExecutorUtil.getAppExecutorService());
        });
    }

    private record IndexedEndpoints(long revision, List<JavaEndpointInspector.EndpointCandidate> endpoints) {}

    private void prepareSearch(String source, List<HttpRequestSample> requests,
            List<JavaEndpointInspector.EndpointCandidate> found, AtomicLong sequence, long generation) {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            InteractionMatcher matcher = new InteractionMatcher();
            List<List<InteractionMatcher.Match>> rankings = new ArrayList<>();
            for (HttpRequestSample request : requests) {
                if (generation != sequence.get()) return;
                rankings.add(matcher.rank(request, found));
            }
            if (generation != sequence.get()) return;
            Map<Integer, JavaEndpointInspector.EndpointCandidate> registered = new HashMap<>();
            LensWebSearch.Result result = new LensWebSearch.Result(found, requests, rankings);
            JsonObject payload = LensWebSearch.toJson(source, result, candidate -> {
                int id = candidateId.incrementAndGet();
                registered.put(id, candidate);
                return id;
            });
            String json = JSON.toJson(payload);
            SwingUtilities.invokeLater(() -> {
                if (disposed || generation != sequence.get()) return;
                for (Integer previous : candidateIdsBySource.getOrDefault(source, List.of()))
                    candidates.remove(previous);
                candidates.putAll(registered);
                candidateIdsBySource.put(source, List.copyOf(registered.keySet()));
                priorScans.put(source, json);
                sendJson(json);
            });
        });
    }

    private void compare(String raw, String mode, int candidateId, String key) {
        long generation = compareGeneration.incrementAndGet();
        if (compareTask != null) compareTask.cancel(true);
        JavaEndpointInspector.EndpointCandidate matched = candidates.get(candidateId);
        if (matched == null && !key.isBlank()) {
            for (var candidate : lastGraphCandidates)
                if (key.equals(WContractProjectState.key(candidate.snapshot()))) {
                    matched = candidate;
                    break;
                }
        }
        if (candidateId != -1 && matched == null) {
            notice("O resultado da busca mudou; selecione novamente o endpoint para comparar.");
            return;
        }
        if (snapshot == null && matched == null) { notice("Selecione primeiro um endpoint Java."); return; }
        if (raw.isBlank() || raw.length() > 1_000_000) {
            notice("Informe um contrato JSON/YAML de até 1 MB.");
            return;
        }
        EndpointSnapshot current = matched == null ? snapshot : matched.snapshot();
        if (!key.isBlank() && matched == null &&
            (snapshot == null || !key.equals(WContractProjectState.key(snapshot)))) {
            notice("Endpoint não encontrado; atualize o mapa de integrações.");
            return;
        }
        final JavaEndpointInspector.EndpointCandidate selectedCandidate = matched;
        boolean response = mode.equals("response");
        compareTask = AppExecutorUtil.getAppExecutorService().submit(() -> {
            if (generation != compareGeneration.get()) return;
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "comparison");
            try {
                String fileName = raw.stripLeading().startsWith("{") || raw.stripLeading().startsWith("[")
                    ? "contract.json" : "contract.yaml";
                ContractParser.Parsed parsed = new ContractParser().parseText(
                    raw, fileName, current.verb, current.path, response);
                if (generation != compareGeneration.get() || Thread.currentThread().isInterrupted()) return;
                InteractionMatcher matcher = new InteractionMatcher();
                JavaEndpointInspector.EndpointCandidate target = selectedCandidate == null
                    ? new JavaEndpointInspector.EndpointCandidate(current, null, 0) : selectedCandidate;
                InteractionMatcher.Review review = response
                    ? matcher.compareResponseContract(target, parsed) : matcher.compareContract(target, parsed);
                payload.addProperty("score", review.adherencePercent());
                payload.addProperty("sourceType", parsed.sourceType());
                payload.add("findings", JSON.toJsonTree(review.findings()));
                payload.add("warnings", JSON.toJsonTree(parsed.warnings()));
            } catch (Exception ex) {
                payload.addProperty("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            SwingUtilities.invokeLater(() -> {
                if (!disposed && generation == compareGeneration.get()) send(payload);
            });
        });
    }


    private record Topology(List<JavaEndpointInspector.EndpointCandidate> endpoints,
                            WContractGraph.Graph graph, long revision) {}

    private void requestIntegrations() {
        if (disposed) return;
        String currentKey = snapshot == null ? "" : WContractProjectState.key(snapshot);
        if (currentKey.isBlank()) { notice("Selecione um endpoint Java primeiro."); return; }
        long revision = PsiModificationTracker.getInstance(project).getModificationCount();
        if (revision == lastGraphRevision) {
            sendIntegrations(currentKey);
            return;
        }
        ReadAction.nonBlocking(() -> {
            long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
            var all = endpointCache.get(stamp, () -> new JavaEndpointInspector(project).inspectAll());
            var graph = new WContractGraph(project).build(all);
            return new Topology(all, graph, stamp);
        }).inSmartMode(project).expireWith(this).coalesceBy(this, "wcontract-topology")
            .finishOnUiThread(ModalityState.any(), topology -> {
                if (disposed) return;
                if (topology.revision() != PsiModificationTracker.getInstance(project).getModificationCount()) {
                    requestIntegrations(); return;
                }
                lastGraphCandidates = topology.endpoints();
                lastGraph = topology.graph();
                lastGraphRevision = topology.revision();
                sendIntegrations(snapshot == null ? "" : WContractProjectState.key(snapshot));
            }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void sendIntegrations(String key) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "integrations");
        JsonArray connections = new JsonArray();
        for (var edge : lastGraph.connections())
            if (edge.fromKey().equals(key)) connections.add(JSON.toJsonTree(edge));
        payload.add("connections", connections);
        JsonArray targets = new JsonArray();
        for (var candidate : lastGraphCandidates) {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", WContractProjectState.key(candidate.snapshot()));
            entry.addProperty("title", candidate.snapshot().title);
            entry.addProperty("verb", candidate.snapshot().verb);
            entry.addProperty("path", candidate.snapshot().path);
            targets.add(entry);
        }
        payload.add("targets", targets);
        send(payload);
    }

    private void bindContract(JsonObject message) {
        String key = string(message, "key");
        String raw = string(message, "raw");
        String name = stringOr(message, "source", "contrato.json");
        boolean response = string(message, "mode").equals("response");
        EndpointSnapshot target = findEndpoint(key);
        if (target == null) { notice("O endpoint selecionado não está disponível."); return; }
        if (raw.isBlank() || raw.length() > 180_000) {
            notice("Forneça um contrato local de até 180 KB."); return;
        }
        // The Java parser chooses JSON/YAML from the source extension. Pasted YAML
        // needs a YAML name even if the user did not import a file.
        String actualName = (!raw.stripLeading().startsWith("{") && !raw.stripLeading().startsWith("["))
            ? (name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(yaml|yml)$") ? name : "contrato.yaml")
            : "contrato.json";
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            String error = null;
            try {
                new ContractParser().parseText(raw, actualName, target.verb, target.path, response);
            } catch (Exception ex) { error = ex.getMessage(); }
            final String errorText = error;
            SwingUtilities.invokeLater(() -> {
                if (disposed) return;
                if (errorText != null) { notice("Contrato inválido: " + errorText); return; }
                try {
                    project.getService(WContractProjectState.class).bind(key, actualName, raw, response);
                    JsonObject done = new JsonObject();
                    done.addProperty("type", "contract-saved");
                    done.addProperty("message", "Contrato local associado. Execute AVALIAR para gerar o relatório.");
                    send(done);
                } catch (IllegalArgumentException ex) { notice(ex.getMessage()); }
            });
        });
    }

    private EndpointSnapshot findEndpoint(String key) {
        if (snapshot != null && key.equals(WContractProjectState.key(snapshot))) return snapshot;
        for (var candidate : lastGraphCandidates)
            if (key.equals(WContractProjectState.key(candidate.snapshot()))) return candidate.snapshot();
        return null;
    }

    private void unbindContract(JsonObject message) {
        String key = string(message, "key");
        boolean response = string(message, "mode").equals("response");
        project.getService(WContractProjectState.class).unbind(key, response);
        JsonObject done = new JsonObject();
        done.addProperty("type", "contract-saved");
        done.addProperty("message", "Associação removida para este endpoint e direção.");
        send(done);
    }

    private void sendHistory() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "history");
        msg.add("history", JSON.toJsonTree(project.getService(WContractProjectState.class).history()));
        send(msg);
    }

    private void progress(String phase, int percent, int completed, int total) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "assessment-progress");
        message.addProperty("phase", phase);
        message.addProperty("indeterminate", percent < 0);
        if (percent >= 0) message.addProperty("percent", percent);
        message.addProperty("detail", total < 0 ? "Descobrindo endpoints no projeto."
            : completed + " de " + total + " endpoint(s) processados");
        SwingUtilities.invokeLater(() -> { if (!disposed) send(message); });
    }

    private void beginAssessment(String scope) {
        if (!scope.equals("isolated") && !scope.equals("project")) scope = "isolated";
        cancelAssessment(false);
        long ticket = assessmentGeneration.incrementAndGet();
        String finalScope = scope;
        String selectedKey = snapshot == null ? "" : WContractProjectState.key(snapshot);
        if (scope.equals("isolated") && selectedKey.isBlank()) {
            JsonObject cancel = new JsonObject();
            cancel.addProperty("type", "assessment-cancelled");
            send(cancel);
            notice("Selecione um endpoint para a avaliação isolada."); return;
        }
        var bindings = project.getService(WContractProjectState.class).bindings();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "WContract — Avaliação estática", true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                activeAssessment = indicator;
                indicator.setIndeterminate(true);
                progress("Descobrindo endpoints e relações Java", -1, 0, -1);
                try {
                    long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
                    var all = ReadAction.compute(() ->
                        endpointCache.get(stamp, () -> new JavaEndpointInspector(project).inspectAll()));
                    indicator.checkCanceled();
                    WContractGraph.Graph graph = ReadAction.compute(() -> new WContractGraph(project).build(all));
                    indicator.checkCanceled();
                    if (ticket != assessmentGeneration.get()) return;
                    List<JavaEndpointInspector.EndpointCandidate> targets =
                        finalScope.equals("project") ? all
                            : all.stream().filter(e -> selectedKey.equals(WContractProjectState.key(e.snapshot()))).toList();
                    if (targets.isEmpty()) throw new IllegalArgumentException("Endpoint não encontrado no projeto; atualize a seleção.");
                    if (finalScope.equals("isolated")) {
                        var relevant = graph.connections().stream()
                            .filter(e -> e.fromKey().equals(selectedKey)).toList();
                        graph = new WContractGraph.Graph(relevant,
                            (int) relevant.stream().filter(e -> e.kind().equals("HTTP_NAO_RESOLVIDO")).count());
                    }
                    indicator.setIndeterminate(false);
                    WContractGraph.Graph selectedGraph = graph;
                    WContractAssessment.Report report = new WContractAssessment().evaluate(
                        targets, selectedGraph, bindings, finalScope, (completed, total, phase) -> {
                            indicator.checkCanceled();
                            indicator.setFraction(total == 0 ? 1 : (double) completed/total);
                            if (completed == total || completed % Math.max(1,total/100) == 0)
                                progress(phase, total == 0 ? 100 : Math.round(completed*100f/total), completed, total);
                        });
                    indicator.checkCanceled();
                    if (ticket != assessmentGeneration.get()) return;
                    SwingUtilities.invokeLater(() -> {
                        if (disposed || ticket != assessmentGeneration.get() || indicator.isCanceled()) return;
                        project.getService(WContractProjectState.class).record(report.summary());
                        JsonObject done = new JsonObject();
                        done.addProperty("type", "assessment-result");
                        done.add("report", JSON.toJsonTree(report));
                        send(done);
                    });
                } catch (ProcessCanceledException ignored) {
                    SwingUtilities.invokeLater(() -> assessmentCancelled(ticket, "Avaliação cancelada."));
                } catch (RuntimeException ex) {
                    SwingUtilities.invokeLater(() -> assessmentCancelled(ticket,
                        "Avaliação incompleta: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage())));
                } finally {
                    if (activeAssessment == indicator) activeAssessment = null;
                }
            }
        });
    }

    private void assessmentCancelled(long ticket, String reason) {
        if (disposed || ticket != assessmentGeneration.get()) return;
        JsonObject data = new JsonObject();
        data.addProperty("type", "assessment-cancelled");
        send(data);
        notice(reason);
    }

    private void cancelAssessment() { cancelAssessment(true); }
    private void cancelAssessment(boolean announce) {
        assessmentGeneration.incrementAndGet();
        ProgressIndicator running = activeAssessment;
        if (running != null) running.cancel();
        if (announce) {
            JsonObject data = new JsonObject();
            data.addProperty("type", "assessment-cancelled");
            send(data);
        }
    }

    private void openLocation(String path, int offset) {
        String base = project.getBasePath();
        if (path == null || path.isBlank() || base == null || !path.startsWith(base + "/")) {
            notice("Evidência de código fora do projeto ou sem localização resolvida."); return;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        if (file == null || !file.isValid()) { notice("Arquivo não disponível."); return; }
        new OpenFileDescriptor(project, file, Math.max(0,offset)).navigate(true);
    }

    private void open(int id) {
        JavaEndpointInspector.EndpointCandidate candidate = candidates.get(id);
        if (candidate == null || candidate.file() == null || !candidate.file().isValid()) {
            notice("A declaração não está mais disponível; refaça a busca.");
            return;
        }
        new OpenFileDescriptor(project, candidate.file(), candidate.offset()).navigate(true);
    }

    private void notice(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "notice");
        response.addProperty("message", message);
        send(response);
    }

    private void send(JsonObject message) {
        sendJson(JSON.toJson(message));
    }

    private void sendJson(String json) {
        if (!ready || disposed) return;
        String js = "window.JsonLensUI&&window.JsonLensUI.onNative(" + json + ");";
        browser.getCefBrowser().executeJavaScript(js, browser.getCefBrowser().getURL(), 0);
    }

    private static String resource(String path) {
        try (var stream = LensWebPanel.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Resource not found: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load UI: " + path, ex);
        }
    }

    private static String string(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
    private static String stringOr(JsonObject value, String key, String fallback) {
        String result = string(value, key);
        return result.isBlank() ? fallback : result;
    }
    private static int number(JsonObject value, String key) { return numberOr(value, key, -1); }
    private static int numberOr(JsonObject value, String key, int fallback) {
        try { return value.get(key).getAsInt(); } catch (RuntimeException ex) { return fallback; }
    }
    private static int clamp(int n, int low, int high) { return Math.max(low, Math.min(high, n)); }

    @Override public void dispose() {
        disposed = true;
        ready = false;
        inspectionGeneration.incrementAndGet();
        curlScanGeneration.incrementAndGet();
        collectionScanGeneration.incrementAndGet();
        compareGeneration.incrementAndGet();
        if (compareTask != null) compareTask.cancel(true);
        cancelAssessment(false);
        refreshTimer.stop();
        if (project.getUserData(ACTIVE) == this) project.putUserData(ACTIVE, null);
        commands.dispose();
        browser.dispose();
    }
}
