package com.aigamelab.api;

import com.aigamelab.common.ApiResp;
import com.aigamelab.production.CandidateBrowserEvidenceService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/production-candidates/{id}/browser-evidence")
public class CandidateBrowserEvidenceController {
    private final CandidateBrowserEvidenceService evidence;

    public CandidateBrowserEvidenceController(CandidateBrowserEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    public ApiResp<ObjectNode> plan(@PathVariable String id) {
        return ApiResp.ok(evidence.plan(id));
    }

    @PostMapping
    public ApiResp<ObjectNode> record(@PathVariable String id, @RequestBody CandidateBrowserEvidenceService.Observation body,
            @RequestHeader(value = "X-AGL-Local-Test", required = false) String marker, HttpServletRequest request) {
        LocalTestController.requireLocal(marker, request);
        return ApiResp.ok(evidence.record(id, body));
    }

    @GetMapping("/{executionId}")
    public ApiResp<ObjectNode> execution(@PathVariable String id, @PathVariable String executionId) {
        return ApiResp.ok(evidence.execution(id, executionId));
    }
}
