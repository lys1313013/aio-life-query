package top.aiolife.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

/** 目录、查询白名单与 Hasura 元数据共用 datasets.json，禁止全库自动导入。 */
public final class QueryDatasets {
    public record Field(String column, String kind, String sqlType, boolean nullable) {
        public String apiType() { return "INT".equals(kind) ? "Int" : "String"; }
        public String comparison() {
            return switch (kind) {
                case "DATE", "DATETIME" -> "DateComparison";
                case "INT" -> "IntComparison";
                case "DECIMAL" -> "DecimalComparison";
                default -> "IdComparison";
            };
        }
    }
    public record Dataset(String name, String domain, String typeName, String root, String statsRoot,
                          String release, String role, String scope, String description, List<String> rangeFields,
                          Map<String, Field> fields, List<String> aggregateFields, List<String> orderFields) {
        public String boolType() { return typeName + "BoolExp"; }
        public String orderType() { return typeName + "OrderBy"; }
        public String statsType() { return typeName + "Stats"; }
        public String sdl() {
            var out = new StringBuilder("type Query {\n  ").append(root).append("(where: ").append(boolType())
                    .append("!, limit: Int = 100, offset: Int = 0, orderBy: [").append(orderType()).append("!]): [")
                    .append(typeName).append("!]!\n  ").append(statsRoot).append("(where: ").append(boolType())
                    .append("!): ").append(statsType()).append("!\n}\ntype ").append(typeName).append(" {\n");
            fields.forEach((name, field) -> out.append("  ").append(name).append(": ").append(field.apiType())
                    .append("id".equals(name) ? "!" : "").append('\n'));
            out.append("}\ntype ").append(statsType()).append(" { count: String!");
            aggregateFields.forEach(name -> out.append(' ').append(name).append(": NumericStats"));
            out.append(" }\ntype NumericStats { sum: String }\ninput ").append(boolType()).append(" {\n");
            fields.forEach((name, field) -> out.append("  ").append(name).append(": ").append(field.comparison()).append('\n'));
            out.append("}\ninput ").append(orderType()).append(" {\n");
            orderFields.forEach(name -> out.append("  ").append(name).append(": OrderDirection\n"));
            return out.append("}\ninput DateComparison { _gte: String! _lt: String! }\n")
                    .append("input IdComparison { _eq: String! }\ninput IntComparison { _eq: Int _gte: Int _lte: Int }\n")
                    .append("input DecimalComparison { _eq: String _gte: String _lte: String }\n")
                    .append("enum OrderDirection { Asc Desc }\n").toString();
        }
        public String example() {
            return "query Recent($start:String!,$end:String!){" + root + "(where:{" + rangeFields.getFirst()
                    + ":{_gte:$start,_lt:$end}},limit:100){id}}";
        }
    }
    private static final List<Dataset> ALL;
    static {
        try (var input = new ClassPathResource("datasets.json").getInputStream()) {
            ALL = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<Dataset>>() {}));
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    private QueryDatasets() {}
    public static List<Dataset> all() { return ALL; }
    public static Dataset named(String name) {
        return ALL.stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new QueryException("DATASET_UNAVAILABLE", 404, "数据集不可用"));
    }
    public static Dataset domain(String domain) {
        return ALL.stream().filter(d -> d.domain().equals(domain)).findFirst().orElseThrow(QueryException::invalid);
    }
}
