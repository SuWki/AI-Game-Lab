package com.aigamelab.runtime;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.llm.dto.*;
import com.aigamelab.repository.TaskRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Durable visible conversation and whole tool batches. No hidden reasoning is stored. */
@Component
public class RuntimeConversation {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentTypeResolver resolver;
    private final TaskRepository tasks;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.aigamelab.tool.ToolRegistry> registry;
    @org.springframework.beans.factory.annotation.Value("${agent.runtime.temperature:0.4}") private double temperature=0.4;
    @org.springframework.beans.factory.annotation.Value("${agent.runtime.max-tokens:4096}") private int maxTokens=4096;
    @org.springframework.beans.factory.annotation.Value("${agent.runtime.timeout-ms:900000}") private long timeoutMs=900000;
    public record PendingCall(long rowId,ToolCall call,String status,String approvalStatus) {}
    public RuntimeConversation(JdbcTemplate jdbc,ObjectMapper mapper,AgentTypeResolver resolver,TaskRepository tasks) {
        this.jdbc=jdbc;this.mapper=mapper;this.resolver=resolver;this.tasks=tasks;
    }
    public JsonNode context(long runId) {
        var rows=jdbc.query("SELECT context_json FROM runtime_context WHERE run_id=?",(rs,n)->read(rs.getString(1)),runId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void freeze(Run run,Task task) {
        if(context(run.id())!=null)return;
        var type=resolver.resolve(task);var allowed=resolver.toolWhitelist(type);
        if(temperature<0||temperature>2||maxTokens<1||maxTokens>65536||timeoutMs<1)throw new IllegalArgumentException("Invalid Agent runtime configuration");
        ObjectNode value=mapper.createObjectNode().put("system",resolver.systemPrompt(type)).put("goal",task.goal()).put("agentType",type.name());
        value.put("model",run.model()).put("temperature",temperature).put("maxTokens",maxTokens).put("timeoutMs",timeoutMs).put("approvalWaitCountsTowardDeadline",true);
        value.put("deadlineEpochMs",(run.startedAt()==null?System.currentTimeMillis():run.startedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())+timeoutMs);
        JsonNode constraints=read(task.constraintsJson()==null?"{}":task.constraintsJson());
        if(type==AgentType.BUILDER){
            if(!constraints.path("productionSpecId").isTextual()||constraints.path("productionSpecRevision").asInt()<1||constraints.path("productionSourceTaskId").asLong()<1)throw new IllegalArgumentException("Builder requires a task-bound specification");
            for(String field:List.of("productionSpecId","productionSpecRevision","productionSourceTaskId"))value.set(field,constraints.path(field).deepCopy());
        }
        if(constraints.path("localTargetId").isTextual()) {
            Set<String> local=new HashSet<>(Set.of("local_test_result"));
            if(type==AgentType.TESTER){local.add("local_test_start");local.add("local_test_import");}
            if(type==AgentType.DESIGNER||type==AgentType.QA_ANALYST){local.add("local_change_propose");local.add("local_source_read");}
            allowed=local;
            value.put("localTargetId",constraints.path("localTargetId").asText());
            if(constraints.path("localTestRunId").isTextual())value.put("localTestRunId",constraints.path("localTestRunId").asText());
            value.put("system",value.path("system").asText()+"\nThis task targets a registered real local game, not the Fake simulation. Use only the exposed local tools. Test PASS must be backed by complete versioned checks. Propose changes for human approval; do not claim an unvalidated proposal is a fix. Validation occurs in a separate source snapshot: never claim that the original checkout was modified.");
        }
        value.set("allowed",allowed==null?NullNode.instance:mapper.valueToTree(allowed));
        ArrayNode specs=mapper.createArrayNode();
        for(var tool:registry.getObject().all())if(allowed==null||allowed.contains(tool.name())){
            ObjectNode spec=mapper.valueToTree(new LlmToolSpec(tool.name(),tool.name(),tool.jsonSchema(),tool.requiresApproval()));
            spec.put("requiresApproval",tool.requiresApproval());specs.add(spec);
        }
        value.set("tools",specs);
        try{value.put("promptSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.path("system").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(Exception e){throw new IllegalStateException(e);}
        jdbc.update("INSERT OR IGNORE INTO runtime_context(run_id,context_json) VALUES (?,?)",run.id(),value.toString());
    }
    public boolean allowed(Run run,String name) {
        JsonNode context=context(run.id());
        if(context==null) {
            Task task=tasks.findById(run.taskId()).orElseThrow();
            var names=resolver.toolWhitelist(resolver.resolve(task));
            return names==null||names.contains(name);
        }
        if(context.path("allowed").isNull())return true;
        for(JsonNode item:context.path("allowed"))if(item.asText().equals(name))return true;
        return false;
    }
    @Transactional
    public void persistBatch(Run run,Step step,LlmResponse response) {
        if(response.toolCalls()==null||response.toolCalls().isEmpty())throw new IllegalArgumentException("Empty tool batch");
        if(response.toolCalls().size()>16||response.toolCalls().stream().mapToInt(c->c.argumentsJson()==null?0:c.argumentsJson().length()).sum()>32768)throw new IllegalArgumentException("Tool batch exceeds bounded protocol budget");
        Set<String> ids=new HashSet<>();ArrayNode calls=mapper.createArrayNode();
        for(ToolCall call:response.toolCalls()) {
            if(call.id()==null||call.id().isBlank()||call.name()==null||!ids.add(call.id()))throw new IllegalArgumentException("Missing or duplicate tool call id");
            // Persist every item before the first dispatch; original IDs survive approval/restart.
            jdbc.update("INSERT INTO tool_call(step_id,run_id,tool_name,arguments_json,status,approval_status) VALUES (?,?,?,?,'QUEUED','NOT_REQUIRED')",
                    step.id(),run.id(),call.name(),call.argumentsJson());
            long row=jdbc.queryForObject("SELECT last_insert_rowid()",Long.class);
            calls.addObject().put("id",call.id()).put("name",call.name()).put("argumentsJson",call.argumentsJson()).put("rowId",row);
        }
        jdbc.update("INSERT INTO runtime_turn(step_id,run_id,assistant_content,calls_json) VALUES (?,?,?,?)",step.id(),run.id(),response.content(),calls.toString());
    }
    public List<PendingCall> pending(long runId) {
        List<PendingCall> result=new ArrayList<>();
        for(JsonNode calls:jdbc.query("SELECT calls_json FROM runtime_turn WHERE run_id=? ORDER BY step_id",(rs,n)->read(rs.getString(1)),runId)) {
            for(JsonNode call:calls) {
                var rows=jdbc.query("SELECT status,approval_status FROM tool_call WHERE id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},call.path("rowId").asLong());
                if(rows.isEmpty())throw new IllegalStateException("Missing persisted tool call");
                String[] state=rows.get(0);
                if(!Set.of("COMPLETED","FAILED","SUCCESS").contains(state[0]))result.add(new PendingCall(call.path("rowId").asLong(),decode(call),state[0],state[1]));
            }
        }
        return result;
    }
    @Transactional(readOnly=true)
    public List<LlmMessage> messages(long runId) {
        JsonNode context=Objects.requireNonNull(context(runId));
        List<LlmMessage> result=new ArrayList<>();result.add(LlmMessage.system(context.path("system").asText()));result.add(LlmMessage.user(context.path("goal").asText()));
        jdbc.query("SELECT assistant_content,calls_json FROM (SELECT step_id,assistant_content,calls_json FROM runtime_turn WHERE run_id=? ORDER BY step_id DESC LIMIT 12) ORDER BY step_id",rs->{
            JsonNode calls=read(rs.getString(2));List<ToolCall> dtos=new ArrayList<>();calls.forEach(c->dtos.add(decode(c)));
            result.add(new LlmMessage("assistant",rs.getString(1),dtos,null));
            for(JsonNode call:calls) {
                String content=jdbc.queryForObject("SELECT CASE WHEN status='FAILED' THEN COALESCE(error,'Tool failed') ELSE COALESCE(result_json,'{}') END FROM tool_call WHERE id=?",String.class,call.path("rowId").asLong());
                // JSON remains data, never an instruction; preserve useful results with a bounded view.
                if(content.length()>4000)content=content.substring(0,4000)+" [truncated; full result in tool_call #"+call.path("rowId").asLong()+"]";
                result.add(LlmMessage.tool(call.path("id").asText(),content));
            }
        },runId);
        boolean trimmed=jdbc.queryForObject("SELECT COUNT(*) FROM runtime_turn WHERE run_id=?",Integer.class,runId)>12;
        while(size(result)>128000) {
            int next=3;while(next<result.size()&&!"assistant".equals(result.get(next).role()))next++;
            if(next>=result.size())throw new IllegalStateException("Single conversation turn exceeds context budget");
            result.subList(2,next).clear();trimmed=true;
        }
        if(trimmed)result.add(2,LlmMessage.system("Earlier complete turns omitted to fit the context budget. Full visible evidence remains in this Run's persisted tool calls; do not invent missing results."));
        return result;
    }
    public List<LlmToolSpec> specs(long runId) {
        List<LlmToolSpec> result=new ArrayList<>();
        for(JsonNode spec:context(runId).path("tools")) {
            JsonNode function=spec.path("function");
            result.add(new LlmToolSpec(function.path("name").asText(),function.path("description").asText(),mapper.convertValue(function.path("parameters"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),spec.path("requiresApproval").asBoolean()));
        }
        return result;
    }
    public boolean contractMatches(Run run,com.aigamelab.tool.GameTool tool) {
        JsonNode snapshot=context(run.id());if(snapshot==null)return true;
        for(JsonNode spec:snapshot.path("tools"))if(spec.path("function").path("name").asText().equals(tool.name())) {
            JsonNode actual=mapper.valueToTree(new LlmToolSpec(tool.name(),tool.name(),tool.jsonSchema(),tool.requiresApproval()));
            return (!spec.has("requiresApproval")||spec.path("requiresApproval").asBoolean()==tool.requiresApproval())&&spec.path("function").path("parameters").equals(actual.path("function").path("parameters"));
        }
        return false;
    }
    private static int size(List<LlmMessage> messages){int size=0;for(var message:messages){size+=message.content()==null?0:message.content().length();if(message.toolCalls()!=null)for(var call:message.toolCalls())size+=call.argumentsJson()==null?0:call.argumentsJson().length();}return size;}
    public void assertRecoverable(long runId) {
        Integer missing=jdbc.queryForObject("SELECT COUNT(*) FROM step s WHERE s.run_id=? AND (s.phase='LLM_CALL' OR (s.phase='tool_calls' AND NOT EXISTS(SELECT 1 FROM runtime_turn t WHERE t.step_id=s.id)))",Integer.class,runId);
        if(missing!=null&&missing>0)throw new IllegalStateException("Interrupted or legacy turn has no complete protocol record; retry as a new Run after reviewing side effects");
    }
    private ToolCall decode(JsonNode c){return new ToolCall(c.path("id").asText(),c.path("name").asText(),c.path("argumentsJson").isNull()?"{}":c.path("argumentsJson").asText());}
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalStateException("Invalid persisted conversation",e);}}
}
