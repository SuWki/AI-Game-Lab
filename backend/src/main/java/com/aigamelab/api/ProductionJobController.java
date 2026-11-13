package com.aigamelab.api;
import com.aigamelab.common.ApiResp;
import com.aigamelab.production.ProductionJobService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/productions")
public class ProductionJobController {
    private final ProductionJobService service;
    public ProductionJobController(ProductionJobService service){this.service=service;}
    public record AgentRequest(long taskId,String specId,int specRevision,String goal){}
    @PostMapping("/agent-task") public ApiResp<com.aigamelab.domain.Task> agentTask(@RequestBody AgentRequest body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.createBuilderTask(body.taskId(),body.specId(),body.specRevision(),body.goal()));}
    @GetMapping("/template") public ApiResp<JsonNode> template(){return ApiResp.ok(service.template());}
    @GetMapping public ApiResp<List<JsonNode>> list(){return ApiResp.ok(service.list());}
    @GetMapping("/{id}") public ApiResp<JsonNode> get(@PathVariable String id){return ApiResp.ok(service.get(id));}
    @PostMapping public ApiResp<JsonNode> start(@RequestBody ProductionJobService.Request body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.start(body));}
    @PostMapping("/{id}/cancel") public ApiResp<JsonNode> cancel(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.cancel(id));}
}
