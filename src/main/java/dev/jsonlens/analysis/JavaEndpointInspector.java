package dev.jsonlens.analysis;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.search.searches.ReferencesSearch;
import dev.jsonlens.model.EndpointSnapshot;
import dev.jsonlens.model.SchemaNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-action only. Uses Java PSI rather than evaluating code or running the application. */
public final class JavaEndpointInspector {
    public record Inspection(List<Option> options, String selectedKey, EndpointSnapshot snapshot, String note) {}
    public record Option(String key, String label) { @Override public String toString() { return label; } }
    public record ValueMapping(String targetField, String sourceField, String expression, String sourceFile, int sourceOffset) {
        public ValueMapping(String targetField, String sourceField, String expression) {
            this(targetField, sourceField, expression, "", -1);
        }
        public boolean hasDifferentSource() {
            return sourceField != null && !normalizeName(targetField).equals(normalizeName(sourceField));
        }
    }
    public record EndpointCandidate(EndpointSnapshot snapshot, VirtualFile file, int offset, List<ValueMapping> valueMappings) {
        public EndpointCandidate(EndpointSnapshot snapshot, VirtualFile file, int offset) {
            this(snapshot, file, offset, List.of());
        }
    }
    private final Project project;
    public JavaEndpointInspector(Project project) { this.project = project; }

    public List<EndpointCandidate> inspectAll() {
        List<EndpointCandidate> endpoints = new ArrayList<>();
        for (VirtualFile file : FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (!(psiFile instanceof PsiJavaFile javaFile)) continue;
            for (PsiClass clazz : javaFile.getClasses()) collectEndpoints(clazz, file, endpoints);
        }
        return List.copyOf(endpoints);
    }

    private void collectEndpoints(PsiClass clazz, VirtualFile file, List<EndpointCandidate> endpoints) {
        ProgressManager.checkCanceled();
        if (isSupportedClass(clazz)) for (PsiMethod method : clazz.getMethods()) {
            if (!isMapped(method)) continue;
            endpoints.add(new EndpointCandidate(inspectMethod(method), file, method.getTextOffset(), inspectValueMappings(method)));
        }
        for (PsiClass innerClass : clazz.getInnerClasses()) collectEndpoints(innerClass, file, endpoints);
    }

    private List<ValueMapping> inspectValueMappings(PsiMethod endpointMethod) {
        PsiClass endpointClass = endpointMethod.getContainingClass();
        if (endpointClass == null || !isFeign(endpointClass)) return List.of();
        int bodyParameter = -1;
        PsiParameter[] parameters = endpointMethod.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (hasAnnotation(parameters[i], "RequestBody")) {
                bodyParameter = i;
                break;
            }
        }
        if (bodyParameter < 0) return List.of();

        List<ValueMapping> mappings = new ArrayList<>();
        boolean directParameterPassThrough = false;
        for (PsiReference reference : ReferencesSearch.search(endpointMethod, GlobalSearchScope.projectScope(project)).findAll()) {
            ProgressManager.checkCanceled();
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(reference.getElement(), PsiMethodCallExpression.class);
            if (call == null || call.resolveMethod() != endpointMethod) continue;
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if (bodyParameter >= arguments.length) continue;
            PsiElement bodyTarget = resolveReference(arguments[bodyParameter]);
            PsiMethod caller = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
            if (!(bodyTarget instanceof PsiVariable) || caller == null) continue;
            if (bodyTarget instanceof PsiParameter sourceParameter
                && sourceParameter.getType().getCanonicalText().equals(parameters[bodyParameter].getType().getCanonicalText()))
                directParameterPassThrough = true;
            collectSetterMappings(caller, call, bodyTarget, mappings);
            collectFieldMappings(caller, call, bodyTarget, mappings);
        }
        if (mappings.isEmpty() && directParameterPassThrough)
            return List.of(new ValueMapping("$", "$", "Objeto de entrada transmitido diretamente sem transformação detectada."));
        if (mappings.isEmpty()) {
            return List.of(new ValueMapping("$", null,
                "A origem dos valores não foi inferida por atribuições ou setters diretos antes da chamada Feign."));
        }
        return List.copyOf(mappings);
    }

    private static void collectSetterMappings(PsiMethod caller, PsiMethodCallExpression sendCall, PsiElement bodyTarget,
                                              List<ValueMapping> mappings) {
        List<PsiMethodCallExpression> calls = new ArrayList<>(PsiTreeUtil.findChildrenOfType(caller, PsiMethodCallExpression.class));
        calls.sort((left, right) -> Integer.compare(left.getTextOffset(), right.getTextOffset()));
        for (PsiMethodCallExpression call : calls) {
            if (call.getTextOffset() >= sendCall.getTextOffset()) break;
            if (resolveReference(call.getMethodExpression().getQualifierExpression()) != bodyTarget) continue;
            String methodName = call.getMethodExpression().getReferenceName();
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if (methodName == null || !methodName.startsWith("set") || methodName.length() <= 3 || arguments.length != 1) continue;
            String target = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            mappings.add(new ValueMapping(target, sourceProperty(arguments[0]), arguments[0].getText(),
                sourcePath(call.getContainingFile()), call.getTextOffset()));
        }
    }

    private static void collectFieldMappings(PsiMethod caller, PsiMethodCallExpression sendCall, PsiElement bodyTarget,
                                             List<ValueMapping> mappings) {
        for (PsiAssignmentExpression assignment : PsiTreeUtil.findChildrenOfType(caller, PsiAssignmentExpression.class)) {
            if (assignment.getTextOffset() >= sendCall.getTextOffset()) continue;
            if (!(assignment.getLExpression() instanceof PsiReferenceExpression target)
                || !(target.getQualifierExpression() instanceof PsiReferenceExpression qualifier)
                || qualifier.resolve() != bodyTarget) continue;
            PsiExpression value = assignment.getRExpression();
            mappings.add(new ValueMapping(target.getReferenceName(), sourceProperty(value), value == null ? "" : value.getText(),
                sourcePath(assignment.getContainingFile()), assignment.getTextOffset()));
        }
    }

    private static String sourcePath(PsiFile file) {
        return file != null && file.getVirtualFile() != null ? file.getVirtualFile().getPath() : "";
    }

    private static PsiElement resolveReference(PsiExpression expression) {
        while (expression instanceof com.intellij.psi.PsiParenthesizedExpression parenthesized)
            expression = parenthesized.getExpression();
        return expression instanceof PsiReferenceExpression reference ? reference.resolve() : null;
    }

    private static String sourceProperty(PsiExpression expression) {
        if (expression == null) return null;
        while (expression instanceof com.intellij.psi.PsiParenthesizedExpression parenthesized)
            expression = parenthesized.getExpression();
        if (expression instanceof PsiMethodCallExpression call) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName != null && (methodName.startsWith("get") && methodName.length() > 3
                || methodName.startsWith("is") && methodName.length() > 2)) {
                int prefix = methodName.startsWith("get") ? 3 : 2;
                return Character.toLowerCase(methodName.charAt(prefix)) + methodName.substring(prefix + 1);
            }
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if (arguments.length == 1) return sourceProperty(arguments[0]);
            return null;
        }
        if (expression instanceof PsiReferenceExpression reference) return reference.getReferenceName();
        return null;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public Inspection inspect(VirtualFile file, int offset, String forcedMethod) {
        if (file == null) return new Inspection(List.of(), null, null, "Abra um arquivo Java com um controller ou cliente Feign.");
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile javaFile))
            return new Inspection(List.of(), null, null, "Selecione um controller ou cliente Feign escrito em Java.");
        PsiElement leaf = psiFile.findElementAt(Math.max(0, Math.min(offset, Math.max(0, psiFile.getTextLength() - 1))));
        PsiMethodCallExpression call = leaf == null ? null : PsiTreeUtil.getParentOfType(leaf, PsiMethodCallExpression.class);
        if (call != null) {
            PsiMethod target = call.resolveMethod();
            if (target != null && target.getContainingClass() != null && isFeign(target.getContainingClass()) && isMapped(target)) {
                EndpointSnapshot snap = inspectMethod(target);
                return new Inspection(List.of(new Option(methodKey(target), methodLabel(target))), methodKey(target), snap,
                    "Cliente Feign identificado pela chamada selecionada; o objeto transformado pelo serviço não foi executado.");
            }
        }
        PsiClass currentClass = leaf == null ? null : PsiTreeUtil.getParentOfType(leaf, PsiClass.class);
        if (currentClass == null) {
            PsiClass[] classes = javaFile.getClasses();
            for (PsiClass clazz : classes) if (isSupportedClass(clazz)) { currentClass = clazz; break; }
        }
        if (currentClass == null || !isSupportedClass(currentClass))
            return new Inspection(List.of(), null, null, "O arquivo não contém @RestController/@Controller ou @FeignClient reconhecido.");
        List<PsiMethod> endpoints = new ArrayList<>();
        List<Option> options = new ArrayList<>();
        for (PsiMethod method : currentClass.getMethods()) if (isMapped(method)) {
            endpoints.add(method);
            options.add(new Option(methodKey(method), methodLabel(method)));
        }
        if (endpoints.isEmpty()) return new Inspection(options, null, null, "A classe não possui métodos @GetMapping, @PostMapping, @PutMapping, @PatchMapping, @DeleteMapping ou @RequestMapping.");
        PsiMethod selection = leaf == null ? null : PsiTreeUtil.getParentOfType(leaf, PsiMethod.class);
        if (forcedMethod != null) for (PsiMethod method : endpoints) if (methodKey(method).equals(forcedMethod)) { selection = method; break; }
        if (selection == null || !endpoints.contains(selection)) selection = endpoints.get(0);
        return new Inspection(options, methodKey(selection), inspectMethod(selection), "");
    }

    private EndpointSnapshot inspectMethod(PsiMethod method) {
        EndpointSnapshot snap = new EndpointSnapshot();
        PsiClass clazz = method.getContainingClass();
        snap.title = (clazz == null ? "?" : clazz.getName()) + "." + method.getName() + "()";
        snap.direction = clazz != null && isFeign(clazz) ? "OUTBOUND (Feign)" : "INBOUND (Controller)";
        PsiAnnotation endpointAnnotation = mapping(method);
        snap.verb = inferVerb(endpointAnnotation);
        String prefix = clazz == null ? "" : pathFromMapping(mapping(clazz));
        if (clazz != null && isFeign(clazz)) {
            PsiAnnotation feign = annotation(clazz, "FeignClient");
            prefix = joinPath(prefix, literalString(feign, "path"));
        }
        snap.path = joinPath(prefix, pathFromMapping(endpointAnnotation));
        snap.warnings.add("Análise estática: filtros, interceptadores, serializadores personalizados e lógica dinâmica podem alterar o payload real.");
        boolean outbound = clazz != null && isFeign(clazz);
        boolean foundBody = false;
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (hasAnnotation(parameter, "RequestBody")) {
                if (!foundBody) {
                    foundBody = true;
                    snap.request = resolveType(parameter.getType(), PsiSubstitutor.EMPTY, snap.warnings, new HashSet<>(), 0, !outbound);
                } else snap.warnings.add("Múltiplos @RequestBody encontrados; somente o primeiro será analisado.");
            } else if (hasAnnotation(parameter, "RequestParam") || hasAnnotation(parameter, "PathVariable") || hasAnnotation(parameter, "RequestHeader")) {
                String kind = hasAnnotation(parameter, "RequestParam") ? "query" : hasAnnotation(parameter, "PathVariable") ? "path" : "header";
                snap.parameterNotes.add(kind + ": " + parameter.getName() + " (" + parameter.getType().getPresentableText() + ")");
            }
        }
        if (!foundBody) {
            snap.request = SchemaNode.of("null");
            if ("POST".equals(snap.verb) || "PUT".equals(snap.verb) || "PATCH".equals(snap.verb))
                snap.warnings.add("Nenhum parâmetro @RequestBody: não foi inferido um corpo JSON.");
        }
        PsiType returnType = method.getReturnType();
        snap.response = returnType == null ? SchemaNode.of("null") : resolveType(returnType, PsiSubstitutor.EMPTY, snap.warnings, new HashSet<>(), 0, outbound);
        return snap;
    }

    private SchemaNode resolveType(PsiType declared, PsiSubstitutor inheritedSubstitutor, List<String> warnings, Set<String> visit, int depth, boolean request) {
        if (declared == null) return SchemaNode.of("null");
        if (depth >= 12) { warnings.add("Profundidade máxima de DTO atingida: " + declared.getPresentableText()); return SchemaNode.of("unknown"); }
        PsiType type = inheritedSubstitutor.substitute(declared);
        if (type == null) type = declared;
        if (type instanceof PsiWildcardType wildcard) {
            PsiType bound = wildcard.getBound();
            return bound == null ? SchemaNode.of("unknown") : resolveType(bound, PsiSubstitutor.EMPTY, warnings, visit, depth + 1, request);
        }
        if (type instanceof PsiArrayType arrayType) {
            SchemaNode arr = SchemaNode.of("array");
            arr.items = resolveType(arrayType.getComponentType(), PsiSubstitutor.EMPTY, warnings, visit, depth + 1, request);
            return arr;
        }
        if (type instanceof PsiPrimitiveType primitive) {
            return switch (primitive.getCanonicalText()) {
                case "boolean" -> SchemaNode.of("boolean");
                case "byte", "short", "int", "long", "char" -> SchemaNode.of(primitive.getCanonicalText().equals("char") ? "string" : "integer");
                case "float", "double" -> SchemaNode.of("number");
                default -> SchemaNode.of("unknown");
            };
        }
        if (!(type instanceof PsiClassType classType)) return SchemaNode.of("unknown");
        PsiClassType.ClassResolveResult resolve = classType.resolveGenerics();
        PsiClass clazz = resolve.getElement();
        if (clazz == null) { warnings.add("Tipo não resolvido: " + type.getCanonicalText()); return SchemaNode.of("unknown"); }
        String name = clazz.getQualifiedName();
        if (name == null) name = clazz.getName();
        if (name == null) return SchemaNode.of("unknown");
        PsiType[] parameters = classType.getParameters();
        switch (name) {
            case "java.lang.String", "java.lang.CharSequence", "java.lang.Character", "java.util.UUID", "java.net.URI", "java.net.URL",
                 "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.Instant", "java.time.LocalTime", "java.time.OffsetTime" -> {
                SchemaNode string = SchemaNode.of("string");
                if (name.equals("java.util.UUID")) string.format = "uuid";
                else if (name.equals("java.time.LocalDate")) string.format = "date";
                else if (name.startsWith("java.time.") && !name.equals("java.time.LocalTime")
                    && !name.equals("java.time.OffsetTime")) string.format = "date-time";
                if (name.startsWith("java.time.")) string.javaType = name;
                return string;
            }
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.math.BigInteger", "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong" -> { return SchemaNode.of("integer"); }
            case "java.lang.Float", "java.lang.Double", "java.lang.Number", "java.math.BigDecimal" -> { return SchemaNode.of("number"); }
            case "java.lang.Boolean" -> { return SchemaNode.of("boolean"); }
            case "java.lang.Void" -> { return SchemaNode.of("null"); }
        }
        if (isOneOf(name, "org.springframework.http.ResponseEntity", "org.springframework.http.HttpEntity", "java.util.Optional",
                "java.util.concurrent.CompletableFuture", "reactor.core.publisher.Mono", "org.springframework.http.codec.ServerSentEvent")) {
            if (parameters.length == 0) return SchemaNode.of("unknown");
            SchemaNode inner = resolveType(parameters[0], PsiSubstitutor.EMPTY, warnings, visit, depth + 1, request);
            if (name.equals("java.util.Optional")) inner.nullable = true;
            return inner;
        }
        if (isOneOf(name, "reactor.core.publisher.Flux", "org.springframework.data.domain.Page", "org.springframework.data.domain.Slice", "org.springframework.data.domain.Window")
                || isInheritor(clazz, "java.util.Collection") || name.equals("java.lang.Iterable")) {
            SchemaNode arr = SchemaNode.of("array");
            arr.items = parameters.length == 0 ? SchemaNode.of("unknown") : resolveType(parameters[0], PsiSubstitutor.EMPTY, warnings, visit, depth + 1, request);
            if (name.startsWith("org.springframework.data.domain.")) warnings.add("Page/Slice contém metadados de paginação em runtime; prévia lista apenas o conteúdo.");
            return arr;
        }
        if (isInheritor(clazz, "java.util.Map")) {
            SchemaNode map = SchemaNode.of("object");
            map.additionalPropertiesAllowed = true;
            map.additionalPropertiesSchema = parameters.length > 1 ? resolveType(parameters[1], PsiSubstitutor.EMPTY, warnings, visit, depth + 1, request) : SchemaNode.of("unknown");
            return map;
        }
        if (clazz.isEnum()) {
            SchemaNode enumNode = SchemaNode.of("string");
            for (PsiField field : clazz.getFields()) if (field instanceof PsiEnumConstant constant) {
                String override = jsonPropertyName(constant);
                enumNode.enumValues.add(override == null ? constant.getName() : override);
            }
            for (PsiMethod method : clazz.getMethods()) if (hasAnnotation(method, "JsonValue")) {
                warnings.add("Enum " + clazz.getName() + " usa @JsonValue; valores reais precisam de verificação personalizada.");
                enumNode.enumValues.clear();
                break;
            }
            return enumNode;
        }
        if (name.equals("java.lang.Object") || name.startsWith("com.fasterxml.jackson.databind.JsonNode")) {
            warnings.add("Tipo dinâmico sem estrutura estática comprovável: " + name);
            return SchemaNode.of("unknown");
        }
        String visitKey = type.getCanonicalText();
        if (!visit.add(visitKey)) { warnings.add("Referência recursiva em " + visitKey + "; ramo reduzido a desconhecido."); return SchemaNode.of("unknown"); }
        SchemaNode object = SchemaNode.of("object");
        PsiSubstitutor substitutor = resolve.getSubstitutor();
        boolean snakeCase = hasJsonSnakeCase(clazz);
        Set<String> included = new LinkedHashSet<>();
        for (PsiRecordComponent component : clazz.getRecordComponents()) {
            addField(object, component.getName(), component.getType(), component, snakeCase, substitutor, warnings, visit, depth, request);
            included.add(component.getName());
        }
        for (PsiField field : clazz.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT) || field instanceof PsiEnumConstant) continue;
            if (included.contains(field.getName())) continue;
            addField(object, field.getName(), field.getType(), field, snakeCase, substitutor, warnings, visit, depth, request);
        }
        for (PsiMethod method : clazz.getAllMethods()) {
            if (hasAnnotation(method, "JsonProperty") && method.getParameterList().isEmpty() && method.getReturnType() != null) {
                String fieldName = jsonPropertyName(method);
                if (fieldName != null && !object.properties.containsKey(fieldName))
                    addField(object, fieldName, method.getReturnType(), method, false, substitutor, warnings, visit, depth, request);
            }
        }
        // Jackson normally exposes JavaBean getters in response serialization.
        // This adds declared and inherited getters to the output preview when not shadowed by a field.
        if (!request) for (PsiMethod method : clazz.getAllMethods()) {
            if (method.hasModifierProperty(PsiModifier.STATIC) || !method.hasModifierProperty(PsiModifier.PUBLIC)
                    || method.getParameterList().getParametersCount() != 0 || method.getReturnType() == null) continue;
            String methodName = method.getName();
            String key = null;
            if (methodName.startsWith("get") && methodName.length() > 3 && !methodName.equals("getClass"))
                key = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            else if (methodName.startsWith("is") && methodName.length() > 2)
                key = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            if (key == null) continue;
            String alternate = jsonPropertyName(method);
            if (alternate != null) key = alternate;
            else if (snakeCase) key = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            if (hasAnnotation(method, "JsonIgnore")) { object.properties.remove(key); object.required.remove(key); continue; }
            if (!object.properties.containsKey(key))
                addField(object, key, method.getReturnType(), method, false, substitutor, warnings, visit, depth, false);
        }
        PsiAnnotation ignored = annotation(clazz, "JsonIgnoreProperties");
        if (ignored != null) {
            for (String key : new ArrayList<>(object.properties.keySet())) {
                if (ignored.getText().contains("\"" + key + "\"")) {
                    object.properties.remove(key);
                    object.required.remove(key);
                }
            }
        }
        if (hasAnnotation(clazz, "JsonTypeInfo") || hasAnnotation(clazz, "JsonSubTypes"))
            warnings.add("Polimorfismo Jackson em " + clazz.getName() + " pode incluir discriminador/campos de subtipos não detectados.");
        if (!request && hasAnnotation(clazz, "JsonInclude"))
            warnings.add("@JsonInclude em " + clazz.getName() + " pode omitir campos nulos/vazios na resposta real.");
        visit.remove(visitKey);
        if (object.properties.isEmpty() && name.startsWith("java.")) {
            warnings.add("Tipo Java não mapeado como objeto: " + name);
            return SchemaNode.of("unknown");
        }
        return object;
    }

    private void addField(SchemaNode object, String name, PsiType fieldType, PsiModifierListOwner owner, boolean snakeCase,
                          PsiSubstitutor substitutor, List<String> warnings, Set<String> visit, int depth, boolean request) {
        if (hasAnnotation(owner, "JsonIgnore")) return;
        PsiAnnotation jsonProperty = annotation(owner, "JsonProperty");
        if (jsonProperty != null) {
            String text = jsonProperty.getText();
            if (request && text.contains("READ_ONLY")) return;
            if (!request && text.contains("WRITE_ONLY")) return;
        }
        String alternate = jsonPropertyName(owner);
        String key = alternate != null ? alternate : snakeCase ? name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT) : name;
        boolean required = hasAnnotation(owner, "NotNull") || hasAnnotation(owner, "NotBlank") || hasAnnotation(owner, "NotEmpty") ||
            (jsonProperty != null && jsonProperty.getText().matches("(?s).*required\\s*=\\s*true.*"));
        SchemaNode value = resolveType(fieldType, substitutor, warnings, visit, depth + 1, request);
        if (hasAnnotation(owner, "Nullable")) value.nullable = true;
        if (hasAnnotation(owner, "JsonUnwrapped")) warnings.add("@JsonUnwrapped em " + key + " requer expansão manual; prévia pode divergir.");
        object.field(key, value, required);
    }

    private boolean isInheritor(PsiClass clazz, String qName) {
        PsiClass target = JavaPsiFacade.getInstance(project).findClass(qName, clazz.getResolveScope());
        return target != null && (clazz.equals(target) || clazz.isInheritor(target, true));
    }
    private static boolean isOneOf(String name, String... values) { return Arrays.asList(values).contains(name); }
    private static boolean hasJsonSnakeCase(PsiClass clazz) {
        PsiAnnotation ann = annotation(clazz, "JsonNaming");
        return ann != null && ann.getText().contains("SnakeCase");
    }
    private static String jsonPropertyName(PsiModifierListOwner owner) {
        PsiAnnotation ann = annotation(owner, "JsonProperty");
        if (ann == null) return null;
        Object value = literal(ann.findAttributeValue("value"));
        return value instanceof String text && !text.isBlank() ? text : null;
    }
    private static String literalString(PsiAnnotation annotation, String key) {
        if (annotation == null) return "";
        Object value = literal(annotation.findAttributeValue(key));
        return value instanceof String text ? text : "";
    }
    private static boolean hasAnnotation(PsiModifierListOwner owner, String shortName) { return annotation(owner, shortName) != null; }
    private static PsiAnnotation annotation(PsiModifierListOwner owner, String shortName) {
        if (owner.getModifierList() == null) return null;
        for (PsiAnnotation ann : owner.getModifierList().getAnnotations()) {
            String q = ann.getQualifiedName();
            if (shortName.equals(q) || (q != null && q.endsWith("." + shortName))) return ann;
        }
        return null;
    }
    private static boolean isFeign(PsiClass clazz) { return hasAnnotation(clazz, "FeignClient"); }
    private static boolean isSupportedClass(PsiClass clazz) {
        return hasAnnotation(clazz, "RestController") || hasAnnotation(clazz, "Controller") || isFeign(clazz);
    }
    private static PsiAnnotation mapping(PsiModifierListOwner owner) {
        for (String name : List.of("PostMapping", "GetMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping")) {
            PsiAnnotation ann = annotation(owner, name);
            if (ann != null) return ann;
        }
        return null;
    }
    private static boolean isMapped(PsiMethod method) { return mapping(method) != null; }
    private static String inferVerb(PsiAnnotation ann) {
        if (ann == null) return "ANY";
        String name = ann.getQualifiedName();
        if (name == null) return "ANY";
        if (name.endsWith("PostMapping")) return "POST";
        if (name.endsWith("GetMapping")) return "GET";
        if (name.endsWith("PutMapping")) return "PUT";
        if (name.endsWith("PatchMapping")) return "PATCH";
        if (name.endsWith("DeleteMapping")) return "DELETE";
        PsiElement method = ann.findAttributeValue("method");
        if (method != null && method.getText().contains("RequestMethod.")) {
            for (String verb : List.of("POST", "GET", "PUT", "PATCH", "DELETE")) if (method.getText().contains("RequestMethod." + verb)) return verb;
        }
        return "ANY";
    }
    private static String pathFromMapping(PsiAnnotation ann) {
        if (ann == null) return "";
        PsiElement element = ann.findAttributeValue("path");
        if (element == null) element = ann.findAttributeValue("value");
        if (element instanceof PsiArrayInitializerMemberValue array && array.getInitializers().length > 0) element = array.getInitializers()[0];
        Object value = literal(element);
        return value instanceof String str ? str : "";
    }
    private static Object literal(PsiElement element) { return element instanceof PsiLiteralExpression e ? e.getValue() : null; }
    private static String joinPath(String a, String b) {
        String text = ("/" + a + "/" + b).replaceAll("/+", "/");
        return text.length() > 1 && text.endsWith("/") ? text.substring(0, text.length()-1) : text;
    }
    private static String methodKey(PsiMethod method) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        for (PsiParameter param : method.getParameterList().getParameters()) out.append(param.getType().getCanonicalText()).append(';');
        return out.append(')').toString();
    }
    private static String methodLabel(PsiMethod method) {
        return inferVerb(mapping(method)) + " " + pathFromMapping(mapping(method)) + " — " + method.getName() + "()";
    }
}
