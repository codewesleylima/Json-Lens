package dev.jsonlens.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.prefs.Preferences;

final class LensPreferences {
    private static final Preferences STORE = Preferences.userNodeForPackage(LensPreferences.class);

    private LensPreferences() {}

    static String language() { return STORE.get("language", "pt-BR"); }
    static String theme() { return STORE.get("theme", "Midnight"); }
    static String infoFont() { return font("infoFont", defaultFont()); }
    static String jsonFont() { return font("jsonFont", Font.MONOSPACED); }
    static String curlFont() { return font("curlFont", Font.MONOSPACED); }
    static int infoSize() { return size("infoSize", 12); }
    static int jsonSize() { return size("jsonSize", 15); }
    static int curlSize() { return size("curlSize", 13); }

    static String text(String portuguese, String english) {
        return language().equals("en") ? english : portuguese;
    }

    static void save(String language, String theme,
                     String infoFont, int infoSize,
                     String jsonFont, int jsonSize,
                     String curlFont, int curlSize) {
        STORE.put("language", language);
        STORE.put("theme", theme);
        STORE.put("infoFont", infoFont);
        STORE.putInt("infoSize", infoSize);
        STORE.put("jsonFont", jsonFont);
        STORE.putInt("jsonSize", jsonSize);
        STORE.put("curlFont", curlFont);
        STORE.putInt("curlSize", curlSize);
    }

    private static String font(String key, String fallback) {
        String value = STORE.get(key, fallback);
        return Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames()).contains(value) ? value : fallback;
    }

    private static int size(String key, int fallback) {
        return Math.max(9, Math.min(28, STORE.getInt(key, fallback)));
    }

    private static String defaultFont() {
        return java.awt.GraphicsEnvironment.isHeadless()
            ? Font.SANS_SERIF : new javax.swing.JLabel().getFont().getFamily();
    }
}
