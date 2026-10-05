import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLTimeoutException;
import java.util.Properties;

/** Only run against the temporary E2E database; verifies actual JDBC/session deadline and connection reuse. */
class ConnectorDeadlineCheck {
    public static void main(String[] args) throws Exception {
        var config = new ObjectMapper().readTree(Path.of(args[0]).toFile());
        var properties = new Properties();
        config.path("jdbcProperties").fields().forEachRemaining(e -> properties.setProperty(e.getKey(), e.getValue().asText()));
        try (var connection = DriverManager.getConnection(System.getenv("AIO_QUERY_TEST_JDBC_URL"), properties);
             var statement = connection.createStatement()) {
            try (var limit = statement.executeQuery("SELECT @@session.max_execution_time")) {
                if (!limit.next() || limit.getInt(1) != 5000) throw new AssertionError("DB execution limit missing");
            }
            statement.setQueryTimeout(5);
            long start = System.nanoTime();
            try (var result = statement.executeQuery("SELECT SLEEP(10)")) {
                if (!result.next() || result.getInt(1) != 1) throw new AssertionError("Slow query was not interrupted");
            } catch (SQLTimeoutException expected) { }
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (millis > 7500 || millis < 4000) throw new AssertionError("Unexpected deadline: " + millis);
            try (var after = statement.executeQuery("SELECT 1")) {
                if (!after.next() || after.getInt(1) != 1) throw new AssertionError("Connection not reusable");
            }
            System.out.println("JDBC / MySQL deadline verified in " + millis + " ms; connection reusable");
        }
    }
}
