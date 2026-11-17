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
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.localtest.LocalEvidenceService evidence;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.localtest.LocalChangeService changes;
    public ProductionTools(ProductionJobService jobs,ProductionArtifactService artifacts,RuntimeConversation conversation,RunRepository runs,ObjectMapper mapper){this.jobs=jobs;this.artifacts=artifacts;this.conversation=conversation;this.runs=runs;this.mapper=mapper;}
    private JsonNode scope(ToolContext ctx){
        if(ctx.runId()==null)throw new ToolException("Builder Run required");var s=conversation.context(ctx.runId());
        if(s==null||!"BUILDER".equals(s.path("agentType").asText())||s.path("productionSpecId").asText().isBlank())throw new ToolException("No authorized production specification");return s;
    }
    private JsonNode spec(JsonNode s){var a=artifacts.get(s.path("productionSpecId").asText(),s.path("productionSpecRevision").asInt());if(a.path("taskId").asLong()!=s.path("productionSourceTaskId").asLong())throw new ToolException("Specification scope changed");return a;}
    private JsonNode compact(JsonNode job){
        var result=mapper.createObjectNode();
        for(String key:List.of("id","status","specId","specRevision","error","localRunId","validationRunId","validationStatus","validationDecision","validationScope","validationChecks"))if(job.has(key))result.set(key,job.get(key));
        if(job.has("iteration")){
            var state=(com.fasterxml.jackson.databind.node.ObjectNode)job.get("iteration").deepCopy();
            if(!state.path("currentRunId").asText().isBlank()){
                result.set("validationRunId",state.path("currentRunId"));result.set("validationStatus",state.path("currentStatus"));result.set("validationDecision",state.path("currentDecision"));
                if(state.has("currentChecks"))result.set("validationChecks",state.remove("currentChecks"));
            }
            result.set("iteration",state);
        }
        if(job.has("result")){result.set("buildId",job.path("result").path("build").path("buildId"));result.put("workspace",job.path("result").path("workspace").asText());}
        result.put("qualityAccepted",false);return result;
    }
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
    private GameTool tool(String name,Map<String,Object> schema,Action action){return tool(name,schema,false,action);}
    private GameTool tool(String name,Map<String,Object> schema,boolean approval,Action action){return new GameTool(){public String name(){return name;}public Map<String,Object> jsonSchema(){return schema;}public boolean requiresApproval(){return approval;}public ToolResult execute(JsonNode args,ToolContext ctx){try{return ToolResult.success(mapper.writeValueAsString(action.apply(args,ctx)),name+" complete");}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return ToolResult.failure(e.getMessage()==null?"Production tool failed":e.getMessage());}}};}
    private JsonNode owned(String id,ToolContext ctx){var s=scope(ctx);var job=jobs.get(id);if(job.path("ownerRunId").asLong(-1)!=ctx.runId()||!job.path("specId").asText().equals(s.path("productionSpecId").asText()))throw new ToolException("Production belongs to another Agent Run");return job;}
    private Map<String,Object> productionSchema(Map<String,Object> extra,List<String> required){Map<String,Object> props=new HashMap<>(extra);props.put("productionId",Map.of("type","string"));var keys=new ArrayList<>(required);keys.add("productionId");return Map.of("type","object","properties",props,"required",keys,"additionalProperties",false);}
    @Bean GameTool productionValidate(){return tool("production_validate",productionSchema(Map.of(),List.of()),(a,c)->{
        String id=a.path("productionId").asText();owned(id,c);var s=scope(c);if(!active(c,s))throw new ToolException("Builder cancelled or timed out");var job=jobs.validate(id,c.runId());long end=System.currentTimeMillis()+10000;
        while(Set.of("QUEUED","RUNNING").contains(job.path("validationStatus").asText())&&System.currentTimeMillis()<end){if(!active(c,s)){jobs.cancel(id);throw new ToolException("Builder cancelled or timed out");}Thread.sleep(50);job=jobs.get(id);}if(!active(c,s)){jobs.cancel(id);throw new ToolException("Builder cancelled or timed out");}return compact(job);
    });}
    @Bean GameTool productionSourceRead(){return tool("production_source_read",productionSchema(Map.of("path",Map.of("type","string"),"offset",Map.of("type","integer"),"limit",Map.of("type","integer")),List.of("path")),(a,c)->{
        var job=owned(a.path("productionId").asText(),c);if(!job.hasNonNull("validationRunId"))throw new ToolException("Verify this production before reading a versioned source");var file=evidence.source(job.path("iteration").path("currentRunId").asText(job.path("validationRunId").asText()),a.path("path").asText());String text=file.path("text").asText();int offset=a.path("offset").asInt(0),limit=a.path("limit").asInt(2000);if(offset<0||offset>text.length()||limit<1||limit>3000)throw new ToolException("Invalid source range");return mapper.createObjectNode().put("path",file.path("path").asText()).put("sha256",file.path("sha256").asText()).put("offset",offset).put("totalCharacters",text.length()).put("text",text.substring(offset,Math.min(offset+limit,text.length())));
    });}
    @Bean GameTool productionChangePropose(){
        Map<String,Object> edit=Map.of("type","object","properties",Map.of("path",Map.of("type","string"),"beforeSha256",Map.of("type","string"),"afterText",Map.of("type","string")),"required",List.of("path","beforeSha256","afterText"),"additionalProperties",false);
        return tool("production_change_propose",productionSchema(Map.of("title",Map.of("type","string"),"edits",Map.of("type","array","items",edit)),List.of("title","edits")),true,(a,c)->{
            var job=owned(a.path("productionId").asText(),c);if(!job.hasNonNull("validationRunId"))throw new ToolException("Independent baseline verification required");String baseline=job.path("iteration").path("currentRunId").asText(job.path("validationRunId").asText());
            var edits=mapper.convertValue(a.path("edits"),new com.fasterxml.jackson.core.type.TypeReference<List<com.aigamelab.localtest.LocalChangeService.Edit>>(){});var p=changes.create(baseline,a.path("title").asText(),edits);return mapper.createObjectNode().put("localProposalId",p.id()).put("status",p.status());
        });
    }
}
