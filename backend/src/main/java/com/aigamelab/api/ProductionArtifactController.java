package com.aigamelab.api;
import com.aigamelab.common.ApiResp;
import com.aigamelab.production.ProductionArtifactService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/production-artifacts")
public class ProductionArtifactController {
    private final ProductionArtifactService service;
    public ProductionArtifactController(ProductionArtifactService service){this.service=service;}
    @PostMapping public ApiResp<JsonNode> save(@RequestBody ProductionArtifactService.Request body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.save(body));}
    @GetMapping public ApiResp<List<JsonNode>> list(@RequestParam long taskId){return ApiResp.ok(service.list(taskId));}
    @GetMapping("/{id}/revisions/{revision}") public ApiResp<JsonNode> get(@PathVariable String id,@PathVariable int revision){return ApiResp.ok(service.get(id,revision));}
}
