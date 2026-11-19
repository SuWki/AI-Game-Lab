package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.*;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.runtime.BudgetGuard;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** One task-scoped production and frozen cumulative limits, shared by all Builder Runs. */
@Service
public class BuilderSessionService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final RunRepository runs;
    private final Map<Long,ReentrantLock> locks=new ConcurrentHashMap<>();
    @Value("${agent.runtime.timeout-ms:900000}") private long timeoutMs=900000;
    @Value("${production.budget.prompt-usd-per-million:}") private String promptPrice="";
    @Value("${production.budget.completion-usd-per-million:}") private String completionPrice="";
    @Value("${production.budget.max-usd:}") private String maxCost="";
    public BuilderSessionService(JdbcTemplate db,ObjectMapper mapper,RunRepository runs){this.db=db;this.mapper=mapper;this.runs=runs;}
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalArgumentException("Invalid Builder binding",e);}}
    public boolean isBuilder(Task task){return "BUILDER".equals(read(task.constraintsJson()==null?"{}":task.constraintsJson()).path("agent_type").asText());}
    public ReentrantLock executionLock(long taskId){return locks.computeIfAbsent(taskId,k->new ReentrantLock());}
    private ObjectNode stored(long taskId){return db.query("SELECT record_json FROM builder_session WHERE task_id=?",(rs,n)->(ObjectNode)read(rs.getString(1)),taskId).stream().findFirst().orElse(null);}
    private void save(long taskId,ObjectNode state){db.update("INSERT INTO builder_session(task_id,record_json) VALUES (?,?) ON CONFLICT(task_id) DO UPDATE SET record_json=excluded.record_json",taskId,state.toString());}
    private BigDecimal price(String text,boolean positive){try{var value=new BigDecimal(text);if(value.signum()<0||(positive&&value.signum()==0)||value.scale()>12||value.precision()>24)throw new IllegalArgumentException();return value;}catch(Exception e){throw new IllegalArgumentException("Invalid Builder price or cost limit",e);}}
    private ObjectNode initialize(Task task,Run current){
        var state=stored(task.id());var binding=read(task.constraintsJson());
        if(!binding.path("productionSpecId").isTextual()||binding.path("productionSpecRevision").asInt()<1||binding.path("productionSourceTaskId").asLong()<1)throw BusinessException.conflict("Builder requires a bound specification");
        if(state==null){
            var first=runs.findByTask(task.id()).stream().min(Comparator.comparing(Run::id)).orElse(current);
            if(first.maxSteps()<1||first.tokenBudget()<1||timeoutMs<1)throw new IllegalArgumentException("Invalid cumulative Builder limits");
            state=mapper.createObjectNode().put("schemaVersion",1).put("taskId",task.id()).put("model",first.model()).put("stepLimit",first.maxSteps()).put("tokenLimit",first.tokenBudget());
            var start=first.startedAt()!=null?first.startedAt():first.createdAt();
            state.put("deadlineEpochMs",(start==null?System.currentTimeMillis():start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())+timeoutMs);
            for(String key:List.of("productionSpecId","productionSpecRevision","productionSourceTaskId"))state.set(key,binding.path(key));
            if(promptPrice.isBlank()!=completionPrice.isBlank()||(!maxCost.isBlank()&&promptPrice.isBlank()))throw new IllegalArgumentException("Cost limit requires both Builder token prices");
            if(!promptPrice.isBlank()){state.put("promptUsdPerMillion",price(promptPrice,false).toPlainString()).put("completionUsdPerMillion",price(completionPrice,false).toPlainString());}
            if(!maxCost.isBlank())state.put("maxCostUsd",price(maxCost,true).toPlainString());
            var legacy=db.queryForList("SELECT p.id FROM production_job p JOIN run r ON r.id=json_extract(p.record_json,'$.ownerRunId') WHERE r.task_id=?",String.class,task.id());
            if(legacy.size()>1)throw BusinessException.conflict("Multiple legacy productions require manual selection");
            if(!legacy.isEmpty())state.put("productionId",legacy.get(0));
            save(task.id(),state);
        }
        for(String key:List.of("productionSpecId","productionSpecRevision","productionSourceTaskId"))if(!state.path(key).equals(binding.path(key)))throw BusinessException.conflict("Builder specification changed; create a new task");
        if(!Objects.equals(state.path("model").asText(),current.model()))throw BusinessException.conflict("Builder model changed; create a new task");
        return state;
    }
    private void idle(long taskId,Long except){
        if(db.queryForObject("SELECT COUNT(*) FROM run WHERE task_id=? AND id<>? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')",Integer.class,taskId,except==null?-1:except)>0)throw BusinessException.conflict("Builder task already has an active Run");
    }
    /** Admission is transactional; insertion and session initialization cannot leave a partial lease. */
    @Transactional public synchronized Run admit(Task task,Supplier<Run> create){
        idle(task.id(),null);var run=create.get();var state=initialize(task,run);
        var verdict=verdict(state,true);if(verdict.hit())throw BusinessException.conflict(verdict.reason());
        return run;
    }
    @Transactional public synchronized void claim(Task task,Run run){
        idle(task.id(),run.id());var state=initialize(task,run);
        if(state.path("activeRunId").asLong(-1)!=run.id()){
            if(state.hasNonNull("productionId")){
                String target="verify-"+state.path("productionId").asText();
                if(db.queryForObject("SELECT COUNT(*) FROM local_change_proposal p JOIN local_test_context c ON c.run_id=json_extract(p.proposal_json,'$.baseRunId') WHERE json_extract(c.binding_json,'$.targetId')=? AND p.status IN ('PENDING','APPLYING','VALIDATING')",Integer.class,target)>0)throw BusinessException.conflict("Resolve previous production proposals before handoff");
                if(db.queryForObject("SELECT COUNT(*) FROM local_test_run t JOIN local_test_context c ON c.run_id=t.id JOIN run r ON r.id=json_extract(c.binding_json,'$.ownerRunId') WHERE r.task_id=? AND json_extract(t.record_json,'$.status') IN ('QUEUED','RUNNING')",Integer.class,task.id())>0)throw BusinessException.conflict("Previous local execution is still active");
            }
            state.put("activeRunId",run.id());save(task.id(),state);
        }
    }
    public ObjectNode snapshot(long taskId){var state=stored(taskId);return state==null?null:usage(state);}
    public int tokenBudgetForNewRun(long taskId, int defaultBudget) {
        var state = stored(taskId);
        return state == null ? defaultBudget : state.path("tokenLimit").asInt(defaultBudget);
    }
    private ObjectNode usage(ObjectNode state){
        var out=state.deepCopy();long task=state.path("taskId").asLong();
        out.put("tokensUsed",db.queryForObject("SELECT COALESCE(SUM(MAX(r.tokens_used,COALESCE((SELECT SUM(s.prompt_tokens+s.completion_tokens) FROM step s WHERE s.run_id=r.id),0))),0) FROM run r WHERE r.task_id=?",Long.class,task));
        out.put("stepsUsed",db.queryForObject("SELECT COUNT(*) FROM step s JOIN run r ON r.id=s.run_id WHERE r.task_id=?",Long.class,task));
        long prompt=db.queryForObject("SELECT COALESCE(SUM(s.prompt_tokens),0) FROM step s JOIN run r ON r.id=s.run_id WHERE r.task_id=?",Long.class,task);
        long completion=db.queryForObject("SELECT COALESCE(SUM(s.completion_tokens),0) FROM step s JOIN run r ON r.id=s.run_id WHERE r.task_id=?",Long.class,task);
        out.put("promptTokens",prompt).put("completionTokens",completion);
        out.put("unsettledUsageTurns",db.queryForObject("SELECT COUNT(*) FROM step s JOIN run r ON r.id=s.run_id WHERE r.task_id=? AND s.phase='LLM_CALL'",Long.class,task));
        if(state.has("promptUsdPerMillion")){
            var cost=new BigDecimal(state.path("promptUsdPerMillion").asText()).multiply(BigDecimal.valueOf(prompt)).add(new BigDecimal(state.path("completionUsdPerMillion").asText()).multiply(BigDecimal.valueOf(completion))).movePointLeft(6);
            out.put("estimatedCostUsd",cost.toPlainString()).put("pricingStatus","ESTIMATE_REPORTED_USAGE");
        }else out.putNull("estimatedCostUsd").put("pricingStatus","UNCONFIGURED");
        return out;
    }
    private BudgetGuard.Verdict verdict(ObjectNode state,boolean beforeModel){
        var used=usage(state);
        if(beforeModel&&used.path("stepsUsed").asLong()>=state.path("stepLimit").asLong())return BudgetGuard.Verdict.stop(RunStatus.FAILED,"Cumulative Builder step budget exhausted");
        if(used.path("tokensUsed").asLong()>=state.path("tokenLimit").asLong())return BudgetGuard.Verdict.stop(RunStatus.FAILED,"Cumulative Builder token budget exhausted");
        if(state.has("maxCostUsd")&&new BigDecimal(used.path("estimatedCostUsd").asText()).compareTo(new BigDecimal(state.path("maxCostUsd").asText()))>=0)return BudgetGuard.Verdict.stop(RunStatus.FAILED,"Cumulative Builder estimated cost budget exhausted");
        if(System.currentTimeMillis()>=state.path("deadlineEpochMs").asLong())return BudgetGuard.Verdict.stop(RunStatus.TIMEOUT,"Cumulative Builder deadline exceeded");
        return BudgetGuard.Verdict.pass();
    }
    public BudgetGuard.Verdict check(Run run,boolean beforeModel){var state=stored(run.taskId());if(state==null)return BudgetGuard.Verdict.pass();if(state.path("activeRunId").asLong(-1)!=run.id())return BudgetGuard.Verdict.stop(RunStatus.FAILED,"Builder session is owned by another Run");return verdict(state,beforeModel);}
    public void assertOwner(long runId,String productionId){
        var run=runs.findById(runId).orElseThrow();var state=stored(run.taskId());
        if(!"RUNNING".equals(run.status())||state==null||state.path("activeRunId").asLong(-1)!=runId||!state.path("productionId").asText().equals(productionId))throw BusinessException.conflict("Production belongs to another active Builder session");
        var check=check(run,false);if(check.hit())throw BusinessException.conflict(check.reason());
    }
    public void bind(long runId,String productionId){
        var run=runs.findById(runId).orElseThrow();var state=stored(run.taskId());
        if(state==null||state.path("activeRunId").asLong(-1)!=runId)throw BusinessException.conflict("Builder session lease required");
        if(state.hasNonNull("productionId")&&!state.path("productionId").asText().equals(productionId))throw BusinessException.conflict("Builder session already has a production");
        state.put("productionId",productionId);save(run.taskId(),state);
    }
}
