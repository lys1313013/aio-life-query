package top.aiolife.query;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;

@Validated
@ConfigurationProperties("aio.query")
public record QueryProperties(@NotNull URI accessUrl, @NotNull URI hasuraUrl,
                              @NotNull @Size(min = 32) String serviceKey,
                              @NotNull @Size(min = 32) String webhookKey,
                              @NotNull Path auditDirectory) {
    public static final String RELEASE = "time-record-v1";
}
