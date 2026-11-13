package com.aigamelab.api;

import com.aigamelab.common.ApiResp;
import com.aigamelab.localtest.LocalTestService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;

@RestController
@RequestMapping("/api/local-tests")
public class LocalTestController {
    private final LocalTestService service;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.localtest.LocalEvidenceService evidence;
    public LocalTestController(LocalTestService service){this.service=service;}
    @GetMapping("/targets") public ApiResp<List<LocalTestService.Target>> targets(){return ApiResp.ok(service.targets());}
    @GetMapping("/runs") public ApiResp<List<LocalTestService.TestRun>> list(){return ApiResp.ok(service.list().stream().map(r->new LocalTestService.TestRun(r.id(),r.target(),r.status(),r.startedAt(),r.finishedAt(),r.exitCode(),"",null,r.error())).toList());}
    @GetMapping("/runs/{id}") public ApiResp<LocalTestService.TestRun> get(@PathVariable String id){return ApiResp.ok(service.get(id));}
    @GetMapping("/runs/{id}/assessment") public ApiResp<com.fasterxml.jackson.databind.JsonNode> assessment(@PathVariable String id){return ApiResp.ok(evidence.assessment(service.get(id)));}
    @GetMapping("/runs/{id}/source") public ApiResp<com.fasterxml.jackson.databind.JsonNode> source(@PathVariable String id,@RequestParam String path){return ApiResp.ok(evidence.source(id,path));}
    @PostMapping("/runs/{id}/import") public ApiResp<com.fasterxml.jackson.databind.JsonNode> importChecks(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false) String marker,HttpServletRequest request){requireLocal(marker,request);return ApiResp.ok(evidence.importChecks(service.get(id)));}
    public record AgentTaskRequest(String agentType,String goal) {}
    @PostMapping("/runs/{id}/agent-task") public ApiResp<com.aigamelab.domain.Task> agentTask(@PathVariable String id,@RequestBody AgentTaskRequest body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){requireLocal(marker,request);return ApiResp.ok(evidence.createAgentTask(service.get(id),body.agentType(),body.goal()));}
    @PostMapping("/targets/{id}/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResp<LocalTestService.TestRun> start(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false) String marker,HttpServletRequest request){
        requireLocal(marker,request);return ApiResp.ok(service.start(id));
    }
    @PostMapping("/runs/{id}/cancel") public ApiResp<LocalTestService.TestRun> cancel(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false) String marker,HttpServletRequest request){
        requireLocal(marker,request);return ApiResp.ok(service.cancel(id));
    }
    public static void requireLocal(String marker,HttpServletRequest request) {
        try {
            String host=request.getServerName();
            if(!"1".equals(marker)||!List.of("localhost","127.0.0.1","::1","[::1]").contains(host)||
                !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
                throw new IllegalArgumentException("Local test mutation requires local access");
        }catch(java.net.UnknownHostException e){throw new IllegalArgumentException("Invalid local address");}
    }
}
