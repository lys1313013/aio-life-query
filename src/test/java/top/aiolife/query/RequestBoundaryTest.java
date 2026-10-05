package top.aiolife.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Path;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestBoundaryTest {
    private final QueryService service = mock(QueryService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var properties = new QueryProperties(URI.create("http://localhost/access"), URI.create("http://localhost/graphql"),
                "fixture-service-key-at-least-32-characters", "fixture-webhook-key-at-least-32-characters", Path.of("target/audit"));
        mvc = MockMvcBuilders.standaloneSetup(new QueryController(service, properties))
                .setControllerAdvice(new QueryErrors()).addFilters(new RequestBoundaryFilter()).build();
    }

    @Test void forgedRoleNeverReachesService() throws Exception {
        mvc.perform(get("/api/v1/datasets").header("Authorization", "Bearer aqt_fake").header("X-Hasura-Role", "admin"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }

    @Test void oversizedRequestIsRejectedBeforeParsing() throws Exception {
        mvc.perform(post("/api/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service);
    }

    @Test void webhookRequiresSeparateSecretAndRejectsAmbiguousAuthorization() throws Exception {
        String headers = "{\"headers\":{\"authorization\":\"Bearer ticket\",\"Authorization\":\"Bearer another\"}}";
        mvc.perform(post("/internal/hasura/auth").contentType(MediaType.APPLICATION_JSON).content(headers))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/hasura/auth").header("X-AIO-Webhook-Key", "fixture-webhook-key-at-least-32-characters")
                        .contentType(MediaType.APPLICATION_JSON).content(headers))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
