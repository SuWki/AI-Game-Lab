package com.aigamelab.api;
import com.aigamelab.common.ApiResp;
import com.aigamelab.localtest.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/qa/local-tests")
public class LocalQaController {
    private final LocalTestService tests;private final LocalEvidenceService evidence;
    public LocalQaController(LocalTestService tests,LocalEvidenceService evidence){this.tests=tests;this.evidence=evidence;}
    @GetMapping("/{id}") public ApiResp<JsonNode> review(@PathVariable String id){return ApiResp.ok(evidence.assessment(tests.get(id)));}
}
