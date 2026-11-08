package dev.jsonlens.ui;

import dev.jsonlens.analysis.JavaEndpointInspector;
import dev.jsonlens.analysis.InteractionMatcher;
import dev.jsonlens.contract.HttpRequestSample;
import dev.jsonlens.model.EndpointSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LensWebSearchTest {
    @Test void reusesEndpointsOnlyUntilProjectRevisionChanges() {
        var cache = new LensWebSearch.EndpointCache();
        var calls = new AtomicInteger();
        var endpoint = new JavaEndpointInspector.EndpointCandidate(new EndpointSnapshot(), null, 0);
        var source = new ArrayList<>(List.of(endpoint));

        var first = cache.get(1, () -> { calls.incrementAndGet(); return source; });
        source.clear();
        assertEquals(List.of(endpoint), cache.get(1, () -> { calls.incrementAndGet(); return List.of(); }));
        assertEquals(1, calls.get());
        assertSame(first, cache.get(1, List::of));
        assertTrue(cache.get(2, () -> { calls.incrementAndGet(); return List.of(); }).isEmpty());
        assertEquals(2, calls.get());
    }

    @Test void failedInspectionDoesNotCacheAnIncompleteRevision() {
        var cache = new LensWebSearch.EndpointCache();
        assertThrows(IllegalStateException.class, () -> cache.get(1, () -> {
            throw new IllegalStateException("cancelled");
        }));
        var endpoint = new JavaEndpointInspector.EndpointCandidate(new EndpointSnapshot(), null, 0);
        assertEquals(List.of(endpoint), cache.get(1, () -> List.of(endpoint)));
    }

    @Test void refreshedResultsKeepTheSameCandidateKeyWithNewIds() {
        var snapshot = new EndpointSnapshot();
        snapshot.title = "Client.send()";
        snapshot.direction = "OUTBOUND (Feign)";
        snapshot.verb = "POST";
        snapshot.path = "/books";
        var candidate = new JavaEndpointInspector.EndpointCandidate(snapshot, null, 0);
        var request = new HttpRequestSample("Book", "POST", "https://example.test/books", "/books", null);
        var result = new LensWebSearch.Result(List.of(candidate), List.of(request),
            List.of(new InteractionMatcher().rank(request, List.of(candidate))));

        var first = LensWebSearch.toJson("curl", result, ignored -> 1)
            .getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonArray("outbound").get(0).getAsJsonObject();
        var updated = LensWebSearch.toJson("curl", result, ignored -> 2)
            .getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonArray("outbound").get(0).getAsJsonObject();
        assertEquals(first.get("key"), updated.get("key"));
        assertNotEquals(first.get("id"), updated.get("id"));
    }
}
