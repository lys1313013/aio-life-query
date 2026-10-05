package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class QueryAuditTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private QueryAudit audit(Path directory) {
        return new QueryAudit(new QueryProperties(URI.create("http://localhost/auth"), URI.create("http://localhost/graphql"),
                "s".repeat(32), "w".repeat(32), directory), json);
    }

    @Test void createsPrivateDirectoryAndFileAndPreservesAppend() throws Exception {
        Path directory = temporary.resolve("new/audit");
        var audit = audit(directory);
        var user = new AccessClient.Access("9007199254740993", "aio-life-query", "a".repeat(32), "login", Long.MAX_VALUE);
        audit.write("first", user, "STARTED", null, 0);
        audit.write("second", user, "SUCCEEDED", "fingerprint", 2);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        try (var files = Files.list(directory)) {
            Path file = files.findFirst().orElseThrow();
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            var lines = Files.readAllLines(file);
            assertEquals(2, lines.size());
            assertEquals(user.userId(), json.readTree(lines.getFirst()).path("userId").asText());
            assertEquals("SUCCEEDED", json.readTree(lines.getLast()).path("event").asText());
        }
    }

    @Test void tightensExistingPermissionsWithoutLosingOldRecords() throws Exception {
        Path directory = temporary.resolve("audit");
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path file = directory.resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl");
        Files.writeString(file, "old-record\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        audit(directory).write("new", null, "STARTED", null, 0);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        var lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertEquals("old-record", lines.getFirst());
    }

    @Test void refusesSymbolicLinkDirectoryWithoutChangingTargetPermissions() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        var original = Files.getPosixFilePermissions(target);
        Path directory = Files.createSymbolicLink(temporary.resolve("audit"), target);
        var error = assertThrows(QueryException.class, () -> audit(directory).write("new", null, "STARTED", null, 0));
        assertEquals("AUDIT_UNAVAILABLE", error.code());
        assertEquals(original, Files.getPosixFilePermissions(target));
        try (var files = Files.list(target)) { assertEquals(0, files.count()); }
    }

    @Test void refusesSymbolicLinkFileWithoutWritingOrChangingTarget() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("audit"));
        Path target = Files.writeString(temporary.resolve("target.jsonl"), "untouched\n");
        var original = Files.getPosixFilePermissions(target);
        Files.createSymbolicLink(directory.resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl"), target);
        var error = assertThrows(QueryException.class, () -> audit(directory).write("new", null, "STARTED", null, 0));
        assertEquals("AUDIT_UNAVAILABLE", error.code());
        assertEquals("untouched\n", Files.readString(target));
        assertEquals(original, Files.getPosixFilePermissions(target));
    }
}
