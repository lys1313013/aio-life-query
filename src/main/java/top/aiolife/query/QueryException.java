package top.aiolife.query;

public class QueryException extends RuntimeException {
    private final String code;
    private final int status;

    public QueryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }

    public static QueryException invalid() { return new QueryException("QUERY_UNSUPPORTED", 400, "查询不符合已发布的数据集结构或输入限制"); }
    public static QueryException denied() { return new QueryException("ACCESS_DENIED", 403, "查询授权不可用"); }
}
