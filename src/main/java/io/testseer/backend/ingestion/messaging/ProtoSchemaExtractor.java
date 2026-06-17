package io.testseer.backend.ingestion.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProtoSchemaExtractor {

    private static final Pattern MESSAGE_DEF =
            Pattern.compile("message\\s+(\\w+)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern FIELD_DEF =
            Pattern.compile("(repeated\\s+)?(\\w+)\\s+(\\w+)\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern ENUM_DEF =
            Pattern.compile("enum\\s+(\\w+)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern ENUM_VALUE =
            Pattern.compile("(\\w+)\\s*=\\s*(\\d+)\\s*;");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Parsed proto catalog keyed by simple message name. */
    public Map<String, ProtoMessage> extractCatalog(List<YamlPubSubExtractor.ConfigFile> protoFiles) {
        Map<String, ProtoMessage> catalog = new LinkedHashMap<>();
        for (YamlPubSubExtractor.ConfigFile file : protoFiles) {
            if (!file.path().endsWith(".proto")) continue;
            parseFile(file.path(), file.content(), catalog);
        }
        return catalog;
    }

    public List<FactBatch.MessageSchemaFact> extractFromJava(
            List<JavaSourceFile> javaFiles,
            Map<String, ProtoMessage> catalog) {
        List<FactBatch.MessageSchemaFact> results = new ArrayList<>();
        Pattern unpack = Pattern.compile("\\.unpack\\(([\\w.]+)\\.class\\)");
        Pattern pack = Pattern.compile("Any\\.pack\\((\\w+)");

        for (JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            Matcher um = unpack.matcher(file.content());
            while (um.find()) {
                String type = um.group(1);
                String simple = simpleName(type);
                ProtoMessage msg = catalog.get(simple);
                results.add(buildSchemaFact(file.classFqn(), "INBOUND", type, simple, msg, file.path(),
                        ".unpack(" + type + ".class)", "JAVA_INFERRED", 0.90));
            }
            Matcher pm = pack.matcher(file.content());
            while (pm.find()) {
                String var = pm.group(1);
                ProtoMessage msg = catalog.get(var);
                if (msg != null) {
                    results.add(buildSchemaFact(file.classFqn(), "OUTBOUND", msg.javaFqn(), msg.name(), msg,
                            file.path(), "Any.pack(" + var + ")", "JAVA_INFERRED", 0.85));
                }
            }
        }
        return results;
    }

    private void parseFile(String path, String content, Map<String, ProtoMessage> catalog) {
        String javaPackage = extractPackage(content);
        String outerClass = extractOption(content, "java_outer_classname");
        String javaFqnPrefix = javaPackage + "." + (outerClass != null ? outerClass : "");

        Matcher em = ENUM_DEF.matcher(content);
        Map<String, List<Map<String, String>>> enums = new LinkedHashMap<>();
        while (em.find()) {
            List<Map<String, String>> values = new ArrayList<>();
            Matcher ev = ENUM_VALUE.matcher(em.group(2));
            while (ev.find()) {
                values.add(Map.of("name", ev.group(1), "number", ev.group(2)));
            }
            enums.put(em.group(1), values);
        }

        Matcher mm = MESSAGE_DEF.matcher(content);
        while (mm.find()) {
            String name = mm.group(1);
            List<Map<String, String>> fields = new ArrayList<>();
            Matcher fm = FIELD_DEF.matcher(mm.group(2));
            while (fm.find()) {
                fields.add(Map.of(
                        "name", fm.group(3),
                        "type", fm.group(2),
                        "number", fm.group(4),
                        "repeated", fm.group(1) != null ? "true" : "false"
                ));
            }
            String javaFqn = javaFqnPrefix.isEmpty() ? name : javaFqnPrefix + "." + name;
            catalog.put(name, new ProtoMessage(name, javaFqn, path, fields, enums));
        }
    }

    private FactBatch.MessageSchemaFact buildSchemaFact(
            String classFqn, String direction, String typeFqn, String simpleName,
            ProtoMessage msg, String javaPath, String expression, String source, double confidence) {
        String fieldsJson = "[]";
        String enumsJson = null;
        String protoFile = javaPath;
        if (msg != null) {
            fieldsJson = toJson(msg.fields());
            enumsJson = toJson(msg.enums());
            protoFile = msg.protoFile();
        }
        return new FactBatch.MessageSchemaFact(
                "QMsgEvent", typeFqn, fieldsJson, enumsJson,
                classFqn, null, direction, null, expression, protoFile, source, confidence
        );
    }

    private String extractPackage(String content) {
        Matcher m = Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(content);
        return m.find() ? m.group(1) : "";
    }

    private String extractOption(String content, String option) {
        Matcher m = Pattern.compile("option\\s+" + option + "\\s*=\\s*\"([^\"]+)\"").matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    public record ProtoMessage(
            String name,
            String javaFqn,
            String protoFile,
            List<Map<String, String>> fields,
            Map<String, List<Map<String, String>>> enums
    ) {}

    public record JavaSourceFile(String path, String content, String classFqn) {}
}
