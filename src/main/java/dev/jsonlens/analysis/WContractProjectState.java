package dev.jsonlens.analysis;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Local per-project contract associations and bounded evaluation history.
 * Contracts stay in IntelliJ's workspace storage. Never contains API tokens by design.
 */
@Service(Service.Level.PROJECT)
@State(name = "WContractWorkspace", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class WContractProjectState implements PersistentStateComponent<WContractProjectState.Data> {
    public static class Binding {
        public String key = "";
        public String source = "";
        public String document = "";
        public boolean response;
        public Binding() {}
        public Binding(String key, String source, String document, boolean response) {
            this.key = key;
            this.source = source;
            this.document = document;
            this.response = response;
        }
    }

    public static class Run {
        public String timestamp = "";
        public String scope = "";
        public int total;
        public int evaluated;
        public int passed;
        public int errors;
        public int warnings;
        public int suspected;
        public int unresolved;
        public int compatibility = -1;
        public String summary = "";
        public Run() {}
    }

    public static class Data {
        public List<Binding> bindings = new ArrayList<>();
        public List<Run> history = new ArrayList<>();
    }

    private Data data = new Data();

    @Override public synchronized @NotNull Data getState() {
        return data;
    }

    @Override public synchronized void loadState(@NotNull Data state) {
        data = state;
        if (data.bindings == null) data.bindings = new ArrayList<>();
        if (data.history == null) data.history = new ArrayList<>();
    }

    public synchronized void bind(String key, String source, String document, boolean response) {
        if (key == null || key.isBlank() || document == null || document.isBlank())
            throw new IllegalArgumentException("Endpoint e contrato são obrigatórios.");
        if (document.length() > 180_000) throw new IllegalArgumentException("Contrato excede 180 KB.");
        data.bindings.removeIf(binding -> key.equals(binding.key) && binding.response == response);
        if (data.bindings.size() >= 100) throw new IllegalArgumentException("Limite de 100 contratos associados por projeto.");
        data.bindings.add(new Binding(key, source, document, response));
    }

    public synchronized void unbind(String key, boolean response) {
        data.bindings.removeIf(binding -> key.equals(binding.key) && binding.response == response);
    }

    public synchronized @Nullable Binding getBinding(String key, boolean response) {
        for (Binding binding : data.bindings)
            if (key.equals(binding.key) && binding.response == response)
                return new Binding(binding.key, binding.source, binding.document, binding.response);
        return null;
    }

    public synchronized List<Binding> bindings() {
        List<Binding> copies = new ArrayList<>();
        for (Binding b : data.bindings) copies.add(new Binding(b.key,b.source,b.document,b.response));
        return List.copyOf(copies);
    }

    public synchronized void record(Run run) {
        data.history.add(0, run);
        if (data.history.size() > 30) data.history = new ArrayList<>(data.history.subList(0,30));
    }

    public synchronized List<Run> history() {
        return List.copyOf(data.history);
    }

    public static String key(dev.jsonlens.model.EndpointSnapshot endpoint) {
        return (endpoint.title == null ? "?" : endpoint.title) + "|" +
            (endpoint.verb == null ? "ANY" : endpoint.verb) + "|" +
            (endpoint.path == null ? "/" : endpoint.path) + "|" +
            (endpoint.direction == null ? "" : endpoint.direction);
    }
}
