package top.aiolife.query;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GraphqlController {
    private final QueryService service;

    public GraphqlController(QueryService service) { this.service = service; }

    @PostMapping(value = "/graphql", consumes = "application/json",
            produces = {"application/graphql-response+json", "application/json"})
    public Object execute(@RequestHeader(value = "Authorization", required = false) String auth,
                          @RequestBody QueryCompiler.GraphqlRequest request) {
        return service.executeGraphql(QueryController.bearer(auth), request);
    }
}
