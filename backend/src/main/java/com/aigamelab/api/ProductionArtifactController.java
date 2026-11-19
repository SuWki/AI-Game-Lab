package com.aigamelab.api;
import com.aigamelab.common.ApiResp;
import com.aigamelab.production.ProductionArtifactService;
import com.aigamelab.production.DesignerHandoffService;
import com.aigamelab.production.ManufacturingScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/production-artifacts")
public class ProductionArtifactController {
    private final ProductionArtifactService service;
    private final DesignerHandoffService handoff;
    private final ManufacturingScopeService scopes;
    public ProductionArtifactController(ProductionArtifactService service, DesignerHandoffService handoff, ManufacturingScopeService scopes){
        this.service=service;this.handoff=handoff;this.scopes=scopes;
    }
    @PostMapping public ApiResp<JsonNode> save(@RequestBody ProductionArtifactService.Request body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.save(body));}
    @GetMapping public ApiResp<List<JsonNode>> list(@RequestParam long taskId){return ApiResp.ok(service.list(taskId));}
    @GetMapping("/{id}/revisions/{revision}") public ApiResp<JsonNode> get(@PathVariable String id,@PathVariable int revision){return ApiResp.ok(service.get(id,revision));}
    @GetMapping("/designer-drafts")
    public ApiResp<List<DesignerHandoffService.Draft>> drafts(@RequestParam long taskId) {
        return ApiResp.ok(handoff.drafts(taskId));
    }
    @PostMapping("/designer-drafts/{runId}")
    public ApiResp<JsonNode> importDraft(@PathVariable long runId, @RequestBody DesignerHandoffService.Save body,
            @RequestHeader(value="X-AGL-Local-Test",required=false) String marker, HttpServletRequest request) {
        LocalTestController.requireLocal(marker,request);
        return ApiResp.ok(handoff.save(runId, body));
    }
    @GetMapping("/{id}/revisions/{revision}/manufacturing-scope")
    public ApiResp<ManufacturingScopeService.Confirmation> scope(@PathVariable String id, @PathVariable int revision) {
        var spec = service.get(id, revision);
        return ApiResp.ok(scopes.find(id, revision, spec.path("contentHash").asText()).orElse(null));
    }
    @PostMapping("/manufacturing-scope")
    public ApiResp<ManufacturingScopeService.Confirmation> confirm(@RequestBody ManufacturingScopeService.Request body,
            @RequestHeader(value="X-AGL-Local-Test",required=false) String marker, HttpServletRequest request) {
        LocalTestController.requireLocal(marker, request);
        return ApiResp.ok(scopes.confirm(body));
    }
}
