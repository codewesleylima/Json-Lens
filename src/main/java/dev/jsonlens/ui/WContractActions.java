package dev.jsonlens.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.contract.CurlGenerator;
import java.awt.datatransfer.StringSelection;
import javax.swing.SwingUtilities;

/** Native EditorPopupMenu integration; never performs a project-wide PSI scan in update(). */
public final class WContractActions {
    private WContractActions() {}

    private static boolean applicable(AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        var file = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(file instanceof PsiJavaFile javaFile)
                || DumbService.isDumb(project)) return false;
        var leaf = file.findElementAt(Math.min(editor.getCaretModel().getOffset(), Math.max(0, file.getTextLength()-1)));
        PsiClass clazz = PsiTreeUtil.getParentOfType(leaf, PsiClass.class);
        if (clazz == null) {
            for (var candidate : javaFile.getClasses()) if (supported(candidate)) { clazz = candidate; break; }
        }
        if (clazz == null || !supported(clazz)) return false;
        for (PsiMethod method : clazz.getMethods()) if (mapped(method)) return true;
        return false;
    }

    private static boolean supported(PsiClass clazz) {
        return has(clazz, "RestController") || has(clazz, "Controller") || has(clazz, "FeignClient");
    }
    private static boolean mapped(PsiMethod method) {
        for (String a : new String[]{"GetMapping","PostMapping","PutMapping","PatchMapping","DeleteMapping","RequestMapping"})
            if (has(method,a)) return true;
        return false;
    }
    private static boolean has(com.intellij.psi.PsiModifierListOwner owner, String shortName) {
        var mods = owner.getModifierList();
        if (mods == null) return false;
        for (PsiAnnotation annotation : mods.getAnnotations()) {
            String q = annotation.getQualifiedName();
            if (q != null && (q.equals(shortName) || q.endsWith("." + shortName))) return true;
        }
        return false;
    }

    public static final class Menu extends DefaultActionGroup {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(AnActionEvent e) {
            boolean enabled = applicable(e);
            e.getPresentation().setEnabledAndVisible(enabled);
        }
    }

    private abstract static class ContextAction extends AnAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(AnActionEvent e) { e.getPresentation().setEnabledAndVisible(applicable(e)); }
        protected void open(AnActionEvent e, String view) {
            Project project = e.getProject();
            if (project == null) return;
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
            VirtualFile file = files.length > 0 ? files[0] : null;
            int offset = editor == null ? 0 : editor.getCaretModel().getOffset();
            ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("WContract");
            if (window == null) return;
            window.activate(() -> {
                LensWebPanel panel = project.getUserData(LensWebPanel.ACTIVE);
                if (panel != null) panel.focusFromEditor(file, offset, view);
                // Swing fallback remains bound to the editor caret.
            }, true);
        }
    }

    public static final class View extends ContextAction {
        @Override public void actionPerformed(AnActionEvent e) { open(e, "contract"); }
    }
    public static final class Integrations extends ContextAction {
        @Override public void actionPerformed(AnActionEvent e) { open(e, "integrations"); }
    }
    public static final class Assess extends ContextAction {
        @Override public void actionPerformed(AnActionEvent e) { open(e, "assess-isolated"); }
    }
    public static final class AssessProject extends ContextAction {
        @Override public void actionPerformed(AnActionEvent e) { open(e, "assess-project"); }
    }
    public static final class CopyCurl extends ContextAction {
        @Override public void actionPerformed(AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            VirtualFile[] files = project == null ? new VirtualFile[0]
                : FileEditorManager.getInstance(project).getSelectedFiles();
            if (project == null || editor == null || files.length == 0) return;
            VirtualFile file = files[0];
            int offset = editor.getCaretModel().getOffset();
            ReadAction.nonBlocking(() -> new JavaEndpointInspector(project).inspect(file, offset, null).snapshot())
                .inSmartMode(project).expireWith(project)
                .finishOnUiThread(ModalityState.any(), snapshot -> {
                    if (snapshot != null)
                        CopyPasteManager.getInstance().setContents(
                            new StringSelection(CurlGenerator.generate(snapshot)));
                }).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
        }
    }
}
