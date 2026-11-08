package dev.jsonlens.ui;

import java.awt.Color;

record LensTheme(
    Color background,
    Color surface,
    Color card,
    Color border,
    Color accent,
    Color primaryText,
    Color secondaryText,
    Color mutedText,
    Color dimText,
    Color jsonKey,
    Color jsonString,
    Color jsonNumber,
    Color jsonKeyword,
    Color jsonPunctuation
) {
    static LensTheme current() { return named(LensPreferences.theme()); }

    static LensTheme named(String name) {
        return switch (name) {
            case "Base16" -> new LensTheme(
                color(0x181818), color(0x202020), color(0x282828), color(0x383838),
                color(0x7CAFC2), color(0xD8D8D8), color(0xB8B8B8), color(0xA0A0A0),
                color(0x707070), color(0x8FA1B3), color(0xA3BE8C), color(0xD08770),
                color(0xB48EAD), color(0x96B5B4));
            case "Ocean" -> new LensTheme(
                color(0x081521), color(0x0D2030), color(0x122B3F), color(0x23465D),
                color(0x4CC9F0), color(0xE2F1F8), color(0xB8CDD9), color(0x8FA9B9),
                color(0x688394), color(0x82AAFF), color(0xA6E3A1), color(0xFAB387),
                color(0xCBA6F7), color(0x89DDFF));
            default -> new LensTheme(
                color(0x030508), color(0x070B12), color(0x0B111A), color(0x1C2A3A),
                color(0x35D6E8), color(0xE7F0FA), color(0xB6C7D8), color(0x8FA4BA),
                color(0x71859B), color(0x82AAFF), color(0xC3E88D), color(0xF78C6C),
                color(0xC792EA), color(0x89DDFF));
        };
    }

    static Color remap(Color value, LensTheme from, LensTheme to) {
        if (value == null) return null;
        if (value.equals(from.background) || rgb(value, 0x030508)) return to.background;
        if (value.equals(from.surface) || rgb(value, 0x070B12) || rgb(value, 0x102033)) return to.surface;
        if (value.equals(from.card) || rgb(value, 0x0B111A) || rgb(value, 0x0A1420)) return to.card;
        if (value.equals(from.border) || rgb(value, 0x1C2A3A) || rgb(value, 0x24415E)) return to.border;
        if (value.equals(from.accent) || rgb(value, 0x35D6E8)) return to.accent;
        if (value.equals(from.primaryText) || rgb(value, 0xE7F0FA) || rgb(value, 0xD6E2F0)
            || rgb(value, 0xFFFFFF)) return to.primaryText;
        if (value.equals(from.secondaryText) || rgb(value, 0xB6C7D8) || rgb(value, 0xC7D2E3)
            || rgb(value, 0xCDD3DE)) return to.secondaryText;
        if (value.equals(from.mutedText) || rgb(value, 0x8FA4BA)) return to.mutedText;
        if (value.equals(from.dimText) || rgb(value, 0x71859B)) return to.dimText;
        if (value.equals(from.jsonKey) || rgb(value, 0x82AAFF)) return to.jsonKey;
        if (value.equals(from.jsonString) || rgb(value, 0xC3E88D)) return to.jsonString;
        if (value.equals(from.jsonNumber) || rgb(value, 0xF78C6C)) return to.jsonNumber;
        if (value.equals(from.jsonKeyword) || rgb(value, 0xC792EA)) return to.jsonKeyword;
        if (value.equals(from.jsonPunctuation) || rgb(value, 0x89DDFF)) return to.jsonPunctuation;
        return value;
    }

    private static Color color(int rgb) { return new Color(rgb); }
    private static boolean rgb(Color color, int rgb) { return (color.getRGB() & 0xFFFFFF) == rgb; }
}
