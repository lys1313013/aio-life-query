package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Set;

/** 首期使用独立持久卷保存摘要，force 成功后才允许执行/交付；禁止传入正文和凭据。 */
@Component
public class QueryAudit {
    private final QueryProperties properties;
    private final ObjectMapper json;
    public QueryAudit(QueryProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }

    public synchronized void write(String id, AccessClient.Access access, String event, String fingerprint, int rows) {
        try {
            var directory = properties.auditDirectory();
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("Invalid audit directory");
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            var record = new LinkedHashMap<String, Object>();
            record.put("timestamp", Instant.now().toString()); record.put("queryId", id);
            record.put("event", event); record.put("releaseId", QueryProperties.RELEASE);
            if (access != null) {
                record.put("userId", access.userId()); record.put("appId", access.appId());
                record.put("credentialId", access.credentialId()); record.put("credentialType", access.credentialType());
            }
            if (fingerprint != null) record.put("fingerprint", fingerprint);
            record.put("rowCount", rows);
            var path = properties.auditDirectory().resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl");
            byte[] bytes = (json.writeValueAsString(record) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(path,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                // 旧文件也必须先收紧权限，再追加新的审计记录。
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch (Exception e) { throw new QueryException("AUDIT_UNAVAILABLE", 503, "查询审计不可用"); }
    }
}
