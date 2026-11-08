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
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.ui.jcef.JBCefBrowser;
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
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** The approved web layout is the view; IntelliJ PSI and the existing Java parsers remain authoritative. */
public final class LensWebPanel implements Disposable {
    private static final int MAX_MESSAGE = 5_500_000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JBCefBrowser browser = new JBCefBrowser();
    private final JBCefJSQuery commands = JBCefJSQuery.create(browser);
    private final Timer refreshTimer = new Timer(350, event -> refresh());
    private final AtomicLong inspectionGeneration = new AtomicLong();
    private final AtomicLong scanGeneration = new AtomicLong();
    private final AtomicLong compareGeneration = new AtomicLong();
    private final Map<Integer, JavaEndpointInspector.EndpointCandidate> candidates = new HashMap<>();
    private final Map<String, JsonObject> priorScans = new HashMap<>();
    private final Map<String, String> priorInputs = new HashMap<>();
    private int candidateId = 0;
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
        String html = resource("/web/json-lens.html");
        String nativeScript = resource("/web/jsonlens-native.js");
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
                send(init);
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
            case "scan" -> scan(string(message, "source"), string(message, "raw"));
            case "source" -> {
                JsonObject cached = priorScans.get(string(message, "source"));
                if (cached != null) send(cached);
            }
            case "compare" -> compare(string(message, "raw"), string(message, "mode"));
            case "open" -> open(number(message, "id"));
            case "copy" -> CopyPasteManager.getInstance().setContents(
                new StringSelection(string(message, "text")));
            case "preferences" -> savePreferences(message);
            default -> { }
        }
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
                if (!priorInputs.isEmpty()) {
                    for (var entry : List.copyOf(priorInputs.entrySet())) scan(entry.getKey(), entry.getValue());
                }
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
        List<HttpRequestSample> requests;
        try {
            if (source.equals("curl")) {
                CurlRequestParser.Request parsed = new CurlRequestParser().parse(raw);
                requests = List.of(new HttpRequestSample("cURL", parsed.method(), parsed.url(),
                    parsed.path(), parsed.body()));
            } else {
                requests = new CollectionRequestParser().parse(raw);
            }
        } catch (RuntimeException ex) {
            JsonObject error = new JsonObject();
            error.addProperty("type", "scan-error");
            error.addProperty("source", source);
            error.addProperty("message", ex.getMessage());
            send(error);
            return;
        }
        priorInputs.put(source, raw);
        long generation = scanGeneration.incrementAndGet();
        ReadAction.nonBlocking(() -> {
            List<JavaEndpointInspector.EndpointCandidate> found = new JavaEndpointInspector(project).inspectAll();
            InteractionMatcher matcher = new InteractionMatcher();
            List<List<InteractionMatcher.Match>> rankings = new ArrayList<>();
            for (HttpRequestSample request : requests) rankings.add(matcher.rank(request, found));
            return new LensWebSearch.Result(found, requests, rankings);
        }).inSmartMode(project).expireWith(this).coalesceBy(this, "search")
            .finishOnUiThread(ModalityState.any(), result -> {
                if (disposed || generation != scanGeneration.get()) return;
                JsonObject payload = LensWebSearch.toJson(source, result, this::registerCandidate);
                priorScans.put(source, payload);
                send(payload);
            }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private int registerCandidate(JavaEndpointInspector.EndpointCandidate candidate) {
        int id = ++candidateId;
        candidates.put(id, candidate);
        return id;
    }

    private void compare(String raw, String mode) {
        if (snapshot == null) { notice("Selecione primeiro um endpoint Java."); return; }
        if (raw.isBlank() || raw.length() > 1_000_000) {
            notice("Informe um contrato JSON/YAML de até 1 MB.");
            return;
        }
        EndpointSnapshot current = snapshot;
        boolean response = mode.equals("response");
        long generation = compareGeneration.incrementAndGet();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "comparison");
            try {
                String fileName = raw.stripLeading().startsWith("{") || raw.stripLeading().startsWith("[")
                    ? "contract.json" : "contract.yaml";
                ContractParser.Parsed parsed = new ContractParser().parseText(
                    raw, fileName, current.verb, current.path, response);
                InteractionMatcher matcher = new InteractionMatcher();
                JavaEndpointInspector.EndpointCandidate fake = new JavaEndpointInspector.EndpointCandidate(current, null, 0);
                InteractionMatcher.Review review = response
                    ? matcher.compareResponseContract(fake, parsed) : matcher.compareContract(fake, parsed);
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
        if (!ready || disposed) return;
        String js = "window.JsonLensUI&&window.JsonLensUI.onNative(" + JSON.toJson(message) + ");";
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
        scanGeneration.incrementAndGet();
        compareGeneration.incrementAndGet();
        refreshTimer.stop();
        commands.dispose();
        browser.dispose();
    }
}
