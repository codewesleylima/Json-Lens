package dev.jsonlens.ui;

import com.google.gson.GsonBuilder;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.model.EndpointSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** UI subscriptions are bound to the content disposer; analysis never blocks the EDT. */
public final class LensPanel implements Disposable {
    private static final Color BACKGROUND = new Color(0x030508);
    private static final Color SURFACE = new Color(0x070B12);
    private static final Color TEXT = new Color(0xE7F0FA);
    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JLabel title = new JLabel("JSON Lens");
    private final JLabel subtitle = new JLabel();
    private final JLabel brandEyebrow = new JLabel();
    private final JLabel methodLabel = new JLabel();
    private final JLabel endpointTitle = new JLabel("Selecione um endpoint");
    private final JLabel endpoint = new JLabel("Sem endpoint selecionado");
    private final JLabel status = new JLabel("Aguardando análise...");
    private final JComboBox<JavaEndpointInspector.Option> methods = new JComboBox<>();
    private final JCheckBox schemaView = new JCheckBox();
    private final Editor requestEditor;
    private final Editor responseEditor;
    private final JTextArea notesView = textArea(12);
    private final JButton pinButton = new JButton();
    private final JButton settingsButton = new JButton();
    private final JButton copyRequestButton = new JButton();
    private final JButton copyResponseButton = new JButton();
    private final Timer refreshTimer;
    private final InteractionSearchPanel interactionSearchPanel;
    private JBTabbedPane tabs;
    private final AtomicLong generation = new AtomicLong();
    private VirtualFile pinnedFile;
    private int pinnedOffset;
    private boolean pinned;
    private boolean modifyingControls;
    private boolean disposed;
    private String forcedMethodKey;
    private String currentRequest = "";
    private String currentResponse = "";
    private EndpointSnapshot lastSnapshot;

    public LensPanel(Project project) {
        this.project = project;
        requestEditor = createJsonEditor(project);
        responseEditor = createJsonEditor(project);
        interactionSearchPanel = new InteractionSearchPanel(project);
        endpointTitle.setText(LensPreferences.text("Selecione um endpoint", "Select an endpoint"));
        endpoint.setText(LensPreferences.text("Sem endpoint selecionado", "No endpoint selected"));
        status.setText(LensPreferences.text("Aguardando análise...", "Waiting for analysis..."));
        root.setOpaque(true);
        root.setBackground(BACKGROUND);
        root.setBorder(JBUI.Borders.empty(12));
        root.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(640)));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        title.putClientProperty("jsonlens.fontRole", "heading");
        title.setForeground(TEXT);
        subtitle.setText(LensPreferences.text("Inspeção local de contratos API", "Local API contract inspection"));
        subtitle.setForeground(new Color(0x8FA4BA));
        subtitle.setFont(subtitle.getFont().deriveFont(12f));
        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brandEyebrow.setText("DEVELOPER TOOL");
        brandEyebrow.setForeground(new Color(0x8FA4BA));
        brandEyebrow.setFont(brandEyebrow.getFont().deriveFont(Font.BOLD, 10f));
        brand.add(brandEyebrow);
        brand.add(javax.swing.Box.createVerticalStrut(2));
        brand.add(title);
        brand.add(javax.swing.Box.createVerticalStrut(2));
        brand.add(subtitle);
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        URL logoUrl = LensPanel.class.getResource("/icons/jsonlens-logo.png");
        JLabel mark = logoUrl == null ? new JLabel() : new JLabel(new javax.swing.ImageIcon(
            new javax.swing.ImageIcon(logoUrl).getImage().getScaledInstance(JBUI.scale(60), JBUI.scale(40), java.awt.Image.SCALE_SMOOTH)));
        mark.setOpaque(false);
        mark.setPreferredSize(new Dimension(JBUI.scale(60), JBUI.scale(40)));
        JPanel identity = new JPanel(new BorderLayout(10, 0));
        identity.setOpaque(false);
        identity.add(mark, BorderLayout.WEST);
        identity.add(brand, BorderLayout.CENTER);
        heading.add(identity, BorderLayout.WEST);
        status.setOpaque(true);
        status.setBackground(SURFACE);
        status.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(uiColor("Component.borderColor", uiColor("Separator.foreground", Color.GRAY))),
            BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        status.setForeground(new Color(0x35D6E8));
        status.setFont(status.getFont().deriveFont(11f));
        JPanel headingActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        headingActions.setOpaque(false);
        headingActions.add(status);
        settingsButton.setText(LensPreferences.text("⚙ Config", "⚙ Settings"));
        settingsButton.setToolTipText(LensPreferences.text("Configurações de aparência e idioma", "Appearance and language settings"));
        settingsButton.setForeground(TEXT);
        settingsButton.setBackground(new Color(0x102033));
        settingsButton.setBorder(BorderFactory.createLineBorder(new Color(0x24415E)));
        settingsButton.setFocusPainted(false);
        settingsButton.addActionListener(e -> showSettings());
        headingActions.add(settingsButton);
        heading.add(headingActions, BorderLayout.EAST);

        endpointTitle.setFont(endpointTitle.getFont().deriveFont(Font.BOLD, 13f));
        endpointTitle.setForeground(TEXT);
        endpoint.setForeground(new Color(0x8FA4BA));
        JPanel endpointCard = new JPanel();
        endpointCard.setOpaque(true);
        endpointCard.setBackground(SURFACE);
        endpointCard.setLayout(new BoxLayout(endpointCard, BoxLayout.Y_AXIS));
        endpointCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x1C2A3A)),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        endpointCard.add(eyebrow(LensPreferences.text("ENDPOINT SELECIONADO", "SELECTED ENDPOINT")));
        endpointCard.add(javax.swing.Box.createVerticalStrut(5));
        endpointTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        endpoint.setAlignmentX(Component.LEFT_ALIGNMENT);
        endpointCard.add(endpointTitle);
        endpointCard.add(javax.swing.Box.createVerticalStrut(4));
        endpointCard.add(endpoint);

        methods.setBackground(SURFACE);
        methods.setForeground(TEXT);
        pinButton.setText(LensPreferences.text("Fixar seleção", "Pin selection"));
        pinButton.setForeground(TEXT);
        pinButton.setBackground(new Color(0x102033));
        pinButton.setBorder(BorderFactory.createLineBorder(new Color(0x24415E)));
        pinButton.setFocusPainted(false);
        schemaView.setOpaque(false);
        schemaView.setForeground(TEXT);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(heading);
        top.add(javax.swing.Box.createVerticalStrut(12));
        top.add(endpointCard);
        top.add(javax.swing.Box.createVerticalStrut(4));
        methodLabel.setText(LensPreferences.text("Método", "Method"));
        methodLabel.setForeground(new Color(0xB6C7D8));
        top.add(row(methodLabel, methods, pinButton));
        top.add(row(schemaView,
            copyRequestButton, copyResponseButton));
        copyRequestButton.setText(LensPreferences.text("Copiar request", "Copy request"));
        copyResponseButton.setText(LensPreferences.text("Copiar response", "Copy response"));
        styleButton(copyRequestButton);
        styleButton(copyResponseButton);
        copyRequestButton.addActionListener(e -> copy(currentRequest));
        copyResponseButton.addActionListener(e -> copy(currentResponse));
        schemaView.setText(LensPreferences.text("Mostrar schema", "Show schema"));
        root.add(top, BorderLayout.NORTH);

        tabs = new JBTabbedPane();
        setEditorText(requestEditor, LensPreferences.text("Abra um controller Spring MVC...", "Open a Spring MVC controller..."));
        setEditorText(responseEditor, LensPreferences.text(
            "Selecione um método para visualizar o response...", "Select a method to preview its response..."));
        tabs.addTab(LensPreferences.text("Request", "Request"), requestEditor.getComponent());
        tabs.addTab(LensPreferences.text("Response", "Response"), responseEditor.getComponent());

        tabs.addTab(LensPreferences.text("Localizar interação", "Find interaction"), interactionSearchPanel.component());
        notesView.setRows(5);
        notesView.setText(LensPreferences.text("Avisos e parâmetros de rota/query aparecerão aqui.",
            "Warnings and route/query parameters will appear here."));
        tabs.addTab(LensPreferences.text("Detalhes", "Details"), codeScrollPane(notesView));
        tabs.setBorder(JBUI.Borders.emptyTop(4));
        tabs.setBackground(BACKGROUND);
        tabs.setForeground(TEXT);
        root.add(tabs, BorderLayout.CENTER);

        refreshTimer = new Timer(350, e -> refresh());
        refreshTimer.setRepeats(false);
        pinButton.addActionListener(e -> togglePin());
        methods.addActionListener(e -> {
            if (modifyingControls) return;
            JavaEndpointInspector.Option option = (JavaEndpointInspector.Option) methods.getSelectedItem();
            if (option != null) { forcedMethodKey = option.key(); schedule(); }
        });
        schemaView.addActionListener(e -> renderCurrent());
        applyPreferences(LensTheme.named("Midnight"));
    }

    public JPanel component() { return root; }

    private void showSettings() {
        boolean english = LensPreferences.language().equals("en");
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(root),
            LensPreferences.text("Configurações do JSON Lens", "JSON Lens Settings"), JDialog.ModalityType.APPLICATION_MODAL);
        JTabbedPane sections = new JTabbedPane();
        JPanel general = settingsForm();
        JComboBox<String> language = new JComboBox<>(new String[]{"Português (Brasil)", "English"});
        language.setSelectedIndex(english ? 1 : 0);
        addSettingRow(general, 0, LensPreferences.text("Idioma", "Language"), language);
        sections.addTab(LensPreferences.text("Geral", "General"), general);

        JPanel appearance = settingsForm();
        JComboBox<String> themes = new JComboBox<>(new String[]{"Midnight", "Base16", "Ocean"});
        themes.setSelectedItem(LensPreferences.theme());
        JComboBox<String> infoFont = fontSelector(LensPreferences.infoFont());
        JComboBox<String> jsonFont = fontSelector(LensPreferences.jsonFont());
        JComboBox<String> curlFont = fontSelector(LensPreferences.curlFont());
        JSpinner infoSize = sizeSelector(LensPreferences.infoSize());
        JSpinner jsonSize = sizeSelector(LensPreferences.jsonSize());
        JSpinner curlSize = sizeSelector(LensPreferences.curlSize());
        addSettingRow(appearance, 0, LensPreferences.text("Tema de cores", "Color theme"), themes);
        addSettingRow(appearance, 1, LensPreferences.text("Informações", "Information"), fontAndSize(infoFont, infoSize));
        addSettingRow(appearance, 2, "JSON", fontAndSize(jsonFont, jsonSize));
        addSettingRow(appearance, 3, "cURL", fontAndSize(curlFont, curlSize));
        sections.addTab(LensPreferences.text("Aparência", "Appearance"), appearance);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(JBUI.Borders.empty(12));
        content.add(sections, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton apply = new JButton(LensPreferences.text("Aplicar", "Apply"));
        JButton cancel = new JButton(LensPreferences.text("Cancelar", "Cancel"));
        apply.addActionListener(e -> {
            LensTheme previousTheme = LensTheme.current();
            LensPreferences.save(language.getSelectedIndex() == 1 ? "en" : "pt-BR",
                String.valueOf(themes.getSelectedItem()), String.valueOf(infoFont.getSelectedItem()),
                (Integer) infoSize.getValue(), String.valueOf(jsonFont.getSelectedItem()),
                (Integer) jsonSize.getValue(), String.valueOf(curlFont.getSelectedItem()),
                (Integer) curlSize.getValue());
            applyLanguage();
            applyPreferences(previousTheme);
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        actions.add(cancel);
        actions.add(apply);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(true);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(JBUI.scale(480), JBUI.scale(360)));
        dialog.setLocationRelativeTo(root);
        dialog.setVisible(true);
    }

    private void applyLanguage() {
        subtitle.setText(LensPreferences.text("Inspeção local de contratos API", "Local API contract inspection"));
        methodLabel.setText(LensPreferences.text("Método", "Method"));
        schemaView.setText(LensPreferences.text("Mostrar schema", "Show schema"));
        pinButton.setText(pinned
            ? LensPreferences.text("Desafixar", "Unpin")
            : LensPreferences.text("Fixar seleção", "Pin selection"));
        copyRequestButton.setText(LensPreferences.text("Copiar request", "Copy request"));
        copyResponseButton.setText(LensPreferences.text("Copiar response", "Copy response"));
        settingsButton.setText(LensPreferences.text("⚙ Config", "⚙ Settings"));
        settingsButton.setToolTipText(LensPreferences.text("Configurações de aparência e idioma", "Appearance and language settings"));
        if (tabs != null) {
            tabs.setTitleAt(0, LensPreferences.text("Request", "Request"));
            tabs.setTitleAt(1, LensPreferences.text("Response", "Response"));
            tabs.setTitleAt(2, LensPreferences.text("Localizar interação", "Find interaction"));
            tabs.setTitleAt(3, LensPreferences.text("Detalhes", "Details"));
        }
        interactionSearchPanel.applyLanguage();
        if (lastSnapshot == null) {
            endpointTitle.setText(LensPreferences.text("Selecione um endpoint", "Select an endpoint"));
            endpoint.setText(LensPreferences.text("Sem endpoint selecionado", "No endpoint selected"));
        }
        status.setText(LensPreferences.text("Configurações aplicadas", "Settings applied"));
    }

    private void applyPreferences(LensTheme previousTheme) {
        LensAppearance.apply(root, previousTheme, LensTheme.current());
        refreshJsonEditor(requestEditor);
        refreshJsonEditor(responseEditor);
        interactionSearchPanel.applyPreferences();
        root.revalidate();
        root.repaint();
    }

    private static void refreshJsonEditor(Editor editor) {
        if (!(editor instanceof EditorEx editorEx)) return;
        LensTheme theme = LensTheme.current();
        EditorColorsScheme scheme = (EditorColorsScheme) EditorColorsManager.getInstance().getGlobalScheme().clone();
        scheme.setEditorFontName(LensPreferences.jsonFont());
        scheme.setEditorFontSize(LensPreferences.jsonSize());
        setJsonAttribute(scheme, "TEXT", theme.secondaryText());
        setJsonAttribute(scheme, "JSON_PROPERTY_KEY", theme.jsonKey());
        setJsonAttribute(scheme, "JSON_STRING", theme.jsonString());
        setJsonAttribute(scheme, "JSON_NUMBER", theme.jsonNumber());
        setJsonAttribute(scheme, "JSON_KEYWORD", theme.jsonKeyword());
        setJsonAttribute(scheme, "JSON_BRACES", theme.jsonPunctuation());
        setJsonAttribute(scheme, "JSON_COMMA", theme.jsonPunctuation());
        setJsonAttribute(scheme, "JSON_COLON", theme.jsonPunctuation());
        editorEx.setColorsScheme(scheme);
        editorEx.setBackgroundColor(theme.background());
    }

    private static JPanel settingsForm() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        return panel;
    }

    private static void addSettingRow(JPanel panel, int row, String label, java.awt.Component control) {
        GridBagConstraints name = new GridBagConstraints();
        name.gridx = 0;
        name.gridy = row;
        name.anchor = GridBagConstraints.LINE_START;
        name.insets = new Insets(5, 5, 5, 16);
        panel.add(new JLabel(label), name);
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = 1;
        value.gridy = row;
        value.weightx = 1;
        value.fill = GridBagConstraints.HORIZONTAL;
        value.insets = new Insets(5, 5, 5, 5);
        panel.add(control, value);
    }

    private static JComboBox<String> fontSelector(String selected) {
        JComboBox<String> fonts = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames());
        fonts.setEditable(true);
        fonts.setSelectedItem(selected);
        return fonts;
    }

    private static JSpinner sizeSelector(int selected) {
        return new JSpinner(new SpinnerNumberModel(selected, 9, 28, 1));
    }

    private static JPanel fontAndSize(JComboBox<String> font, JSpinner size) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(font, BorderLayout.CENTER);
        row.add(size, BorderLayout.EAST);
        return row;
    }

    public void start() {
        interactionSearchPanel.start();
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
        schedule();
    }

    private void togglePin() {
        if (!pinned) {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            VirtualFile file = FileEditorManager.getInstance(project).getSelectedFiles().length == 0 ? null : FileEditorManager.getInstance(project).getSelectedFiles()[0];
            if (file == null || editor == null) {
                status.setText(LensPreferences.text("Abra primeiro um método Java.", "Open a Java method first."));
                return;
            }
            pinnedFile = file;
            pinnedOffset = editor.getCaretModel().getOffset();
            pinned = true;
            pinButton.setText(LensPreferences.text("Desafixar", "Unpin"));
        } else {
            pinned = false;
            pinnedFile = null;
            forcedMethodKey = null;
            pinButton.setText(LensPreferences.text("Fixar seleção", "Pin selection"));
        }
        schedule();
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
            if (editor == null || files.length == 0) {
                status.setText(LensPreferences.text("Abra um arquivo Java.", "Open a Java file."));
                return;
            }
            file = files[0];
            caret = editor.getCaretModel().getOffset();
        }
        if (file == null || !file.isValid()) {
            status.setText(LensPreferences.text("Arquivo indisponível.", "File unavailable."));
            return;
        }
        // Documents are committed asynchronously by the IDE; a PSI modification event schedules a re-read.
        String selectionKey = forcedMethodKey;
        long ticket = generation.incrementAndGet();
        status.setText(LensPreferences.text("Analisando alterações...", "Analyzing changes..."));
        ReadAction.nonBlocking(() -> new JavaEndpointInspector(project).inspect(file, caret, selectionKey))
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), inspection -> {
                if (disposed || ticket != generation.get()) return;
                applyInspection(inspection);
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void applyInspection(JavaEndpointInspector.Inspection inspection) {
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
            endpointTitle.setText(LensPreferences.text("Nenhum endpoint reconhecido", "No endpoint recognized"));
            endpoint.setText(inspection.note());
            setEditorText(requestEditor, "—");
            setEditorText(responseEditor, "—");
            status.setText(LensPreferences.text("Sem endpoint", "No endpoint"));
            notesView.setText(inspection.note());
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
        status.setText(LensPreferences.text("JSON atualizado • análise local", "JSON updated • local analysis"));
    }

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
        if (editor instanceof EditorEx editorEx) {
            LensTheme theme = LensTheme.current();
            EditorColorsScheme scheme = (EditorColorsScheme) EditorColorsManager.getInstance().getGlobalScheme().clone();
            scheme.setEditorFontName(LensPreferences.jsonFont());
            scheme.setEditorFontSize(LensPreferences.jsonSize());
            setJsonAttribute(scheme, "TEXT", theme.secondaryText());
            setJsonAttribute(scheme, "JSON_PROPERTY_KEY", theme.jsonKey());
            setJsonAttribute(scheme, "JSON_STRING", theme.jsonString());
            setJsonAttribute(scheme, "JSON_NUMBER", theme.jsonNumber());
            setJsonAttribute(scheme, "JSON_KEYWORD", theme.jsonKeyword());
            setJsonAttribute(scheme, "JSON_BRACES", theme.jsonPunctuation());
            setJsonAttribute(scheme, "JSON_COMMA", theme.jsonPunctuation());
            setJsonAttribute(scheme, "JSON_COLON", theme.jsonPunctuation());
            editorEx.setColorsScheme(scheme);
            editorEx.setBackgroundColor(theme.background());
        }
        return editor;
    }

    private static void setJsonAttribute(EditorColorsScheme scheme, String keyName, Color foreground) {
        TextAttributesKey key = TextAttributesKey.find(keyName);
        if (key == null) return;
        TextAttributes attributes = scheme.getAttributes(key);
        TextAttributes adjusted = attributes == null ? new TextAttributes() : attributes.clone();
        adjusted.setForegroundColor(foreground);
        scheme.setAttributes(key, adjusted);
    }

    private static void setEditorText(Editor editor, String text) {
        WriteAction.run(() -> editor.getDocument().setText(text));
        editor.getCaretModel().moveToOffset(0);
    }

    private static JTextArea textArea(int fontSize) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(LensPreferences.infoFont(), Font.PLAIN, fontSize));
        area.putClientProperty("jsonlens.fontRole", "info");
        area.setTabSize(2);
        area.setLineWrap(false);
        area.setMargin(new java.awt.Insets(12, 14, 12, 14));
        area.setBackground(BACKGROUND);
        area.setForeground(new Color(0xC7D2E3));
        return area;
    }
    private static JLabel eyebrow(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(0x8FA4BA));
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
    private static void styleButton(JButton button) {
        LensTheme theme = LensTheme.current();
        button.setOpaque(true);
        button.setForeground(theme.primaryText());
        button.setBackground(theme.surface());
        button.setBorder(BorderFactory.createLineBorder(theme.border()));
        button.setFocusPainted(false);
    }
    private static void copy(String text) { CopyPasteManager.getInstance().setContents(new StringSelection(text)); }
    @Override public void dispose() {
        disposed = true;
        refreshTimer.stop();
        generation.incrementAndGet();
        interactionSearchPanel.dispose();
        EditorFactory.getInstance().releaseEditor(requestEditor);
        EditorFactory.getInstance().releaseEditor(responseEditor);
    }
}
