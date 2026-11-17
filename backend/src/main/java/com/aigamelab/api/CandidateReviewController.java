package com.aigamelab.api;

import com.aigamelab.common.ApiResp;
import com.aigamelab.production.CandidateReviewService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/production-candidates/{id}/review")
public class CandidateReviewController {
    private final CandidateReviewService reviews;
    public CandidateReviewController(CandidateReviewService reviews){this.reviews=reviews;}
    @GetMapping public ApiResp<ObjectNode> get(@PathVariable String id){return ApiResp.ok(reviews.view(id));}
    @GetMapping("/history") public ApiResp<List<ObjectNode>> history(@PathVariable String id,@RequestParam(required=false)Integer beforeRevision){return ApiResp.ok(reviews.history(id,beforeRevision));}
    @PostMapping public ApiResp<ObjectNode> save(@PathVariable String id,@RequestBody CandidateReviewService.Request body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(reviews.save(id,body));}
    @GetMapping("/receipt") public ResponseEntity<byte[]> receipt(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);byte[] bytes=reviews.receipt(id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=agl-review-"+id+".json").header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);}
}
