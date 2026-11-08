package dev.jsonlens.model;

import java.util.ArrayList;
import java.util.List;

public final class EndpointSnapshot {
    public String title;
    public String verb;
    public String path;
    public String direction;
    public SchemaNode request = SchemaNode.of("unknown");
    public SchemaNode response = SchemaNode.of("unknown");
    public final List<String> parameterNotes = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
}
