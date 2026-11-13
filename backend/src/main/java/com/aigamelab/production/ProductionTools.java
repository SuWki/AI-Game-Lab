package com.aigamelab.production;

import com.aigamelab.runtime.RuntimeConversation;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.tool.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.*;
import java.util.*;

@Configuration
public class ProductionTools {
    private final ProductionJobService jobs;private final ProductionArtifactService artifacts;private final RuntimeConversation conversation;private final RunRepository runs;private final ObjectMapper mapper;
    public ProductionTools(ProductionJobService jobs,ProductionArtifactService artifacts,RuntimeConversation conversation,RunRepository runs,ObjectMapper mapper){this.jobs=jobs;this.artifacts=artifacts;this.conversation=conversation;this.runs=runs;this.mapper=mapper;}
    private JsonNode scope(ToolContext ctx){
        if(ctx.runId()==null)throw new ToolException("Builder Run required");var s=conversation.context(ctx.runId());
        if(s==null||!"BUILDER".equals(s.path("agentType").asText())||s.path("productionSpecId").asText().isBlank())throw new ToolException("No authorized production specification");return s;
    }
    private JsonNode spec(JsonNode s){var a=artifacts.get(s.path("productionSpecId").asText(),s.path("productionSpecRevision").asInt());if(a.path("taskId").asLong()!=s.path("productionSourceTaskId").asLong())throw new ToolException("Specification scope changed");return a;}
    private JsonNode compact(JsonNode job){var result=mapper.createObjectNode();for(String key:List.of("id","status","specId","specRevision","error","localRunId"))if(job.has(key))result.set(key,job.get(key));if(job.has("result")){result.set("buildId",job.path("result").path("build").path("buildId"));result.put("workspace",job.path("result").path("workspace").asText());}result.put("qualityAccepted",false);return result;}
    @Bean GameTool productionContext(){return tool("production_context",Map.of("type","object","properties",Map.of(),"additionalProperties",false),(a,c)->{
        var s=scope(c);var artifact=spec(s);var result=mapper.createObjectNode().put("specId",s.path("productionSpecId").asText()).put("revision",s.path("productionSpecRevision").asInt()).put("contentHash",artifact.path("contentHash").asText());
        result.set("template",jobs.template());for(String field:List.of("title","coreLoop","systems","requirements","acceptanceCriteria","exclusions"))result.set(field,artifact.path("content").path(field));return result;
    });}
    @Bean GameTool productionStart(){return tool("production_start",Map.of("type","object","properties",Map.of(),"additionalProperties",false),(a,c)->{
        var s=scope(c);spec(s);if(!active(c,s))throw new ToolException("Builder cancelled or timed out");
        var job=jobs.startForAgent(new ProductionJobService.Request(s.path("productionSourceTaskId").asLong(),s.path("productionSpecId").asText(),s.path("productionSpecRevision").asInt(),"agent-"+c.runId()),c.runId());
        if(!active(c,s)){jobs.cancel(job.path("id").asText());throw new ToolException("Builder cancelled or timed out");}return compact(job);
    });}
    private boolean active(ToolContext ctx,JsonNode s){return runs.findById(ctx.runId()).map(r->"RUNNING".equals(r.status())).orElse(false)&&System.currentTimeMillis()<s.path("deadlineEpochMs").asLong();}
    @Bean GameTool productionResult(){return tool("production_result",Map.of("type","object","properties",Map.of("productionId",Map.of("type","string")),"required",List.of("productionId"),"additionalProperties",false),(a,c)->{
        var s=scope(c);String id=a.path("productionId").asText();long end=System.currentTimeMillis()+10000;
        while(true){var job=jobs.get(id);if(job.path("ownerRunId").asLong(-1)!=c.runId()||!job.path("specId").asText().equals(s.path("productionSpecId").asText()))throw new ToolException("Production belongs to another Agent Run");
            if(!Set.of("PREPARING","QUEUED","RUNNING").contains(job.path("status").asText())||System.currentTimeMillis()>=end)return compact(job);
            if(!runs.findById(c.runId()).map(r->"RUNNING".equals(r.status())).orElse(false)||System.currentTimeMillis()>=s.path("deadlineEpochMs").asLong()){jobs.cancel(id);throw new ToolException("Builder cancelled or timed out");}Thread.sleep(50);
        }
    });}
    interface Action{JsonNode apply(JsonNode a,ToolContext c)throws Exception;}
    private GameTool tool(String name,Map<String,Object> schema,Action action){return new GameTool(){public String name(){return name;}public Map<String,Object> jsonSchema(){return schema;}public boolean requiresApproval(){return false;}public ToolResult execute(JsonNode args,ToolContext ctx){try{return ToolResult.success(mapper.writeValueAsString(action.apply(args,ctx)),name+" complete");}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return ToolResult.failure(e.getMessage()==null?"Production tool failed":e.getMessage());}}};}
}
