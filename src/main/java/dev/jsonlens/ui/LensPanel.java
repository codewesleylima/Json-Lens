package dev.jsonlens.ui;

import com.google.gson.GsonBuilder;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.contract.ContractComparator;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.model.EndpointSnapshot;
import dev.jsonlens.model.SchemaNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** UI subscriptions are bound to the content disposer; analysis never blocks the EDT. */
public final class LensPanel implements Disposable {
    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JLabel title = new JLabel("JSON Lens");
    private final JLabel endpointTitle = new JLabel("Selecione um endpoint");
    private final JLabel endpoint = new JLabel("Sem endpoint selecionado");
    private final JLabel status = new JLabel("Aguardando análise...");
    private final JComboBox<JavaEndpointInspector.Option> methods = new JComboBox<>();
    private final JComboBox<String> checkTarget = new JComboBox<>(new String[] {"Request", "Response"});
    private final JCheckBox schemaView = new JCheckBox("Mostrar schema");
    private final Editor requestEditor;
    private final Editor responseEditor;
    private final JTextArea findingsView = textArea(12);
    private final JTextArea notesView = textArea(12);
    private final JTextField contractRoute = new JTextField(18);
    private final JTextField contractVerb = new JTextField(5);
    private final JLabel contractFileLabel = new JLabel("Sem contrato importado");
    private final JComboBox<Path> availableContracts = new JComboBox<>();
    private final JButton pinButton = new JButton("Fixar seleção");
    private final Timer refreshTimer;
    private final Timer contractWatchTimer;
    private final ContractParser parser = new ContractParser();
    private final ContractComparator comparator = new ContractComparator();
    private final AtomicLong generation = new AtomicLong();
    private VirtualFile pinnedFile;
    private int pinnedOffset;
    private boolean pinned;
    private boolean modifyingControls;
    private boolean disposed;
    private String forcedMethodKey;
    private String currentRequest = "";
    private String currentResponse = "";
    private Path contractFile;
    private final Set<Path> savedContracts = new LinkedHashSet<>();
    private long contractModified;
    private EndpointSnapshot lastSnapshot;
    private final PropertiesComponent state;

    public LensPanel(Project project) {
        this.project = project;
        this.state = PropertiesComponent.getInstance(project);
        requestEditor = createJsonEditor(project);
        responseEditor = createJsonEditor(project);
        root.setBorder(JBUI.Borders.empty(12));
        root.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(640)));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        JLabel subtitle = new JLabel("Inspeção local de contratos API");
        subtitle.setForeground(uiColor("Label.disabledForeground", Color.GRAY));
        subtitle.setFont(subtitle.getFont().deriveFont(12f));
        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.add(eyebrow("DEVELOPER TOOL"));
        brand.add(javax.swing.Box.createVerticalStrut(2));
        brand.add(title);
        brand.add(javax.swing.Box.createVerticalStrut(2));
        brand.add(subtitle);
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        JLabel mark = new JLabel("JL", SwingConstants.CENTER);
        mark.setOpaque(true);
        mark.setForeground(Color.WHITE);
        mark.setBackground(new JBColor(new Color(0x426B91), new Color(0x315B81)));
        mark.setFont(mark.getFont().deriveFont(Font.BOLD, 12f));
        mark.setPreferredSize(new Dimension(JBUI.scale(34), JBUI.scale(34)));
        mark.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel identity = new JPanel(new BorderLayout(10, 0));
        identity.setOpaque(false);
        identity.add(mark, BorderLayout.WEST);
        identity.add(brand, BorderLayout.CENTER);
        heading.add(identity, BorderLayout.WEST);
        status.setOpaque(true);
        status.setBackground(new JBColor(new Color(0x34373D), new Color(0xEEF1F5)));
        status.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(uiColor("Component.borderColor", uiColor("Separator.foreground", Color.GRAY))),
            BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        status.setForeground(uiColor("Label.disabledForeground", Color.GRAY));
        status.setFont(status.getFont().deriveFont(11f));
        heading.add(status, BorderLayout.EAST);

        endpointTitle.setFont(endpointTitle.getFont().deriveFont(Font.BOLD, 13f));
        endpoint.setForeground(uiColor("Label.disabledForeground", Color.GRAY));
        JPanel endpointCard = new JPanel();
        endpointCard.setOpaque(true);
        endpointCard.setBackground(new JBColor(new Color(0x2D3036), new Color(0xF6F7F9)));
        endpointCard.setLayout(new BoxLayout(endpointCard, BoxLayout.Y_AXIS));
        endpointCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(uiColor("Component.borderColor", uiColor("Separator.foreground", Color.GRAY))),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        endpointCard.add(eyebrow("ENDPOINT SELECIONADO"));
        endpointCard.add(javax.swing.Box.createVerticalStrut(5));
        endpointTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        endpoint.setAlignmentX(Component.LEFT_ALIGNMENT);
        endpointCard.add(endpointTitle);
        endpointCard.add(javax.swing.Box.createVerticalStrut(4));
        endpointCard.add(endpoint);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(heading);
        top.add(javax.swing.Box.createVerticalStrut(12));
        top.add(endpointCard);
        top.add(javax.swing.Box.createVerticalStrut(4));
        top.add(row(new JLabel("Método"), methods, pinButton));
        top.add(row(schemaView,
            button("Copiar request", e -> copy(currentRequest)),
            button("Copiar response", e -> copy(currentResponse))));
        root.add(top, BorderLayout.NORTH);

        JBTabbedPane tabs = new JBTabbedPane();
        setEditorText(requestEditor, "Abra um controller Spring MVC...");
        setEditorText(responseEditor, "Selecione um método para visualizar o response...");
        tabs.addTab("Request", requestEditor.getComponent());
        tabs.addTab("Response", responseEditor.getComponent());

        JPanel contractPane = new JPanel(new BorderLayout(5, 5));
        contractPane.setOpaque(false);
        JPanel contractHeader = new JPanel();
        contractHeader.setOpaque(false);
        contractHeader.setLayout(new BoxLayout(contractHeader, BoxLayout.Y_AXIS));
        contractHeader.setBorder(JBUI.Borders.empty(8, 0, 10, 0));
        contractHeader.add(row(button("Importar contrato", this::chooseContract), button("Remover", e -> clearContract())));
        contractHeader.add(row(new JLabel("Contrato"), availableContracts));
        contractFileLabel.setForeground(uiColor("Label.disabledForeground", Color.GRAY));
        contractHeader.add(row(contractFileLabel));
        contractHeader.add(row(new JLabel("Comparar"), checkTarget, new JLabel("Método"), contractVerb));
        contractHeader.add(row(new JLabel("Path externo (vazio = rota local)"), contractRoute));
        contractPane.add(contractHeader, BorderLayout.NORTH);
        findingsView.setText("Importe um contrato da aplicação de destino para verificar a compatibilidade.");
        contractPane.add(codeScrollPane(findingsView), BorderLayout.CENTER);
        tabs.addTab("Comparação", contractPane);
        notesView.setRows(5);
        notesView.setText("Avisos e parâmetros de rota/query aparecerão aqui.");
        tabs.addTab("Detalhes", codeScrollPane(notesView));
        tabs.setBorder(JBUI.Borders.emptyTop(4));
        root.add(tabs, BorderLayout.CENTER);

        refreshTimer = new Timer(350, e -> refresh());
        refreshTimer.setRepeats(false);
        contractWatchTimer = new Timer(1600, e -> pollExternalContract());
        pinButton.addActionListener(e -> togglePin());
        availableContracts.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value == null ? "—" : ((Path) value).getFileName().toString(), index, selected, focus);
                return this;
            }
        });
        availableContracts.addActionListener(e -> {
            if (!modifyingControls) {
                contractFile = (Path) availableContracts.getSelectedItem();
                contractModified = contractFile == null ? 0 : lastModified(contractFile);
                contractFileLabel.setText(contractFile == null ? "Sem contrato importado" : shorten(contractFile.getFileName().toString(), 45));
                schedule();
            }
        });
        methods.addActionListener(e -> {
            if (modifyingControls) return;
            JavaEndpointInspector.Option option = (JavaEndpointInspector.Option) methods.getSelectedItem();
            if (option != null) { forcedMethodKey = option.key(); schedule(); }
        });
        schemaView.addActionListener(e -> renderCurrent());
        checkTarget.addActionListener(e -> schedule());
        listenText(contractRoute, this::schedule);
        listenText(contractVerb, this::schedule);
        String saved = state.getValue("jsonlens.contractFiles", "");
        if (!saved.isBlank()) for (String entry : saved.split("\n")) {
            if (!entry.isBlank()) {
                Path p = Path.of(entry);
                if (Files.isRegularFile(p)) addContract(p, false);
            }
        }
        String latest = state.getValue("jsonlens.contractFile");
        if (latest != null && Files.isRegularFile(Path.of(latest))) setContract(Path.of(latest));
        else if (!savedContracts.isEmpty()) setContract(savedContracts.iterator().next());
    }

    public JPanel component() { return root; }

    public void start() {
        project.getMessageBus().connect(this).subscribe(PsiModificationTracker.TOPIC, (PsiModificationTracker.Listener) this::schedule);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(FileEditorManagerEvent event) {
                if (!pinned) { forcedMethodKey = null; schedule(); }
            }
        });
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent e) {
                if (!pinned && e.getEditor().getProject() == project) { forcedMethodKey = null; schedule(); }
            }
        }, this);
        contractWatchTimer.start();
        schedule();
    }

    private void togglePin() {
        if (!pinned) {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            VirtualFile file = FileEditorManager.getInstance(project).getSelectedFiles().length == 0 ? null : FileEditorManager.getInstance(project).getSelectedFiles()[0];
            if (file == null || editor == null) { status.setText("Abra primeiro um método Java."); return; }
            pinnedFile = file;
            pinnedOffset = editor.getCaretModel().getOffset();
            pinned = true;
            pinButton.setText("Desafixar");
        } else {
            pinned = false;
            pinnedFile = null;
            forcedMethodKey = null;
            pinButton.setText("Fixar seleção");
        }
        schedule();
    }

    private void chooseContract(ActionEvent ignored) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecione um contrato externo JSON, OpenAPI JSON ou YAML");
        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) setContract(chooser.getSelectedFile().toPath());
    }

    private void setContract(Path path) {
        addContract(path.toAbsolutePath().normalize(), true);
        contractFile = path.toAbsolutePath().normalize();
        contractModified = lastModified(contractFile);
        contractFileLabel.setText(shorten(contractFile.getFileName().toString(), 45));
        contractFileLabel.setToolTipText(contractFile.toString());
        state.setValue("jsonlens.contractFile", contractFile.toString());
        schedule();
    }

    private void clearContract() {
        if (contractFile != null) savedContracts.remove(contractFile);
        updateContractList();
        contractFile = savedContracts.isEmpty() ? null : savedContracts.iterator().next();
        if (contractFile == null) state.unsetValue("jsonlens.contractFile");
        else state.setValue("jsonlens.contractFile", contractFile.toString());
        contractFileLabel.setText(contractFile == null ? "Sem contrato importado" : contractFile.getFileName().toString());
        findingsView.setText("Selecione um contrato da aplicação de destino para verificar a compatibilidade.");
        schedule();
    }

    private void addContract(Path path, boolean select) {
        if (!Files.isRegularFile(path)) return;
        savedContracts.add(path);
        updateContractList();
        if (select) availableContracts.setSelectedItem(path);
    }

    private void updateContractList() {
        modifyingControls = true;
        try {
            Path previous = contractFile;
            availableContracts.removeAllItems();
            savedContracts.forEach(availableContracts::addItem);
            if (previous != null && savedContracts.contains(previous)) availableContracts.setSelectedItem(previous);
        } finally { modifyingControls = false; }
        state.setValue("jsonlens.contractFiles", String.join("\n", savedContracts.stream().map(Path::toString).toList()));
    }

    private static long lastModified(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (Exception ignored) { return -1L; }
    }
    private void pollExternalContract() {
        if (contractFile != null) {
            long modified = lastModified(contractFile);
            if (contractModified != modified) { contractModified = modified; schedule(); }
        }
    }

    private void schedule() {
        if (disposed) return;
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::schedule); return; }
        refreshTimer.restart();
    }

    private void refresh() {
        if (disposed || project.isDisposed()) return;
        VirtualFile file;
        int caret;
        if (pinned) { file = pinnedFile; caret = pinnedOffset; }
        else {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
            if (editor == null || files.length == 0) { status.setText("Abra um arquivo Java."); return; }
            file = files[0];
            caret = editor.getCaretModel().getOffset();
        }
        if (file == null || !file.isValid()) { status.setText("Arquivo indisponível."); return; }
        // Documents are committed asynchronously by the IDE; a PSI modification event schedules a re-read.
        String selectionKey = forcedMethodKey;
        String selectedContractVerb = contractVerb.getText().trim();
        String selectedContractPath = contractRoute.getText().trim();
        Path external = contractFile;
        boolean response = checkTarget.getSelectedIndex() == 1;
        long ticket = generation.incrementAndGet();
        status.setText("Analisando alterações...");
        ReadAction.nonBlocking(() -> new JavaEndpointInspector(project).inspect(file, caret, selectionKey))
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), inspection -> {
                if (disposed || ticket != generation.get()) return;
                applyInspection(inspection, external, selectedContractVerb, selectedContractPath, response, ticket);
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void applyInspection(JavaEndpointInspector.Inspection inspection, Path external, String contractMethod,
                                 String contractPath, boolean response, long ticket) {
        modifyingControls = true;
        try {
            methods.removeAllItems();
            for (JavaEndpointInspector.Option option : inspection.options()) methods.addItem(option);
            for (int i = 0; i < methods.getItemCount(); i++) {
                if (methods.getItemAt(i).key().equals(inspection.selectedKey())) { methods.setSelectedIndex(i); break; }
            }
        } finally { modifyingControls = false; }
        lastSnapshot = inspection.snapshot();
        if (lastSnapshot == null) {
            endpointTitle.setText("Nenhum endpoint reconhecido");
            endpoint.setText(inspection.note());
            setEditorText(requestEditor, "—");
            setEditorText(responseEditor, "—");
            status.setText("Sem endpoint");
            notesView.setText(inspection.note());
            findingsView.setText("Selecione um endpoint compatível para comparar.");
            return;
        }
        forcedMethodKey = inspection.selectedKey();
        endpointTitle.setText(lastSnapshot.title);
        endpoint.setText(lastSnapshot.direction + " • " + lastSnapshot.verb + " " + lastSnapshot.path);
        currentRequest = "";
        currentResponse = "";
        renderCurrent();
        List<String> notices = new ArrayList<>();
        if (!inspection.note().isBlank()) notices.add(inspection.note());
        notices.addAll(lastSnapshot.parameterNotes);
        notices.addAll(lastSnapshot.warnings);
        notesView.setText(String.join("\n\n", notices));
        status.setText("JSON atualizado • análise local");
        if (external == null) {
            findingsView.setText("Importe o OpenAPI, JSON Schema ou JSON de exemplo do receptor.\nPara integração de saída, selecione o método @FeignClient.");
            return;
        }
        String http = contractMethod.isBlank() ? lastSnapshot.verb : contractMethod;
        String route = contractPath.isBlank() ? lastSnapshot.path : contractPath;
        EndpointSnapshot analyzed = lastSnapshot;
        status.setText("Comparando contrato externo...");
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            ComparisonReport report = compareContract(external, http, route, response, analyzed);
            SwingUtilities.invokeLater(() -> {
                if (disposed || ticket != generation.get()) return;
                findingsView.setText(report.text());
                findingsView.setCaretPosition(0);
                status.setText(report.status());
            });
        });
    }

    private ComparisonReport compareContract(Path external, String http, String route, boolean response, EndpointSnapshot analyzed) {
        try {
            ContractParser.Parsed imported = parser.parse(external, http, route, response);
            SchemaNode actual = response ? analyzed.response : analyzed.request;
            List<ContractComparator.Finding> findings = comparator.compare(actual, imported.node());
            long errors = findings.stream().filter(f -> f.severity() == ContractComparator.Severity.ERROR).count();
            long warnings = findings.stream().filter(f -> f.severity() == ContractComparator.Severity.WARNING).count();
            StringBuilder report = new StringBuilder("Fonte: ").append(imported.sourceType()).append("\n")
                .append("Comparando: ").append(response ? "Response" : "Request").append(" • ").append(http).append(" ").append(route).append("\n")
                .append(errors).append(" erro(s) • ").append(warnings).append(" aviso(s)\n\n");
            for (String warning : imported.warnings()) report.append("AVISO: ").append(warning).append("\n");
            for (ContractComparator.Finding finding : findings) report.append(finding).append("\n");
            if (analyzed.direction.startsWith("INBOUND"))
                report.append("\nATENÇÃO: comparação do controller de ENTRADA; isso não prova o payload de uma chamada de SAÍDA. Selecione o método Feign usado na integração.\n");
            String newStatus = errors > 0 ? "Contrato: divergências detectadas" : "Contrato: sem erro estrutural demonstrado (" + warnings + " avisos)";
            return new ComparisonReport(report.toString(), newStatus);
        } catch (Exception ex) {
            return new ComparisonReport("Falha ao ler contrato: " + ex.getMessage() + "\n\nConfira método, path, arquivo e estrutura OpenAPI.",
                "Contrato: não foi possível verificar");
        }
    }

    private record ComparisonReport(String text, String status) {}

    private void renderCurrent() {
        if (lastSnapshot == null) return;
        var gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        boolean schema = schemaView.isSelected();
        currentRequest = gson.toJson(schema ? lastSnapshot.request.toSchema(0) : lastSnapshot.request.sample(0));
        currentResponse = gson.toJson(schema ? lastSnapshot.response.toSchema(0) : lastSnapshot.response.sample(0));
        setEditorText(requestEditor, currentRequest);
        setEditorText(responseEditor, currentResponse);
    }

    private static Editor createJsonEditor(Project project) {
        var document = EditorFactory.getInstance().createDocument("");
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("json");
        Editor editor = EditorFactory.getInstance().createEditor(document, project, fileType, true);
        editor.getSettings().setLineNumbersShown(true);
        editor.getSettings().setUseSoftWraps(true);
        if (editor instanceof EditorEx editorEx) editorEx.setFontSize(15);
        return editor;
    }

    private static void setEditorText(Editor editor, String text) {
        WriteAction.run(() -> editor.getDocument().setText(text));
        editor.getCaretModel().moveToOffset(0);
    }

    private static JTextArea textArea(int fontSize) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        area.setTabSize(2);
        area.setLineWrap(false);
        area.setMargin(new java.awt.Insets(12, 14, 12, 14));
        area.setBackground(uiColor("TextArea.background", uiColor("Panel.background", Color.WHITE)));
        area.setForeground(uiColor("TextArea.foreground", uiColor("Label.foreground", Color.BLACK)));
        return area;
    }
    private static JLabel eyebrow(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(uiColor("Label.disabledForeground", Color.GRAY));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        return label;
    }
    private static JScrollPane codeScrollPane(JTextArea area) {
        JScrollPane pane = new JScrollPane(area);
        pane.setBorder(BorderFactory.createLineBorder(uiColor("Component.borderColor", uiColor("Separator.foreground", Color.GRAY))));
        pane.getViewport().setBackground(area.getBackground());
        return pane;
    }
    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
    private static JPanel row(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        panel.setOpaque(false);
        for (Component component : components) panel.add(component);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }
    private static JButton button(String label, java.awt.event.ActionListener listener) {
        JButton button = new JButton(label);
        button.addActionListener(listener);
        return button;
    }
    private static void copy(String text) { CopyPasteManager.getInstance().setContents(new StringSelection(text)); }
    private static String shorten(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 3) + "..."; }
    private void listenText(JTextField field, Runnable listener) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
            private void changed() { if (!modifyingControls) listener.run(); }
        });
    }
    @Override public void dispose() {
        disposed = true;
        refreshTimer.stop();
        contractWatchTimer.stop();
        generation.incrementAndGet();
        EditorFactory.getInstance().releaseEditor(requestEditor);
        EditorFactory.getInstance().releaseEditor(responseEditor);
    }
}
