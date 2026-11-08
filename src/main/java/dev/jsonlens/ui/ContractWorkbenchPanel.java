package dev.jsonlens.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.util.ui.JBUI;
import dev.jsonlens.analysis.InteractionMatcher;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.contract.ContractComparator;
import dev.jsonlens.contract.ContractParser;
import dev.jsonlens.model.EndpointSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Live, local contract workbench for the endpoint selected in the Java editor.
 * This is not a runtime HTTP test and never executes a request or uploads code.
 */
final class ContractWorkbenchPanel implements Disposable {
    private final JPanel root = new JPanel(new BorderLayout(0, 10));
    private final JTextArea heading = LensUi.wrapped("", 2);
    private final JTextArea selected = LensUi.wrapped("", 2);
    private final JTextArea status = LensUi.wrapped("", 2);
    private final JTextArea contractInput = new JTextArea();
    private final JTextArea findings = new JTextArea();
    private final JComboBox<String> direction = new JComboBox<>(new String[]{"Request", "Response"});
    private final JButton open = new JButton();
    private final JButton copy = new JButton();
    private final Timer debounce = new Timer(350, e -> compare());
    private final ContractParser parser = new ContractParser();
    private final InteractionMatcher matcher = new InteractionMatcher();
    private EndpointSnapshot snapshot;
    private String sourceName = "contract.json";
    private boolean disposed;

    ContractWorkbenchPanel() {
        root.setOpaque(true);
        root.setBackground(LensTheme.current().background());
        root.setBorder(JBUI.Borders.empty(9));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        selected.setFont(selected.getFont().deriveFont(12f));
        status.setFont(status.getFont().deriveFont(12f));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        JPanel caption = new JPanel(new GridLayout(0, 1, 0, 3));
        caption.setOpaque(false);
        caption.add(heading);
        caption.add(selected);
        top.add(caption, BorderLayout.CENTER);

        JPanel controls = new JPanel(new GridLayout(1, 3, JBUI.scale(5), 0));
        controls.setOpaque(false);
        controls.add(direction);
        controls.add(open);
        controls.add(copy);
        top.add(controls, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        contractInput.setLineWrap(true);
        contractInput.setWrapStyleWord(false);
        contractInput.setTabSize(2);
        contractInput.setRows(9);
        contractInput.setMargin(JBUI.insets(10));
        findings.setEditable(false);
        findings.setLineWrap(true);
        findings.setWrapStyleWord(true);
        findings.setMargin(JBUI.insets(10));
        findings.setRows(8);

        JScrollPane inputScroll = new JScrollPane(contractInput);
        JScrollPane resultScroll = new JScrollPane(findings);
        inputScroll.setBorder(BorderFactory.createLineBorder(LensTheme.current().border()));
        resultScroll.setBorder(BorderFactory.createLineBorder(LensTheme.current().border()));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputScroll, resultScroll);
        split.setResizeWeight(0.55);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setDividerSize(JBUI.scale(8));
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> {
            if (!disposed) split.setDividerLocation(0.55);
        });

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        debounce.setRepeats(false);
        contractInput.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
            private void changed() { sourceName = "contract.txt"; debounce.restart(); }
        });
        direction.addActionListener(e -> debounce.restart());
        open.addActionListener(e -> chooseContract());
        copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(findings.getText())));
        applyLanguage();
        applyPreferences();
    }

    JPanel component() { return root; }

    void setSnapshot(EndpointSnapshot next) {
        snapshot = next;
        selected.setText(next == null
            ? LensPreferences.text("Selecione um controller ou Feign no editor Java.",
                "Select a controller or Feign client in the Java editor.")
            : next.direction + " • " + next.verb + " " + next.path + " • " + next.title);
        debounce.restart();
    }

    void applyLanguage() {
        heading.setText(LensPreferences.text("Comparar contrato externo", "Compare external contract"));
        open.setText(LensPreferences.text("Abrir arquivo", "Open file"));
        open.setToolTipText(LensPreferences.text("Abrir contrato JSON, YAML ou OpenAPI",
            "Open a JSON, YAML or OpenAPI contract"));
        copy.setText(LensPreferences.text("Copiar", "Copy"));
        copy.setToolTipText(LensPreferences.text("Copiar resultado da análise", "Copy analysis findings"));
        if (snapshot == null) selected.setText(LensPreferences.text(
            "Selecione um controller ou Feign no editor Java.", "Select a controller or Feign client in the Java editor."));
        debounce.restart();
    }

    void applyPreferences() {
        LensTheme theme = LensTheme.current();
        root.setBackground(theme.background());
        heading.setForeground(theme.primaryText());
        selected.setForeground(theme.secondaryText());
        status.setForeground(theme.accent());
        contractInput.setBackground(theme.surface());
        contractInput.setForeground(theme.primaryText());
        contractInput.setCaretColor(theme.accent());
        findings.setBackground(theme.surface());
        findings.setForeground(theme.secondaryText());
        contractInput.setFont(new Font(LensPreferences.jsonFont(), Font.PLAIN, LensPreferences.jsonSize()));
        findings.setFont(new Font(LensPreferences.infoFont(), Font.PLAIN, LensPreferences.infoSize()));
        open.setBackground(theme.card());
        open.setForeground(theme.primaryText());
        open.setFont(open.getFont().deriveFont(10f));
        copy.setBackground(theme.card());
        copy.setForeground(theme.primaryText());
        copy.setFont(copy.getFont().deriveFont(10f));
        direction.setFont(direction.getFont().deriveFont(10f));
        open.setMargin(JBUI.insets(4, 2));
        copy.setMargin(JBUI.insets(4, 2));
        root.repaint();
    }

    private void chooseContract() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(LensPreferences.text("Selecionar contrato JSON, YAML ou OpenAPI",
            "Select JSON, YAML or OpenAPI contract"));
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        try {
            if (Files.size(file) > 1024 * 1024) {
                status.setText(LensPreferences.text("Arquivo acima do limite de 1 MB para edição na interface.",
                    "File exceeds the 1 MB editing limit."));
                return;
            }
            contractInput.setText(Files.readString(file));
            sourceName = file.getFileName().toString();
            compare();
        } catch (IOException e) {
            status.setText(LensPreferences.text("Erro ao abrir arquivo: ", "Could not open file: ") + e.getMessage());
        }
    }

    private void compare() {
        if (disposed) return;
        if (snapshot == null) {
            status.setText(LensPreferences.text("Aguardando endpoint Java.", "Waiting for Java endpoint."));
            findings.setText(LensPreferences.text("Abra um método Java reconhecido para comparar seu contrato.",
                "Open a recognized Java method to compare its contract."));
            return;
        }
        String raw = contractInput.getText().trim();
        if (raw.isEmpty()) {
            status.setText(LensPreferences.text("Cole um exemplo JSON, JSON Schema ou OpenAPI 3.x (JSON/YAML).",
                "Paste a JSON example, JSON Schema or OpenAPI 3.x (JSON/YAML)."));
            findings.setText(LensPreferences.text(
                "A comparação é local, estática e não executa nenhuma chamada.\n" +
                "Para Response use um contrato OpenAPI com a resposta da operação.",
                "Comparison is local and static. No request is sent.\n" +
                "Use an OpenAPI response schema to compare responses."));
            return;
        }
        if (raw.length() > 1_000_000) {
            status.setText(LensPreferences.text("Contrato muito grande (limite 1 MB).", "Contract exceeds 1 MB."));
            return;
        }
        boolean response = direction.getSelectedIndex() == 1;
        boolean openApi = raw.contains("\"openapi\"") || raw.contains("\"swagger\"") ||
            raw.stripLeading().startsWith("openapi:") || raw.stripLeading().startsWith("swagger:");
        if (response && !openApi) {
            status.setText(LensPreferences.text("Response exige OpenAPI 3.x para localizar o schema de saída.",
                "Response comparison requires OpenAPI 3.x."));
            findings.setText(LensPreferences.text("Selecione Request ou forneça um OpenAPI completo.",
                "Select Request or provide a complete OpenAPI document."));
            return;
        }
        try {
            String name = sourceName;
            if (raw.stripLeading().startsWith("openapi:") || raw.stripLeading().startsWith("swagger:"))
                name = "contract.yaml";
            else if (raw.stripLeading().startsWith("{") || raw.stripLeading().startsWith("["))
                name = "contract.json";
            ContractParser.Parsed parsed = parser.parseText(raw, name, snapshot.verb, snapshot.path, response);
            var candidate = new JavaEndpointInspector.EndpointCandidate(snapshot, null, 0);
            InteractionMatcher.Review review = response
                ? matcher.compareResponseContract(candidate, parsed)
                : matcher.compareContract(candidate, parsed);
            long errors = review.findings().stream().filter(f -> f.severity() == ContractComparator.Severity.ERROR).count();
            long warnings = review.findings().stream().filter(f -> f.severity() == ContractComparator.Severity.WARNING).count();
            status.setText(LensPreferences.text("Aderência estimada: ", "Estimated adherence: ") +
                review.adherencePercent() + "% • " + errors + LensPreferences.text(" erro(s)", " error(s)") +
                " • " + warnings + LensPreferences.text(" aviso(s)", " warning(s)"));
            StringBuilder report = new StringBuilder();
            report.append(response ? "RESPONSE" : "REQUEST").append(" • ").append(parsed.sourceType()).append("\n");
            report.append(LensPreferences.text(
                "Correspondência heurística; não comprova compatibilidade em runtime.\n\n",
                "Heuristic match; runtime compatibility is not proven.\n\n"));
            for (var finding : review.findings()) {
                report.append(finding.severity()).append(" ").append(finding.pointer()).append(" — ")
                    .append(finding.message()).append("\n");
            }
            for (var mapping : review.valueMappings()) {
                if (mapping.hasDifferentSource())
                    report.append("WARNING $.").append(mapping.targetField()).append(" — ")
                        .append(mapping.expression()).append(" [").append(mapping.sourceField()).append("]\n");
            }
            for (String warning : parsed.warnings()) report.append("INFO — ").append(warning).append("\n");
            findings.setText(report.toString());
            findings.setCaretPosition(0);
        } catch (Exception e) {
            status.setText(LensPreferences.text("Contrato inválido ou incompleto.", "Invalid or incomplete contract."));
            findings.setText(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @Override public void dispose() { disposed = true; debounce.stop(); }
}
