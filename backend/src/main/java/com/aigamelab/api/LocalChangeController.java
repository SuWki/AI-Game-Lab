package com.aigamelab.api;
import com.aigamelab.common.ApiResp;
import com.aigamelab.localtest.LocalChangeService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
@RestController @RequestMapping("/api/local-tests/changes")
public class LocalChangeController {
    public record Request(String baseRunId,String title,List<LocalChangeService.Edit> edits) {}
    private final LocalChangeService service;
    public LocalChangeController(LocalChangeService service){this.service=service;}
    @GetMapping public ApiResp<List<LocalChangeService.Proposal>> list(){return ApiResp.ok(service.list());}
    @GetMapping("/{id}") public ApiResp<LocalChangeService.Proposal> get(@PathVariable String id){return ApiResp.ok(service.get(id));}
    @PostMapping public ApiResp<LocalChangeService.Proposal> create(@RequestBody Request body,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.create(body.baseRunId(),body.title(),body.edits()));}
    @PostMapping("/{id}/approve") public ApiResp<LocalChangeService.Proposal> approve(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.approve(id));}
    @PostMapping("/{id}/reject") public ApiResp<LocalChangeService.Proposal> reject(@PathVariable String id,@RequestHeader(value="X-AGL-Local-Test",required=false)String marker,HttpServletRequest request){LocalTestController.requireLocal(marker,request);return ApiResp.ok(service.reject(id));}
}
