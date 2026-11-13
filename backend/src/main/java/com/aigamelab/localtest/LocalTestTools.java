package com.aigamelab.localtest;

import com.aigamelab.runtime.RuntimeConversation;
import com.aigamelab.tool.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.*;
import java.util.*;

/** Task-scoped tools. Target IDs, commands and approval decisions cannot be supplied by the model. */
@Configuration
public class LocalTestTools {
    private final LocalTestService tests;private final LocalEvidenceService evidence;private final LocalChangeService changes;private final RuntimeConversation conversation;private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.repository.RunRepository runs;
    public LocalTestTools(LocalTestService tests,LocalEvidenceService evidence,LocalChangeService changes,RuntimeConversation conversation,ObjectMapper mapper){this.tests=tests;this.evidence=evidence;this.changes=changes;this.conversation=conversation;this.mapper=mapper;}
    private JsonNode scope(ToolContext ctx){if(ctx.runId()==null)throw new ToolException("Agent Run required");JsonNode scope=conversation.context(ctx.runId());if(scope==null||scope.path("localTargetId").asText().isBlank())throw new ToolException("Task has no authorized local target");return scope;}
    private LocalTestService.TestRun owned(JsonNode args,ToolContext ctx) {
        JsonNode scope=scope(ctx);var run=tests.get(args.path("localTestRunId").asText());JsonNode binding=evidence.binding(run.id());
        if(!run.target().id().equals(scope.path("localTargetId").asText())||
            !(run.id().equals(scope.path("localTestRunId").asText())||binding.path("ownerRunId").asLong(-1)==ctx.runId()))throw new ToolException("Local test is not authorized for this task");
        return run;
    }
    private GameTool tool(String name,Map<String,Object> schema,boolean approval,java.util.function.BiFunction<JsonNode,ToolContext,JsonNode> fn){return new GameTool(){public String name(){return name;}public Map<String,Object> jsonSchema(){return schema;}public boolean requiresApproval(){return approval;}public ToolResult execute(JsonNode args,ToolContext ctx){return ToolResult.success(fn.apply(args,ctx).toString(),name+" completed");}};}
    private Map<String,Object> runSchema(){return Map.of("type","object","properties",Map.of("localTestRunId",Map.of("type","string"),"sourceOffset",Map.of("type","integer"),"checkOffset",Map.of("type","integer")),"required",List.of("localTestRunId"));}
    @Bean GameTool localTestStart(){return tool("local_test_start",Map.of("type","object","properties",Map.of(),"additionalProperties",false),false,(a,c)->{
        JsonNode bound=scope(c);var baseline=tests.get(bound.path("localTestRunId").asText());evidence.verify(baseline.id());
        var registered=tests.targets().stream().filter(t->t.id().equals(bound.path("localTargetId").asText())).findFirst().orElseThrow(()->new ToolException("Registered target was removed"));
        try {
            if(!java.nio.file.Path.of(registered.workingDirectory()).toRealPath().equals(java.nio.file.Path.of(baseline.target().workingDirectory()).toRealPath())||!registered.command().equals(baseline.target().command())||!registered.versionFiles().equals(baseline.target().versionFiles()))throw new ToolException("Target registration changed; create a new baseline task");
        }catch(java.io.IOException e){throw new ToolException("Target root unavailable");}
        var run=tests.startForAgent(registered.id(),c.runId());
        long deadline=bound.path("deadlineEpochMs").asLong(System.currentTimeMillis()+registered.timeoutSeconds()*1000L);
        while(Set.of("QUEUED","RUNNING").contains(run.status())) {
            if(System.currentTimeMillis()>=deadline||runs.findById(c.runId()).map(r->!"RUNNING".equals(r.status())).orElse(true)) {
                tests.cancel(run.id());throw new ToolException("Agent cancelled or deadline reached while waiting for local test");
            }
            try{Thread.sleep(100);}catch(InterruptedException e){tests.cancel(run.id());Thread.currentThread().interrupt();throw new ToolException("Local test wait interrupted");}
            run=tests.get(run.id());
        }
        return compact(run,0,0);
    });}
    @Bean GameTool localTestResult(){return tool("local_test_result",runSchema(),false,(a,c)->compact(owned(a,c),a.path("sourceOffset").asInt(0),a.path("checkOffset").asInt(0)));}
    @Bean GameTool localTestImport(){return tool("local_test_import",runSchema(),false,(a,c)->evidence.importChecks(owned(a,c)));}
    @Bean GameTool localSourceRead(){return tool("local_source_read",Map.of("type","object","properties",Map.of("localTestRunId",Map.of("type","string"),"path",Map.of("type","string"),"offset",Map.of("type","integer"),"limit",Map.of("type","integer")),"required",List.of("localTestRunId","path")),false,(a,c)->{
        var run=owned(a,c);JsonNode source=evidence.source(run.id(),a.path("path").asText());String text=source.path("text").asText();int offset=a.path("offset").asInt(0),limit=a.path("limit").asInt(2000);
        if(offset<0||offset>text.length()||limit<1||limit>3000)throw new ToolException("Source read range invalid");
        return mapper.createObjectNode().put("path",source.path("path").asText()).put("sha256",source.path("sha256").asText()).put("offset",offset).put("totalCharacters",text.length()).put("text",text.substring(offset,Math.min(offset+limit,text.length())));
    });}
    private JsonNode compact(LocalTestService.TestRun run,int offset,int checkOffset) {
        if(offset<0||offset>5000||checkOffset<0||checkOffset>10000)throw new ToolException("Evidence page offset invalid");
        var assessment=evidence.assessment(run);var result=mapper.createObjectNode().put("localTestRunId",run.id()).put("executionStatus",run.status()).put("decision",assessment.path("decision").asText()).put("version",assessment.path("binding").path("version").isNull()?null:assessment.path("binding").path("version").asText());
        result.set("failedChecks",assessment.has("failedChecks")?assessment.path("failedChecks"):com.fasterxml.jackson.databind.node.NullNode.instance);
        result.set("totalChecks",assessment.has("totalChecks")?assessment.path("totalChecks"):com.fasterxml.jackson.databind.node.NullNode.instance);
        if(assessment.has("reason"))result.put("reason",assessment.path("reason").asText());
        if(run.error()!=null)result.put("executionError",run.error());
        var checks=mapper.createArrayNode();var all=run.evidence()==null?mapper.createArrayNode():run.evidence().path("checks");
        for(int i=checkOffset;i<Math.min(all.size(),checkOffset+2);i++) {
            var check=checks.addObject();for(String field:List.of("id","status","title","severity","category","expected","actual"))if(all.get(i).has(field)){String text=all.get(i).path(field).asText();check.put(field,text.length()>200?text.substring(0,200)+" [truncated]":text);}
        }
        result.set("checks",checks);result.put("nextCheckOffset",Math.min(all.size(),checkOffset+2));
        var files=mapper.createObjectNode();var fields=assessment.path("binding").path("source").fields();int index=0;
        while(fields.hasNext()){var entry=fields.next();if(index>=offset&&index<offset+4)files.set(entry.getKey(),entry.getValue());index++;}
        result.set("source",files);result.put("sourceCount",index).put("nextSourceOffset",Math.min(index,offset+4));return result;
    }
    @Bean GameTool localChangePropose(){
        Map<String,Object> edit=Map.of("type","object","properties",Map.of("path",Map.of("type","string"),"beforeSha256",Map.of("type","string"),"afterText",Map.of("type","string")),"required",List.of("path","afterText"));
        return tool("local_change_propose",Map.of("type","object","properties",Map.of("localTestRunId",Map.of("type","string"),"title",Map.of("type","string"),"edits",Map.of("type","array","items",edit)),"required",List.of("localTestRunId","title","edits")),true,(a,c)->{
            var run=owned(a,c);List<LocalChangeService.Edit> edits=mapper.convertValue(a.path("edits"),new com.fasterxml.jackson.core.type.TypeReference<List<LocalChangeService.Edit>>(){});
            var p=changes.create(run.id(),a.path("title").asText(),edits);return mapper.createObjectNode().put("localProposalId",p.id()).put("status",p.status());
        });
    }
}
