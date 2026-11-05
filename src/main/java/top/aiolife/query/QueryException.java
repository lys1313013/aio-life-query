package top.aiolife.query;

public class QueryException extends RuntimeException {
    private final String code;
    private final int status;
    private String menuPath;

    public QueryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
    public String menuPath() { return menuPath; }

    public static QueryException secondaryLock(String menuPath) {
        var error = new QueryException("SECONDARY_LOCK_REQUIRED", 403, "请先在 AIO Life 解锁对应业务及 AI 工具访问");
        if (menuPath != null && menuPath.matches("/[A-Za-z0-9/_-]{1,200}")) error.menuPath = menuPath;
        return error;
    }

    public static QueryException invalid() { return new QueryException("QUERY_UNSUPPORTED", 400, "查询不符合已发布的数据集结构或输入限制"); }
    public static QueryException denied() { return new QueryException("ACCESS_DENIED", 403, "查询授权不可用"); }
}
