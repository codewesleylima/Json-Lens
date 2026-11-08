package dev.jsonlens.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

final class LensAppearance {
    private LensAppearance() {}

    static void apply(Component component, LensTheme from, LensTheme to) {
        if (component instanceof JComponent swing) {
            if (swing.getBackground() != null)
                swing.setBackground(LensTheme.remap(swing.getBackground(), from, to));
            if (swing.getForeground() != null)
                swing.setForeground(LensTheme.remap(swing.getForeground(), from, to));
            swing.setBorder(remapBorder(swing.getBorder(), from, to));

            Font current = swing.getFont();
            if (current != null) {
                String role = String.valueOf(swing.getClientProperty("jsonlens.fontRole"));
                String family = role.equals("curl") ? LensPreferences.curlFont()
                    : role.equals("json") || swing instanceof javax.swing.JTextPane
                        ? LensPreferences.jsonFont() : LensPreferences.infoFont();
                int size = role.equals("heading") ? current.getSize()
                    : role.equals("curl") ? LensPreferences.curlSize()
                    : role.equals("json") || swing instanceof javax.swing.JTextPane
                        ? LensPreferences.jsonSize() : LensPreferences.infoSize();
                swing.setFont(new Font(family, current.getStyle(), size));
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) apply(child, from, to);
        }
    }

    private static Border remapBorder(Border border, LensTheme from, LensTheme to) {
        if (border instanceof LineBorder line)
            return BorderFactory.createLineBorder(
                LensTheme.remap(line.getLineColor(), from, to), line.getThickness(), line.getRoundedCorners());
        if (border instanceof MatteBorder matte) {
            var insets = matte.getBorderInsets();
            return new MatteBorder(insets.top, insets.left, insets.bottom, insets.right,
                LensTheme.remap(matte.getMatteColor(), from, to));
        }
        if (border instanceof CompoundBorder compound)
            return new CompoundBorder(remapBorder(compound.getOutsideBorder(), from, to),
                remapBorder(compound.getInsideBorder(), from, to));
        return border;
    }
}
