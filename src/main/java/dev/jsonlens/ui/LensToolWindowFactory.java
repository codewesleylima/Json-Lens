package dev.jsonlens.ui;

import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class LensToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        if (JBCefApp.isSupported()) {
            LensWebPanel panel = new LensWebPanel(project);
            Content content = ContentFactory.getInstance().createContent(panel.component(), "WContract", false);
            content.setDisposer(panel);
            toolWindow.getContentManager().addContent(content);
            panel.start();
        } else {
            // Browser-less or custom JBR: preserve the existing fully local Swing workflow.
            LensPanel fallback = new LensPanel(project);
            Content content = ContentFactory.getInstance().createContent(fallback.component(), "WContract (Swing)", false);
            content.setDisposer(fallback);
            toolWindow.getContentManager().addContent(content);
            fallback.start();
        }
    }
}
