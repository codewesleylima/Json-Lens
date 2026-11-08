package dev.jsonlens.analysis;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.jsonlens.model.EndpointSnapshot;

public final class JavaEndpointInspectorTest extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("java/lang/String.java", "package java.lang; public final class String {}");
        myFixture.addFileToProject("java/util/Collection.java", "package java.util; public interface Collection<E> {}");
        myFixture.addFileToProject("java/util/List.java", "package java.util; public interface List<E> extends Collection<E> {}");
        myFixture.addFileToProject("java/util/Map.java", "package java.util; public interface Map<K, V> {}");
        addAnnotation("org/springframework/web/bind/annotation/RestController.java",
            "package org.springframework.web.bind.annotation; public @interface RestController {}");
        addAnnotation("org/springframework/web/bind/annotation/PostMapping.java",
            "package org.springframework.web.bind.annotation; public @interface PostMapping { String[] value() default {}; String[] path() default {}; }");
        addAnnotation("org/springframework/web/bind/annotation/GetMapping.java",
            "package org.springframework.web.bind.annotation; public @interface GetMapping { String[] value() default {}; String[] path() default {}; }");
        addAnnotation("org/springframework/web/bind/annotation/RequestBody.java",
            "package org.springframework.web.bind.annotation; public @interface RequestBody {}");
        addAnnotation("org/springframework/web/bind/annotation/PathVariable.java",
            "package org.springframework.web.bind.annotation; public @interface PathVariable { String value() default \"\"; }");
        addAnnotation("org/springframework/web/bind/annotation/RequestParam.java",
            "package org.springframework.web.bind.annotation; public @interface RequestParam { String value() default \"\"; }");
        addAnnotation("org/springframework/cloud/openfeign/FeignClient.java",
            "package org.springframework.cloud.openfeign; public @interface FeignClient { String name() default \"\"; String path() default \"\"; }");
        addAnnotation("org/springframework/http/ResponseEntity.java",
            "package org.springframework.http; public class ResponseEntity<T> {}");
        addAnnotation("jakarta/validation/constraints/NotBlank.java",
            "package jakarta.validation.constraints; public @interface NotBlank {}");
        addAnnotation("com/fasterxml/jackson/annotation/JsonProperty.java",
            "package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; boolean required() default false; }");
        addAnnotation("com/fasterxml/jackson/annotation/JsonIgnore.java",
            "package com.fasterxml.jackson.annotation; public @interface JsonIgnore {}");
    }

    public void testControllerExpandsRecordRequestAndResponseEntityWithJacksonNames() {
        PsiFile controller = myFixture.addFileToProject("sample/BookController.java", """
            package sample;
            import com.fasterxml.jackson.annotation.JsonIgnore;
            import com.fasterxml.jackson.annotation.JsonProperty;
            import jakarta.validation.constraints.NotBlank;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class BookController {
                @PostMapping(path = "/books")
                ResponseEntity<BookResponse> create(@RequestBody CreateBook request) { return null; }
            }
            record CreateBook(@NotBlank @JsonProperty("book_title") String title, String note) {}
            class BookResponse {
                @JsonProperty("display_name") public String name;
                @JsonIgnore public String secret;
            }
            """);

        JavaEndpointInspector.Inspection inspection = inspect(controller, "create(");
        EndpointSnapshot snapshot = inspection.snapshot();

        assertNotNull(snapshot);
        assertEquals("POST", snapshot.verb);
        assertEquals("/books", snapshot.path);
        assertTrue(snapshot.request.required.contains("book_title"));
        assertEquals(snapshot.warnings.toString(), "string", snapshot.request.properties.get("book_title").kind);
        assertTrue(snapshot.request.properties.containsKey("note"));
        assertTrue(snapshot.response.properties.containsKey("display_name"));
        assertFalse(snapshot.response.properties.containsKey("secret"));
    }

    public void testNestedGenericCollectionsMapsEnumsAndCircularDtos() {
        PsiFile controller = myFixture.addFileToProject("sample/TreeController.java", """
            package sample;
            import java.util.List;
            import java.util.Map;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class TreeController {
                @GetMapping(path = "/trees")
                ResponseEntity<Box<TreeNode>> trees() { return null; }
            }
            class Box<T> {
                public List<T> values;
                public Map<String, String> labels;
            }
            class TreeNode {
                public TreeNode child;
                public State state;
            }
            enum State { NEW, READY }
            """);

        EndpointSnapshot snapshot = inspect(controller, "trees(").snapshot();

        assertNotNull(snapshot);
        assertEquals(snapshot.warnings.toString(), "array", snapshot.response.properties.get("values").kind);
        var item = snapshot.response.properties.get("values").items;
        assertEquals("object", item.kind);
        assertEquals("unknown", item.properties.get("child").kind);
        assertEquals("string", item.properties.get("state").kind);
        assertEquals(2, item.properties.get("state").enumValues.size());
        assertEquals("string", snapshot.response.properties.get("labels").additionalPropertiesSchema.kind);
        assertTrue(snapshot.warnings.stream().anyMatch(w -> w.contains("Referência recursiva")));
    }

    public void testFeignRequestAndRouteParametersAreRecognized() {
        PsiFile client = myFixture.addFileToProject("sample/RemoteClient.java", """
            package sample;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestParam;

            @FeignClient(name = "remote", path = "/v1")
            interface RemoteClient {
                @PostMapping(path = "/items/{id}")
                ResponseEntity<Item> send(@RequestBody Item request, @PathVariable("id") String id, @RequestParam("q") String query);
            }
            class Item { public String name; }
            """);

        EndpointSnapshot snapshot = inspect(client, "send(").snapshot();

        assertNotNull(snapshot);
        assertEquals("OUTBOUND (Feign)", snapshot.direction);
        assertEquals("/v1/items/{id}", snapshot.path);
        assertEquals("object", snapshot.request.kind);
        assertTrue(snapshot.parameterNotes.stream().anyMatch(note -> note.startsWith("path:")));
        assertTrue(snapshot.parameterNotes.stream().anyMatch(note -> note.startsWith("query:")));
    }

    public void testProjectScanFindsControllerAndFeignEndpoints() {
        myFixture.addFileToProject("sample/SearchController.java", """
            package sample;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController
            class SearchController {
                @PostMapping(path = "/search")
                Object search() { return null; }
            }
            """);
        myFixture.addFileToProject("sample/SearchClient.java", """
            package sample;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.PostMapping;
            @FeignClient(name = "search")
            interface SearchClient {
                @PostMapping(path = "/search")
                Object search();
            }
            """);

        var endpoints = ReadAction.compute(() -> new JavaEndpointInspector(getProject()).inspectAll());

        assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.snapshot().direction.startsWith("INBOUND")));
        assertTrue(endpoints.stream().anyMatch(endpoint -> endpoint.snapshot().direction.startsWith("OUTBOUND")));
    }

    public void testProjectScanDetectsSetterValueMappedFromDifferentSourceField() {
        myFixture.addFileToProject("sample/MappingClient.java", """
            package sample;
            import org.springframework.cloud.openfeign.FeignClient;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;

            @FeignClient(name = "mapping")
            interface MappingClient {
                @PostMapping(path = "/customers")
                void send(@RequestBody CustomerRequest request);
            }
            class CustomerRequest {
                public void setNomeCompleto(String value) {}
            }
            class CustomerSource {
                String getIdade() { return "42"; }
            }
            class CustomerService {
                private MappingClient client;
                void send(CustomerSource source) {
                    CustomerRequest request = new CustomerRequest();
                    request.setNomeCompleto(source.getIdade());
                    client.send(request);
                }
            }
            """);

        var endpoints = ReadAction.compute(() -> new JavaEndpointInspector(getProject()).inspectAll());
        var client = endpoints.stream()
            .filter(endpoint -> endpoint.snapshot().title.equals("MappingClient.send()"))
            .findFirst()
            .orElseThrow();

        assertTrue(client.valueMappings().stream().anyMatch(mapping ->
            mapping.targetField().equals("nomeCompleto")
                && mapping.sourceField().equals("idade")
                && mapping.hasDifferentSource()));
    }

    private JavaEndpointInspector.Inspection inspect(PsiFile file, String marker) {
        int offset = file.getText().indexOf(marker);
        assertTrue(offset >= 0);
        return ReadAction.compute(() ->
            new JavaEndpointInspector(getProject()).inspect(file.getVirtualFile(), offset, null));
    }

    private void addAnnotation(String path, String source) {
        myFixture.addFileToProject(path, source);
    }
}
