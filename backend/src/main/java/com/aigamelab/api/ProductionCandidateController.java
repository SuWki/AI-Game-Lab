package com.aigamelab.api;

import com.aigamelab.common.ApiResp;
import com.aigamelab.production.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ProductionCandidateController {
    private final ProductionCandidateService candidates;private final CandidatePreviewService previews;
    public record PackageRequest(String validationRunId){}
    public ProductionCandidateController(ProductionCandidateService candidates,CandidatePreviewService previews){this.candidates=candidates;this.previews=previews;}
    @GetMapping("/api/productions/{id}/candidates") public ApiResp<List<ObjectNode>> list(@PathVariable String id){return ApiResp.ok(candidates.list(id));}
    @PostMapping("/api/productions/{id}/candidates") public ApiResp<ObjectNode> create(@PathVariable String id,@RequestBody PackageRequest body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(candidates.create(id,body.validationRunId()));}
    @GetMapping("/api/production-candidates/{id}") public ApiResp<ObjectNode> get(@PathVariable String id){return ApiResp.ok(candidates.get(id));}
    @GetMapping("/api/production-candidates/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=agl-candidate-"+candidates.get(id).path("id").asText()+".zip").header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.parseMediaType("application/zip")).body(candidates.download(id));}
    @PostMapping("/api/production-candidates/{id}/preview") public ApiResp<ObjectNode> preview(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(previews.start(id));}
    @PostMapping("/api/production-candidates/{id}/preview/stop") public ApiResp<ObjectNode> stop(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(previews.stop(id));}
}
