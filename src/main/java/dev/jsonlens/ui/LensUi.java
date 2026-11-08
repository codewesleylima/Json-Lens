package dev.jsonlens.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/** Reusable responsive primitives for narrow IntelliJ tool windows. */
final class LensUi {
    private LensUi() {}

    static JTextArea wrapped(String value, int rows) {
        JTextArea area = new JTextArea(value, rows, 12);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(new EmptyBorder(2, 2, 2, 2));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    static JPanel adaptiveRow(int breakpoint, Component... children) {
        JPanel row = new JPanel(new GridLayout(0, 1, 0, 5));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component child : children) row.add(child);
        row.addComponentListener(new ComponentAdapter() {
            private boolean compact = true;
            @Override public void componentResized(ComponentEvent event) {
                boolean next = row.getWidth() < breakpoint;
                if (compact == next) return;
                compact = next;
                row.setLayout(next ? new GridLayout(0, 1, 0, 5) : new FlowLayout(FlowLayout.LEFT, 6, 5));
                row.revalidate();
            }
        });
        return row;
    }

    /** Prevents results from forcing a horizontal scrollbar or overflowing the splitter. */
    static JPanel scrollingCards() {
        return new WidthTrackingPanel();
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(60, orientation == SwingConstants.VERTICAL ? visibleRect.height - 24 : visibleRect.width - 24);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
