package io.testseer.backend.ingestion.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BigQueryDirectExtractor {

    private static final Pattern BQ_UTIL_CALL =
            Pattern.compile("(\\w+)\\.(save|insertAll|insertRows|insert)\\s*\\([^,]+,\\s*[^,]+,\\s*(\\w+)");
    private static final Pattern TABLE_CONST =
            Pattern.compile("(?:static\\s+)?(?:final\\s+)?String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"");

    private final TypeFqnResolver typeFqnResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public BigQueryDirectExtractor(TypeFqnResolver typeFqnResolver) {
        this.typeFqnResolver = typeFqnResolver;
    }

    public List<FactBatch.DataAccessFact> extract(
            String orgId, String serviceId, List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        List<FactBatch.DataAccessFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            String content = file.content();
            ImportIndex imports = ImportIndex.build(content);
            var ctx = new TypeFqnResolver.CompilationContext(orgId, serviceId, file.classFqn());
            FieldTypeIndex fields = FieldTypeIndex.build(content, imports, typeFqnResolver, ctx);
            Map<String, String> constants = tableConstants(content);

            Matcher m = BQ_UTIL_CALL.matcher(content);
            while (m.find()) {
                String utilField = m.group(1);
                String methodName = m.group(2);
                String tableRef = m.group(3);
                String utilFqn = fields.resolveAccessorFqn(utilField, imports);
                if (utilFqn == null || !utilFqn.toLowerCase(Locale.ROOT).contains("bigquery")) continue;

                String handlerMethod = enclosingMethod(content, m.start());
                String physicalName = constants.getOrDefault(tableRef, constantToTable(tableRef));
                String key = file.classFqn() + "|" + handlerMethod + "|" + utilField + "|" + tableRef;
                if (!seen.add(key)) continue;

                results.add(FactBatch.DataAccessFact.linked(
                        file.classFqn(),
                        handlerMethod,
                        "WRITE",
                        StoreType.BIGQUERY.dbValue(),
                        physicalName,
                        utilField,
                        methodName,
                        correlationKeys(),
                        null,
                        "BIGQUERY_DIRECT",
                        0.82,
                        null,
                        null,
                        utilFqn,
                        "BIGQUERY_UTIL",
                        null,
                        null
                ));
            }
        }
        return results;
    }

    private static Map<String, String> tableConstants(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = TABLE_CONST.matcher(content);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static String constantToTable(String constantName) {
        if (constantName == null) return null;
        return constantName.toLowerCase(Locale.ROOT)
                .replace("_table", "")
                .replace('_', ' ');
    }

    private static String enclosingMethod(String content, int callIndex) {
        Matcher m = Pattern.compile("(public|protected)\\s+[\\w<>,\\[\\].\\s]+\\s+(\\w+)\\s*\\(").matcher(content);
        String last = null;
        while (m.find()) {
            if (m.start() < callIndex) {
                String name = m.group(2);
                if (!name.equals("class") && !name.startsWith("get") && !name.startsWith("set")) {
                    last = name;
                }
            }
        }
        return last;
    }

    private String correlationKeys() {
        try {
            return mapper.writeValueAsString(List.of("transactionId"));
        } catch (JsonProcessingException ex) {
            return "[\"transactionId\"]";
        }
    }
}
