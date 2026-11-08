package dev.jsonlens.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Guards the packaged offline UI contract without requiring the Chromium runtime in CI.
 * End-to-end JS-to-Java behavior is validated separately in IntelliJ using runIde.
 */
class LensWebResourcesTest {
    private static String load(String path) throws Exception {
        try (var resource = LensWebResourcesTest.class.getResourceAsStream(path)) {
            assertNotNull(resource, "Missing packaged UI resource: " + path);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void bundlesApprovedIsolatedWindowWithAllProductionTabs() throws Exception {
        String html = load("/web/json-lens.html");
        for (String id : new String[] {
            "view-preview", "view-search", "view-compare", "view-details",
            "jsonPreview", "methodSelect", "pinBtn", "curlText", "collectionFile",
            "resultsHolder", "contractInput", "contractFindings"
        }) assertTrue(html.contains("id=\"" + id + "\""), "HTML missing " + id);
        assertTrue(html.contains("var(--orange)"));
        assertFalse(html.contains("<script src="), "The browser must not fetch remote JS");
    }

    @Test void browserAdapterIncludesNativeActionsWithoutRemoteCalls() throws Exception {
        String js = load("/web/jsonlens-native.js");
        for (String action : new String[]{
            "'ready'", "'select'", "'pin'", "'scan'", "'compare'", "'open'", "'copy'",
            "'preferences'", "'preview'", "'comparison'"
        }) assertTrue(js.contains(action), "Bridge action missing: " + action);
        assertTrue(js.contains("window.__jsonLensNative"));
        assertFalse(js.contains("fetch("), "No HTTP or external request in browser bridge");
        assertFalse(js.contains("XMLHttpRequest"), "No XMLHttpRequest in browser bridge");
    }
}
