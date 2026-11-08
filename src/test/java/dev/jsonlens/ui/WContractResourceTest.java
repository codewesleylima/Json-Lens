package dev.jsonlens.ui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WContractResourceTest {
    private static String read(String path) throws Exception {
        try(InputStream input = WContractResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path + " must be packaged in the plugin");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void bundlesRealWContractViewsWithoutExternalScripts() throws Exception {
        String html=read("/web/wcontract.html");
        for(String id:new String[]{"catalog","contract","integrations","search","risks","reports",
            "catalogList","jsonBody","curlPreview","integrationList","evaluateBtn","historyList"})
            assertTrue(html.contains("id=\""+id+"\""),id);
        assertTrue(html.contains("WContract"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("radial-gradient"));
    }

    @Test void browserBridgeIsOfflineAndReferencesAllActions() throws Exception {
        String js=read("/web/wcontract-native.js");
        for(String action:new String[]{"\"ready\"","\"select\"","\"copy\"","\"evaluate\"",
            "\"cancel-evaluation\"","\"bind-contract\"","\"integrations\"","\"open-location\""})
            assertTrue(js.contains(action),action);
        assertFalse(js.contains("XMLHttpRequest"));
        assertFalse(js.contains("fetch("));
        assertFalse(js.contains("window.open"));
    }
}
