package dev.jsonlens.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import dev.jsonlens.analysis.InteractionMatcher;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.contract.CollectionRequestParser;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.contract.CurlRequestParser;
import dev.jsonlens.contract.HttpRequestSample;
import dev.jsonlens.model.SchemaNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Offline request-to-code discovery for cURL commands and API client collections. */
final class InteractionSearchPanel implements Disposable {
    private static final Color BACKGROUND = new Color(0x030508);
    private static final Color SURFACE = new Color(0x070B12);
    private static final Color CARD = new Color(0x0B111A);
    private static final Color BORDER = new Color(0x1C2A3A);
    private static final Color ACCENT = new Color(0x35D6E8);
    private static final Pattern JSON_TOKEN = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)|\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|\\b(?:true|false|null)\\b");
    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final JLabel status = new JLabel("Pronto para analisar");
    private final JLabel collectionLabel = new JLabel("Nenhuma collection carregada");
    private final JLabel resultCount = new JLabel("0 resultados");
    private final JLabel heroEyebrow = new JLabel();
    private final JLabel heroHeading = new JLabel();
    private final JLabel heroHint = new JLabel();
    private final JLabel resultTitle = new JLabel();
    private final JLabel safetyHint = new JLabel();
    private final JLabel curlHint = new JLabel();
    private final JLabel collectionHint = new JLabel();
    private final JTextArea curlInput = new JTextArea();
    private final JTextPane collectionPreview = new JTextPane();
    private final JPanel resultCards = new JPanel();
    private final JBTabbedPane sourceTabs = new JBTabbedPane();
    private final JButton analyzeButton = new JButton();
    private JButton curlFileButton;
    private JButton collectionFileButton;
    private final AtomicLong generation = new AtomicLong();
    private final CurlRequestParser curlParser = new CurlRequestParser();
    private final CollectionRequestParser collectionParser = new CollectionRequestParser();
    private final ContractParser contractParser = new ContractParser();
    private final InteractionMatcher matcher = new InteractionMatcher();
    private final Timer liveUpdateTimer = new Timer(550, e -> updateLiveResults());
    private final ScanResult[] scansBySource = new ScanResult[2];
    private List<HttpRequestSample> collectionRequests = List.of();
    private List<JavaEndpointInspector.EndpointCandidate> cachedEndpoints = List.of();
    private boolean hasAnalyzed;
    private boolean disposed;

    InteractionSearchPanel(Project project) {
        this.project = project;
        root.setOpaque(true);
        root.setBackground(BACKGROUND);
        root.setBorder(JBUI.Borders.empty(4, 0, 0, 0));
        root.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(520)));

        JPanel hero = new JPanel(new BorderLayout(8, 6));
        hero.setOpaque(true);
        hero.setBackground(SURFACE);
        hero.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            JBUI.Borders.empty(12, 14)));
        JPanel heroText = new JPanel();
        heroText.setOpaque(false);
        heroText.setLayout(new BoxLayout(heroText, BoxLayout.Y_AXIS));
        heroEyebrow.setText("LOCAL SCAN  /  API ROUTING");
        heroEyebrow.setForeground(ACCENT);
        heroEyebrow.setFont(heroEyebrow.getFont().deriveFont(Font.BOLD, 10f));
        heroHeading.setForeground(Color.WHITE);
        heroHeading.setFont(heroHeading.getFont().deriveFont(Font.BOLD, 17f));
        heroHeading.putClientProperty("jsonlens.fontRole", "heading");
        heroHint.setForeground(new Color(0xB6C7D8));
        heroHint.setFont(heroHint.getFont().deriveFont(11f));
        heroText.add(heroEyebrow);
        heroText.add(Box.createVerticalStrut(3));
        heroText.add(heroHeading);
        heroText.add(Box.createVerticalStrut(3));
        heroText.add(heroHint);
        hero.add(heroText, BorderLayout.CENTER);
        status.setForeground(ACCENT);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 10f));
        status.setHorizontalAlignment(JLabel.RIGHT);
        hero.add(status, BorderLayout.EAST);
        root.add(hero, BorderLayout.NORTH);

        sourceTabs.addTab(LensPreferences.text("cURL", "cURL"), createCurlTab());
        sourceTabs.addTab(LensPreferences.text("Collection", "Collection"), createCollectionTab());
        sourceTabs.setOpaque(true);
        sourceTabs.setBackground(SURFACE);
        sourceTabs.setForeground(new Color(0xD6E2F0));
        sourceTabs.addChangeListener(e -> sourceChanged());

        resultCards.setOpaque(false);
        resultCards.setLayout(new BoxLayout(resultCards, BoxLayout.Y_AXIS));
        showMessage("Cole um cURL ou carregue uma collection para encontrar endpoints no projeto.");
        JScrollPane resultScroll = new JScrollPane(resultCards);
        resultScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        resultScroll.getViewport().setBackground(BACKGROUND);
        resultScroll.setBackground(BACKGROUND);
        resultScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(14));

        JPanel results = new JPanel(new BorderLayout(0, 5));
        results.setOpaque(false);
        JPanel resultHeader = new JPanel(new BorderLayout());
        resultHeader.setOpaque(false);
        resultTitle.setText("MATCH SCORE  /  HEURÍSTICO");
        resultTitle.setForeground(new Color(0x71859B));
        resultTitle.setFont(resultTitle.getFont().deriveFont(Font.BOLD, 10f));
        resultCount.setForeground(new Color(0x8FA4BA));
        resultHeader.add(resultTitle, BorderLayout.WEST);
        resultHeader.add(resultCount, BorderLayout.EAST);
        results.add(resultHeader, BorderLayout.NORTH);
        results.add(resultScroll, BorderLayout.CENTER);

        JPanel inputArea = new JPanel(new BorderLayout(0, 6));
        inputArea.setOpaque(false);
        inputArea.add(sourceTabs, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.setOpaque(false);
        safetyHint.setForeground(new Color(0x71859B));
        safetyHint.setFont(safetyHint.getFont().deriveFont(10f));
        actions.add(safetyHint, BorderLayout.CENTER);
        actions.add(analyzeButton, BorderLayout.EAST);
        inputArea.add(actions, BorderLayout.SOUTH);

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, inputArea, results);
        split.setResizeWeight(0.42);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setDividerSize(JBUI.scale(8));
        split.setOpaque(true);
        split.setBackground(BACKGROUND);
        root.add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.42));

        analyzeButton.addActionListener(this::analyze);
        styleButton(analyzeButton);
        curlInput.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { inputChanged(0); }
            @Override public void removeUpdate(DocumentEvent e) { inputChanged(0); }
            @Override public void changedUpdate(DocumentEvent e) { inputChanged(0); }
        });
        applyLanguage();
    }

    JPanel component() { return root; }

    void applyLanguage() {
        boolean english = LensPreferences.language().equals("en");
        heroHeading.setText(LensPreferences.text("Localizar interação", "Find interaction"));
        heroHint.setText(LensPreferences.text(
            "cURL, Postman ou Insomnia → endpoints mais prováveis",
            "cURL, Postman, or Insomnia → most likely endpoints"));
        resultTitle.setText(LensPreferences.text("MATCH SCORE  /  HEURÍSTICO", "MATCH SCORE  /  HEURISTIC"));
        safetyHint.setText(LensPreferences.text(
            "Não executa requests nem valida responses", "Requests are not executed and responses are not validated"));
        analyzeButton.setText(LensPreferences.text("Localizar no código", "Find in code"));
        curlHint.setText(LensPreferences.text(
            "Cole o comando. Ele será apenas interpretado, nunca executado.",
            "Paste a command. It is parsed only and never executed."));
        curlFileButton.setText(LensPreferences.text("Abrir arquivo cURL...", "Open cURL file..."));
        collectionHint.setText(english
            ? "<html>Load a <b>Postman v2.1</b> or <b>Insomnia JSON</b> export.<br>Requests are analyzed and ranked separately.</html>"
            : "<html>Carregue um export <b>Postman v2.1</b> ou <b>Insomnia JSON</b>.<br>Todos os requests serão analisados e ranqueados separadamente.</html>");
        collectionFileButton.setText(LensPreferences.text("Carregar collection JSON...", "Load collection JSON..."));
        if (collectionRequests.isEmpty()) collectionLabel.setText(LensPreferences.text(
            "Nenhuma collection carregada", "No collection loaded"));
        if (collectionRequests.isEmpty()) collectionPreview.setText(LensPreferences.text(
            "A collection carregada será organizada aqui por request.",
            "The loaded collection will be organized here by request."));
        sourceTabs.setTitleAt(0, "cURL");
        sourceTabs.setTitleAt(1, LensPreferences.text("Collection", "Collection"));
        if (scansBySource[sourceTabs.getSelectedIndex()] != null) {
            renderResults(scansBySource[sourceTabs.getSelectedIndex()]);
        } else {
            status.setText(LensPreferences.text("Pronto para analisar", "Ready to analyze"));
        }
        applyJsonColors(collectionPreview);
    }

    void start() {
        project.getMessageBus().connect(this).subscribe(PsiModificationTracker.TOPIC,
            (PsiModificationTracker.Listener) this::projectChanged);
    }

    private JPanel createCurlTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        curlHint.setForeground(new Color(0x8FA4BA));
        panel.add(curlHint, BorderLayout.NORTH);
        curlInput.setRows(6);
        curlInput.setFont(new Font(LensPreferences.curlFont(), Font.PLAIN, LensPreferences.curlSize()));
        curlInput.putClientProperty("jsonlens.fontRole", "curl");
        curlInput.setMargin(new java.awt.Insets(8, 10, 8, 10));
        curlInput.setBackground(SURFACE);
        curlInput.setForeground(new Color(0xC7D2E3));
        curlInput.setCaretColor(ACCENT);
        curlInput.setToolTipText("Exemplo: curl -X POST https://host/api -H \"Content-Type: application/json\" -d '{\"id\":1}'");
        panel.add(scroll(curlInput), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        curlFileButton = new JButton();
        styleButton(curlFileButton);
        curlFileButton.addActionListener(this::chooseCurlFile);
        footer.add(curlFileButton, BorderLayout.WEST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createCollectionTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);
        collectionHint.setForeground(new Color(0x8FA4BA));
        JPanel picker = new JPanel();
        picker.setOpaque(false);
        picker.setLayout(new BoxLayout(picker, BoxLayout.Y_AXIS));
        collectionFileButton = new JButton();
        styleButton(collectionFileButton);
        collectionFileButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        collectionFileButton.addActionListener(this::chooseCollection);
        collectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        collectionLabel.setForeground(new Color(0x8FA4BA));
        collectionLabel.setBorder(JBUI.Borders.emptyTop(8));
        picker.add(collectionFileButton);
        picker.add(collectionLabel);
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setOpaque(false);
        header.add(collectionHint, BorderLayout.NORTH);
        header.add(picker, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        collectionPreview.setEditable(false);
        collectionPreview.setFont(new Font(LensPreferences.jsonFont(), Font.PLAIN, LensPreferences.jsonSize()));
        collectionPreview.putClientProperty("jsonlens.fontRole", "json");
        collectionPreview.setBackground(SURFACE);
        collectionPreview.setForeground(new Color(0xC7D2E3));
        collectionPreview.setMargin(new java.awt.Insets(8, 10, 8, 10));
        collectionPreview.setText(LensPreferences.text(
            "A collection carregada será organizada aqui por request.",
            "The loaded collection will be organized here by request."));
        applyJsonColors(collectionPreview);
        panel.add(scroll(collectionPreview), BorderLayout.CENTER);
        return panel;
    }

    private void chooseCurlFile(ActionEvent ignored) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(LensPreferences.text("Selecione um arquivo com comando cURL",
            "Choose a file containing a cURL command"));
        chooser.setFileFilter(new FileNameExtensionFilter("cURL ou texto (*.curl, *.txt, *.sh, *.ps1)", "curl", "txt", "sh", "ps1"));
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        inputChanged(0);
        try {
            Path file = chooser.getSelectedFile().toPath();
            if (Files.size(file) > 2_000_000) throw new IllegalArgumentException("Arquivo cURL acima de 2 MB.");
            curlInput.setText(Files.readString(file));
            curlInput.setCaretPosition(0);
            status.setText(LensPreferences.text("cURL carregado", "cURL loaded"));
        } catch (IOException | IllegalArgumentException ex) {
            status.setText(LensPreferences.text("Falha ao carregar cURL", "Failed to load cURL"));
            showInputError(LensPreferences.text("Não foi possível carregar o arquivo: ", "Could not load file: ")
                + ex.getMessage(), 0);
        }
    }

    private void chooseCollection(ActionEvent ignored) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(LensPreferences.text("Selecione um export Postman ou Insomnia",
            "Choose a Postman or Insomnia export"));
        chooser.setFileFilter(new FileNameExtensionFilter("Collection JSON (*.json)", "json"));
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        String previousLabel = collectionLabel.getText();
        collectionLabel.setText(LensPreferences.text("Lendo collection...", "Reading collection..."));
        status.setText(LensPreferences.text("Carregando", "Loading"));
        try {
            Path file = chooser.getSelectedFile().toPath();
            if (Files.size(file) > 5_000_000) throw new IllegalArgumentException("Collection acima de 5 MB.");
            List<HttpRequestSample> parsedRequests = collectionParser.parse(Files.readString(file));
            collectionRequests = parsedRequests;
            collectionLabel.setText(file.getFileName() + " • " + collectionRequests.size()
                + " " + LensPreferences.text("request(s)", "request(s)"));
            collectionPreview.setText(formatCollection(collectionRequests));
            applyJsonColors(collectionPreview);
            status.setText(LensPreferences.text("Collection pronta", "Collection ready"));
            inputChanged(1);
        } catch (IOException | IllegalArgumentException ex) {
            collectionLabel.setText(previousLabel);
            status.setText(LensPreferences.text("Falha ao carregar", "Load failed"));
            showInputError(LensPreferences.text("Não foi possível ler a collection: ",
                "Could not read collection: ") + ex.getMessage(), 1);
        }
    }

    private static String formatCollection(List<HttpRequestSample> requests) {
        JsonArray normalized = new JsonArray();
        for (HttpRequestSample request : requests) {
            JsonObject item = new JsonObject();
            item.addProperty("name", request.name());
            item.addProperty("method", request.method());
            item.addProperty("url", request.url());
            item.addProperty("path", request.path());
            if (request.body() != null && !request.body().isBlank()) {
                try {
                    item.add("body", JsonParser.parseString(request.body()));
                } catch (RuntimeException ignored) {
                    item.addProperty("body", request.body());
                }
            }
            normalized.add(item);
        }
        JsonObject document = new JsonObject();
        document.addProperty("collection", "JSON Lens");
        document.add("requests", normalized);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(document);
    }

    private void inputChanged(int sourceIndex) {
        if (sourceTabs.getSelectedIndex() == sourceIndex) markStale();
    }

    private void sourceChanged() {
        liveUpdateTimer.stop();
        int sourceIndex = sourceTabs.getSelectedIndex();
        ScanResult previous = scansBySource[sourceIndex];
        if (previous != null) {
            renderResults(previous);
            return;
        }
        status.setText(LensPreferences.text("Entrada pronta para análise", "Input ready to analyze"));
        showMessage(LensPreferences.text(
            "Clique em “Localizar no código” para analisar esta entrada.",
            "Click “Find in code” to analyze this input."));
    }

    private void showInputError(String message, int sourceIndex) {
        if (sourceIndex == sourceTabs.getSelectedIndex() && scansBySource[sourceIndex] == null) {
            showMessage(message);
        } else if (sourceIndex == sourceTabs.getSelectedIndex()) {
            status.setText(message);
        }
    }

    private void analyze(ActionEvent ignored) {
        analyzeCurrentInput();
    }

    private void analyzeCurrentInput() {
        int sourceIndex = sourceTabs.getSelectedIndex();
        final List<HttpRequestSample> requests;
        try {
            if (sourceIndex == 0) {
                CurlRequestParser.Request curl = curlParser.parse(curlInput.getText());
                requests = List.of(new HttpRequestSample("cURL", curl.method(), curl.url(), curl.path(), curl.body()));
            } else {
                if (collectionRequests.isEmpty()) throw new IllegalArgumentException("Carregue uma collection Postman ou Insomnia.");
                requests = collectionRequests;
            }
        } catch (IllegalArgumentException ex) {
            status.setText(LensPreferences.text("Entrada inválida", "Invalid input"));
            showInputError(ex.getMessage(), sourceIndex);
            return;
        }

        long ticket = generation.incrementAndGet();
        analyzeButton.setEnabled(false);
        hasAnalyzed = true;
        status.setText(LensPreferences.text("Escaneando projeto...", "Scanning project..."));
        if (scansBySource[sourceIndex] == null)
            showMessage(LensPreferences.text("Procurando controllers e clientes Feign no projeto...",
                "Looking for controllers and Feign clients in the project..."));
        ReadAction.nonBlocking(() -> {
            try {
                List<JavaEndpointInspector.EndpointCandidate> endpoints = new JavaEndpointInspector(project).inspectAll();
                List<RequestMatches> results = requests.stream()
                    .map(request -> new RequestMatches(request, matcher.rank(request, endpoints)))
                    .toList();
                return new ScanResult(results, endpoints, null, sourceIndex);
            } catch (ProcessCanceledException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                return new ScanResult(List.of(), List.of(), ex.getMessage(), sourceIndex);
            }
        }).inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), scan -> {
                if (disposed || ticket != generation.get()) return;
                analyzeButton.setEnabled(true);
                if (scan.error() != null) {
                    status.setText(LensPreferences.text("Análise interrompida", "Analysis interrupted"));
                    showInputError(LensPreferences.text("Não foi possível analisar o projeto: ",
                        "Could not analyze project: ") + scan.error(), scan.sourceIndex());
                } else {
                    renderResults(scan);
                }
            })
            .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void updateLiveResults() {
        if (disposed || project.isDisposed()) return;
        if (cachedEndpoints.isEmpty()) {
            if (hasAnalyzed) analyzeCurrentInput();
            return;
        }
        try {
            List<HttpRequestSample> requests;
            int sourceIndex = sourceTabs.getSelectedIndex();
            if (sourceIndex == 0) {
                CurlRequestParser.Request curl = curlParser.parse(curlInput.getText());
                requests = List.of(new HttpRequestSample("cURL", curl.method(), curl.url(), curl.path(), curl.body()));
            } else {
                if (collectionRequests.isEmpty()) throw new IllegalArgumentException("Carregue uma collection Postman ou Insomnia.");
                requests = collectionRequests;
            }
            List<RequestMatches> results = requests.stream()
                .map(request -> new RequestMatches(request, matcher.rank(request, cachedEndpoints)))
                .toList();
            renderResults(new ScanResult(results, cachedEndpoints, null, sourceIndex));
        } catch (IllegalArgumentException ex) {
            status.setText(LensPreferences.text("Contrato em edição", "Contract being edited"));
            showInputError(LensPreferences.text("Ajuste a entrada para continuar: ",
                "Fix the input to continue: ") + ex.getMessage(), sourceTabs.getSelectedIndex());
        }
    }

    private void projectChanged() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::projectChanged);
            return;
        }
        cachedEndpoints = List.of();
        markStale();
    }

    private void renderResults(ScanResult scan) {
        if (scan.error() != null) return;
        scansBySource[scan.sourceIndex()] = scan;
        cachedEndpoints = scan.candidates();
        hasAnalyzed = true;
        if (sourceTabs.getSelectedIndex() != scan.sourceIndex()) return;
        resultCards.removeAll();
        resultCount.setText(scan.candidates().size() + " " + LensPreferences.text("endpoint(s)", "endpoint(s)")
            + " • " + scan.requests().size() + " " + LensPreferences.text("request(s)", "request(s)"));
        status.setText(LensPreferences.text("Análise concluída", "Analysis complete"));
        if (scan.candidates().isEmpty()) {
            showMessage(LensPreferences.text(
                "Nenhum endpoint Java reconhecido. São analisados @Controller/@RestController e @FeignClient.",
                "No Java endpoint recognized. Scanned @Controller/@RestController and @FeignClient."));
            return;
        }
        for (RequestMatches result : scan.requests()) {
            resultCards.add(requestHeading(result.request()));
            addDirectionResults(LensPreferences.text("RECEBEM  /  CONTROLLERS", "RECEIVE  /  CONTROLLERS"),
                result.matches(), true);
            addDirectionResults(LensPreferences.text("ENVIAM  /  FEIGN CLIENTS", "SEND  /  FEIGN CLIENTS"),
                result.matches(), false);
        }
        LensAppearance.apply(resultCards, LensTheme.named("Midnight"), LensTheme.current());
        recolorJsonPanes(resultCards);
        resultCards.revalidate();
        resultCards.repaint();
    }

    private void addDirectionResults(String title, List<InteractionMatcher.Match> matches, boolean inbound) {
        List<InteractionMatcher.Match> selected = matches.stream()
            .filter(match -> match.endpoint().snapshot().direction.startsWith(inbound ? "INBOUND" : "OUTBOUND"))
            .limit(3).toList();
        JPanel heading = sectionHeading(title);
        resultCards.add(heading);
        if (selected.isEmpty()) {
            JLabel empty = new JLabel(LensPreferences.text("Nenhum endpoint desse tipo encontrado.",
                "No endpoint of this type was found."));
            empty.setForeground(new Color(0x8FA4BA));
            empty.setBorder(JBUI.Borders.empty(0, 8, 8, 8));
            resultCards.add(empty);
        } else {
            for (InteractionMatcher.Match match : selected) resultCards.add(matchCard(match));
        }
    }

    private JPanel requestHeading(HttpRequestSample request) {
        JPanel header = new JPanel(new BorderLayout(4, 2));
        header.setOpaque(true);
        header.setBackground(new Color(0x0A1420));
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT), JBUI.Borders.empty(8, 10)));
        JLabel name = new JLabel(request.name());
        name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
        name.setForeground(new Color(0xE7F0FA));
        JLabel route = new JLabel(request.method() + " " + request.path());
        route.setForeground(new Color(0x8FA4BA));
        route.setFont(route.getFont().deriveFont(Font.PLAIN, 11f));
        header.add(name, BorderLayout.NORTH);
        header.add(route, BorderLayout.SOUTH);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        return header;
    }

    private JPanel matchCard(InteractionMatcher.Match match) {
        var endpoint = match.endpoint();
        var snapshot = endpoint.snapshot();
        JPanel card = new JPanel(new BorderLayout(8, 4));
        card.setOpaque(true);
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            JBUI.Borders.empty(8, 9)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(snapshot.title);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(new Color(0xE7F0FA));
        JLabel route = new JLabel(snapshot.verb + " " + snapshot.path + "  •  "
            + LensPreferences.text("match", "match") + " " + match.score() + "/100");
        route.setForeground(new Color(0x8FA4BA));
        route.setFont(route.getFont().deriveFont(Font.PLAIN, 11f));
        identity.add(title);
        identity.add(route);
        JLabel score = new JLabel(match.adherencePercent() < 0 ? "—" : match.adherencePercent() + "%");
        score.setFont(score.getFont().deriveFont(Font.BOLD, 15f));
        score.setToolTipText(LensPreferences.text(
            "Aderência estimada entre o contrato e o payload declarado pelo endpoint",
            "Estimated adherence between the contract and the endpoint's declared payload"));
        score.setForeground(match.adherencePercent() < 0 ? new Color(0x8FA4BA) : scoreColor(match.adherencePercent()));
        top.add(identity, BorderLayout.CENTER);
        top.add(score, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);

        JPanel detail = new JPanel();
        detail.setOpaque(false);
        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        for (String evidence : match.evidence()) {
            JLabel line = new JLabel("• " + evidence);
            line.setForeground(new Color(0xB6C7D8));
            line.setFont(line.getFont().deriveFont(10f));
            detail.add(line);
        }
        String path = relativePath(endpoint.file());
        JLabel source = new JLabel(path);
        source.setForeground(new Color(0x71859B));
        source.setFont(source.getFont().deriveFont(Font.ITALIC, 10f));
        detail.add(Box.createVerticalStrut(3));
        detail.add(source);
        JPanel expanded = createMatchDetails(match);
        expanded.setVisible(false);
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(detail);
        content.add(expanded);
        card.add(content, BorderLayout.CENTER);

        JButton expand = new JButton(LensPreferences.text("Expandir", "Expand"));
        styleButton(expand);
        expand.addActionListener(e -> {
            boolean visible = !expanded.isVisible();
            expanded.setVisible(visible);
            expand.setText(visible ? LensPreferences.text("Recolher", "Collapse")
                : LensPreferences.text("Expandir", "Expand"));
            card.revalidate();
            card.repaint();
        });
        JButton pasteContract = new JButton(LensPreferences.text("Colar contrato", "Paste contract"));
        styleButton(pasteContract);
        pasteContract.addActionListener(e -> comparePastedContract(match));
        JButton open = new JButton(LensPreferences.text("Abrir código", "Open code"));
        styleButton(open);
        open.addActionListener(e -> open(endpoint.file(), endpoint.offset()));
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setOpaque(false);
        footer.add(expand);
        footer.add(pasteContract);
        footer.add(open);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private JPanel createMatchDetails(InteractionMatcher.Match match) {
        JPanel details = new JPanel();
        details.setOpaque(true);
        details.setBackground(SURFACE);
        details.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER), JBUI.Borders.empty(8, 2)));
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        if (match.adherencePercent() < 0) {
            details.add(detailLabel(LensPreferences.text(
                "Aderência não calculada: o cURL não contém body JSON.",
                "Adherence not calculated: the cURL contains no JSON body.")));
        } else {
            String state = adherenceLabel(match.adherencePercent(), match.bodyFindings(), match.valueMappings());
            details.add(detailLabel(state + " • " + match.adherencePercent() + "% "
                + LensPreferences.text("de aderência estimada", "estimated adherence")));
            details.add(detailLabel(LensPreferences.text(
                "O exemplo do cURL é comparado com a estrutura do endpoint; não é uma validação em runtime.",
                "The cURL example is compared with the endpoint structure; this is not runtime validation.")));
            if (match.endpoint().snapshot().direction.startsWith("OUTBOUND"))
                details.add(detailLabel(LensPreferences.text(
                    "Origem de valores é inferida estaticamente em setters/atribuições diretas; mapeadores e transformações indiretas podem exigir revisão.",
                    "Value origins are inferred statically from direct setters/assignments; mappers and indirect transformations may need review.")));
            addFindings(details, match.bodyFindings(), match.valueMappings());
        }
        if (match.contractBody() != null && !match.contractBody().isBlank())
            addJsonSection(details, LensPreferences.text("Contrato informado (body do cURL)", "Provided contract (cURL body)"),
                prettyJson(match.contractBody()));
        addJsonSection(details, match.endpoint().snapshot().direction.startsWith("INBOUND")
            ? LensPreferences.text("O que este endpoint espera receber (request)", "What this endpoint expects to receive (request)")
            : LensPreferences.text("Payload que este cliente declara enviar (request)", "Payload this client declares it sends (request)"),
            sampleJson(match.endpoint().snapshot().request));
        addJsonSection(details, LensPreferences.text("Response declarado pelo endpoint", "Response declared by the endpoint"),
            sampleJson(match.endpoint().snapshot().response));
        return details;
    }

    private void comparePastedContract(InteractionMatcher.Match match) {
        LensTheme theme = LensTheme.current();
        JTextPane input = new JTextPane();
        input.setFont(new Font(LensPreferences.jsonFont(), Font.PLAIN, LensPreferences.jsonSize()));
        input.putClientProperty("jsonlens.fontRole", "json");
        input.setBackground(theme.surface());
        input.setForeground(theme.secondaryText());
        input.setCaretColor(theme.accent());
        input.setPreferredSize(new Dimension(JBUI.scale(620), JBUI.scale(240)));
        input.setText(match.contractBody() == null ? "" : match.contractBody());
        applyJsonColors(input);
        JTextArea findings = new JTextArea(7, 56);
        findings.setEditable(false);
        findings.putClientProperty("jsonlens.fontRole", "info");
        findings.setLineWrap(true);
        findings.setWrapStyleWord(true);
        findings.setBackground(theme.background());
        findings.setForeground(theme.secondaryText());
        JLabel score = new JLabel(LensPreferences.text(
            "Cole um exemplo JSON, JSON Schema ou OpenAPI para calcular a aderência.",
            "Paste a JSON example, JSON Schema, or OpenAPI contract to calculate adherence."));
        score.setForeground(theme.accent());
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(theme.background());
        content.add(score, BorderLayout.NORTH);
        JPanel editors = new JPanel(new java.awt.GridLayout(2, 1, 0, 6));
        editors.setOpaque(false);
        editors.add(scroll(input));
        editors.add(scroll(findings));
        content.add(editors, BorderLayout.CENTER);

        Runnable refresh = () -> {
            String raw = input.getText();
            try {
                String sourceName = raw.stripLeading().startsWith("{") ? "contract.json" : "contract.yaml";
                var endpoint = match.endpoint();
                String trimmed = raw.stripLeading();
                boolean openApi = raw.contains("\"openapi\"") || raw.contains("\"swagger\"")
                    || trimmed.startsWith("openapi:") || trimmed.startsWith("swagger:")
                    || raw.contains("\nopenapi:") || raw.contains("\nswagger:");
                if (openApi) {
                    List<String> scores = new ArrayList<>();
                    StringBuilder report = new StringBuilder();
                    try {
                        ContractParser.Parsed requestContract = contractParser.parseText(raw, sourceName,
                            endpoint.snapshot().verb, endpoint.snapshot().path, false);
                        InteractionMatcher.Review requestReview = matcher.compareContract(endpoint, requestContract);
                        scores.add("Request " + requestReview.adherencePercent() + "%");
                        report.append("REQUEST\n")
                            .append(formatFindings(requestReview.findings(), requestReview.valueMappings(), requestContract.warnings()));
                    } catch (Exception ex) {
                        report.append("REQUEST — ").append(ex.getMessage()).append('\n');
                    }
                    try {
                        ContractParser.Parsed responseContract = contractParser.parseText(raw, sourceName,
                            endpoint.snapshot().verb, endpoint.snapshot().path, true);
                        InteractionMatcher.Review responseReview = matcher.compareResponseContract(endpoint, responseContract);
                        scores.add("Response " + responseReview.adherencePercent() + "%");
                        report.append("\nRESPONSE\n")
                            .append(formatFindings(responseReview.findings(), responseReview.valueMappings(), responseContract.warnings()));
                    } catch (Exception ex) {
                        report.append("\nRESPONSE — ").append(ex.getMessage()).append('\n');
                    }
                    boolean adherent = !scores.isEmpty() && scores.stream().allMatch(value -> value.endsWith("100%"));
                    score.setText(scores.isEmpty()
                        ? "Contrato OpenAPI sem request ou response JSON compatível"
                        : (adherent ? "ADERENTE • " : "NÃO ADERENTE • ") + "OpenAPI: " + String.join("  •  ", scores));
                    findings.setText(report.toString());
                } else {
                    ContractParser.Parsed parsed = contractParser.parseText(raw, sourceName,
                        endpoint.snapshot().verb, endpoint.snapshot().path, false);
                    InteractionMatcher.Review review = matcher.compareContract(endpoint, parsed);
                    score.setText(adherenceLabel(review.adherencePercent(), review.findings(), review.valueMappings())
                        + " • " + review.adherencePercent() + "% • " + parsed.sourceType());
                    findings.setText(formatFindings(review.findings(), review.valueMappings(), parsed.warnings()));
                }
                findings.setCaretPosition(0);
            } catch (Exception ex) {
                score.setText("Contrato ainda inválido ou incompleto");
                findings.setText(ex.getMessage() == null ? "Não foi possível interpretar o contrato." : ex.getMessage());
            }
        };
        Timer dialogTimer = new Timer(350, e -> refresh.run());
        dialogTimer.setRepeats(false);
        Timer colorTimer = new Timer(120, e -> applyJsonColors(input));
        colorTimer.setRepeats(false);
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { colorTimer.restart(); dialogTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { colorTimer.restart(); dialogTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
        refresh.run();
        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setBackground(theme.background());
        wrapper.setBorder(JBUI.Borders.empty(12));
        wrapper.add(content, BorderLayout.CENTER);
        JButton close = new JButton(LensPreferences.text("Fechar", "Close"));
        styleButton(close);
        close.addActionListener(e -> SwingUtilities.getWindowAncestor(wrapper).dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(close);
        wrapper.add(actions, BorderLayout.SOUTH);
        LensAppearance.apply(wrapper, LensTheme.named("Midnight"), theme);
        recolorJsonPanes(wrapper);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(root),
            LensPreferences.text("Aderência do contrato • ", "Contract adherence • ")
                + match.endpoint().snapshot().title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setContentPane(wrapper);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(true);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                dialogTimer.stop();
                colorTimer.stop();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(root);
        dialog.setVisible(true);
    }

    private void addFindings(JPanel parent, List<dev.jsonlens.contract.ContractComparator.Finding> findings,
                             List<JavaEndpointInspector.ValueMapping> mappings) {
        Set<String> messages = new LinkedHashSet<>();
        for (var finding : findings) {
            if (finding.severity() == dev.jsonlens.contract.ContractComparator.Severity.INFO) continue;
            messages.add(finding.pointer() + " — " + finding.message());
        }
        for (JavaEndpointInspector.ValueMapping mapping : mappings) {
            if (mapping.targetField().equals("$")) {
                messages.add("Mapeamento de valores não comprovado: " + mapping.expression());
            } else if (mapping.hasDifferentSource()) {
                messages.add("$." + mapping.targetField() + " — possível erro de mapeamento: recebe "
                    + mapping.expression() + " (origem inferida: " + mapping.sourceField() + ").");
            } else if (mapping.sourceField() == null) {
                messages.add("$." + mapping.targetField() + " — não foi possível inferir estaticamente a origem de "
                    + mapping.expression() + ".");
            }
        }
        if (messages.isEmpty()) {
            parent.add(detailLabel(LensPreferences.text(
                "Nenhuma divergência estrutural ou de origem foi detectada.",
                "No structural or value-origin mismatch was detected.")));
            return;
        }
        for (String message : messages) parent.add(detailLabel(message));
    }

    private String formatFindings(List<dev.jsonlens.contract.ContractComparator.Finding> findings,
                                  List<JavaEndpointInspector.ValueMapping> mappings, List<String> warnings) {
        StringBuilder text = new StringBuilder();
        for (var finding : findings) {
            if (finding.severity() == dev.jsonlens.contract.ContractComparator.Severity.INFO) continue;
            text.append(finding.severity()).append(' ').append(finding.pointer()).append(" — ")
                .append(finding.message()).append('\n');
        }
        for (JavaEndpointInspector.ValueMapping mapping : mappings) {
            if (mapping.targetField().equals("$")) {
                text.append("WARNING — ").append(mapping.expression()).append('\n');
            } else if (mapping.hasDifferentSource()) {
                text.append("WARNING $.").append(mapping.targetField()).append(" — recebe ")
                    .append(mapping.expression()).append(" (origem inferida: ").append(mapping.sourceField())
                    .append("); confirme se essa conversão é intencional.\n");
            } else if (mapping.sourceField() == null) {
                text.append("INFO $.").append(mapping.targetField()).append(" — origem não inferida: ")
                    .append(mapping.expression()).append('\n');
            }
        }
        for (String warning : warnings) text.append("INFO — ").append(warning).append('\n');
        if (text.isEmpty()) text.append("Aderente: nenhuma incompatibilidade detectada. Isso não substitui testes de integração.");
        return text.toString();
    }

    private static String adherenceLabel(int percent,
                                         List<dev.jsonlens.contract.ContractComparator.Finding> findings,
                                         List<JavaEndpointInspector.ValueMapping> mappings) {
        if (percent == 100) return LensPreferences.text("ADERENTE", "COMPLIANT");
        boolean mismatch = findings.stream()
            .anyMatch(finding -> finding.severity() == dev.jsonlens.contract.ContractComparator.Severity.ERROR)
            || mappings.stream().anyMatch(JavaEndpointInspector.ValueMapping::hasDifferentSource);
        return mismatch ? LensPreferences.text("NÃO ADERENTE", "NOT COMPLIANT")
            : LensPreferences.text("ADERÊNCIA PARCIAL", "PARTIALLY COMPLIANT");
    }

    private void addJsonSection(JPanel parent, String title, String value) {
        parent.add(detailLabel(title));
        JTextPane preview = jsonPreview(value);
        JScrollPane pane = new JScrollPane(preview);
        pane.setBorder(BorderFactory.createLineBorder(BORDER));
        pane.setPreferredSize(new Dimension(JBUI.scale(360), JBUI.scale(145)));
        pane.getViewport().setBackground(LensTheme.current().background());
        parent.add(pane);
        parent.add(Box.createVerticalStrut(6));
    }

    private static JLabel detailLabel(String text) {
        JLabel label = new JLabel("<html><div style='width:340px'>" + escapeHtml(text) + "</div></html>");
        label.setForeground(LensTheme.current().secondaryText());
        label.setFont(label.getFont().deriveFont(11f));
        label.setBorder(JBUI.Borders.empty(3, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JTextPane jsonPreview(String json) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFont(new Font(LensPreferences.jsonFont(), Font.PLAIN, LensPreferences.jsonSize()));
        pane.putClientProperty("jsonlens.fontRole", "json");
        pane.setBackground(LensTheme.current().background());
        pane.setForeground(LensTheme.current().secondaryText());
        pane.setMargin(new java.awt.Insets(8, 10, 8, 10));
        pane.setText(json);
        applyJsonColors(pane);
        return pane;
    }

    private static void applyJsonColors(JTextPane pane) {
        String json = pane.getText();
        if (json.length() > 100_000) return;
        LensTheme theme = LensTheme.current();
        StyledDocument document = pane.getStyledDocument();
        SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setForeground(base, theme.secondaryText());
        document.setCharacterAttributes(0, document.getLength(), base, true);
        Matcher tokens = JSON_TOKEN.matcher(json);
        while (tokens.find()) {
            String token = tokens.group();
            Color color;
            if (token.startsWith("\"")) {
                boolean key = json.substring(tokens.end()).stripLeading().startsWith(":");
                color = key ? theme.jsonKey() : theme.jsonString();
            } else if (token.equals("true") || token.equals("false")) color = theme.jsonKeyword();
            else if (token.equals("null")) color = new Color(0xF07178);
            else color = theme.jsonNumber();
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setForeground(style, color);
            document.setCharacterAttributes(tokens.start(), tokens.end() - tokens.start(), style, true);
        }
    }

    private static String sampleJson(SchemaNode node) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(node.sample(0));
    }

    private static String prettyJson(String raw) {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(com.google.gson.JsonParser.parseString(raw));
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    private void open(VirtualFile file, int offset) {
        if (file == null || !file.isValid()) {
            status.setText("Arquivo do endpoint indisponível");
            return;
        }
        new OpenFileDescriptor(project, file, offset).navigate(true);
    }

    private String relativePath(VirtualFile file) {
        if (file == null) return "Arquivo indisponível";
        String basePath = project.getBasePath();
        VirtualFile base = basePath == null ? null : LocalFileSystem.getInstance().findFileByPath(basePath);
        String relative = base == null ? null : VfsUtilCore.getRelativePath(file, base, '/');
        return relative == null ? file.getPath() : relative;
    }

    private void markStale() {
        if (disposed) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::markStale);
            return;
        }
        generation.incrementAndGet();
        analyzeButton.setEnabled(true);
        if (!cachedEndpoints.isEmpty()) {
            status.setText("Contrato alterado • atualizando aderência...");
            liveUpdateTimer.restart();
        } else {
            status.setText("Entrada pronta para análise");
            if (hasAnalyzed) liveUpdateTimer.restart();
            else showMessage("Clique em “Localizar no código” para atualizar as sugestões.");
        }
    }

    private void showMessage(String message) {
        resultCards.removeAll();
        JLabel label = new JLabel("<html><div style='width:270px'>" + escapeHtml(message) + "</div></html>");
        label.setForeground(new Color(0x8FA4BA));
        label.setBorder(JBUI.Borders.empty(12));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultCards.add(label);
        LensAppearance.apply(label, LensTheme.named("Midnight"), LensTheme.current());
        resultCards.revalidate();
        resultCards.repaint();
        resultCount.setText("—");
    }

    private JPanel sectionHeading(String text) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        row.setOpaque(false);
        JLabel label = new JLabel(text);
        label.setForeground(ACCENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        row.add(label);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static JScrollPane scroll(JTextArea area) {
        JScrollPane pane = new JScrollPane(area);
        pane.setBorder(BorderFactory.createLineBorder(BORDER));
        pane.getViewport().setBackground(area.getBackground());
        return pane;
    }

    private static JScrollPane scroll(JTextPane pane) {
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(pane.getBackground());
        return scroll;
    }

    private static Color scoreColor(int score) {
        if (score >= 95) return new Color(0x4FE0B2);
        if (score >= 70) return new Color(0xFFD166);
        return new Color(0xFF7B72);
    }

    private static void styleButton(JButton button) {
        button.setOpaque(true);
        LensTheme theme = LensTheme.current();
        button.setForeground(theme.primaryText());
        button.setBackground(theme.surface());
        button.setBorder(BorderFactory.createLineBorder(theme.border()));
        button.setFocusPainted(false);
    }

    void applyPreferences() {
        curlInput.setFont(new Font(LensPreferences.curlFont(), Font.PLAIN, JBUI.scale(LensPreferences.curlSize())));
        applyJsonColors(collectionPreview);
        recolorJsonPanes(root);
    }

    private static void recolorJsonPanes(Component component) {
        if (component instanceof JTextPane pane) applyJsonColors(pane);
        if (component instanceof java.awt.Container container)
            for (Component child : container.getComponents()) recolorJsonPanes(child);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        liveUpdateTimer.stop();
    }

    private record RequestMatches(HttpRequestSample request, List<InteractionMatcher.Match> matches) {}
    private record ScanResult(List<RequestMatches> requests, List<JavaEndpointInspector.EndpointCandidate> candidates,
                              String error, int sourceIndex) {}
}
