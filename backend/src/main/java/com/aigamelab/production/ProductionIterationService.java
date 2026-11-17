package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.aigamelab.localtest.LocalChangeService.Proposal;
import com.aigamelab.localtest.LocalTestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Single-instance coordination; the existing immutable proposals are the durable attempt ledger. */
@Service
public class ProductionIterationService {
    private static final Set<String> ACTIVE=Set.of("PENDING","APPLYING","VALIDATING");
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final LocalTestService tests;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.aigamelab.localtest.LocalChangeService> changes;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.localtest.LocalEvidenceService evidence;
    @org.springframework.beans.factory.annotation.Autowired private BuilderSessionService sessions;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.repository.RunRepository runs;
    public ProductionIterationService(JdbcTemplate db,ObjectMapper mapper,LocalTestService tests){this.db=db;this.mapper=mapper;this.tests=tests;}
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<JsonNode> attempts(String id){return db.query("""
        SELECT p.proposal_json FROM local_change_proposal p JOIN local_test_context c
        ON c.run_id=json_extract(p.proposal_json,'$.baseRunId')
        WHERE json_extract(c.binding_json,'$.targetId')=? ORDER BY p.rowid
        """,(rs,n)->read(rs.getString(1)),"verify-"+id);}
    private JsonNode production(String baseRunId){
        var ids=db.queryForList("SELECT json_extract(binding_json,'$.targetId') FROM local_test_context WHERE run_id=?",String.class,baseRunId);
        if(ids.isEmpty()||ids.get(0)==null||!ids.get(0).startsWith("verify-"))return null;
        var records=db.query("SELECT record_json FROM production_job WHERE id=?",(rs,n)->read(rs.getString(1)),ids.get(0).substring(7));
        return records.isEmpty()?null:records.get(0);
    }
    private String stoppedOwner(JsonNode job){
        if(!job.hasNonNull("ownerRunId"))return "";
        var states=db.queryForList("SELECT status FROM run WHERE id=?",String.class,job.path("ownerRunId").asLong());
        return !states.isEmpty()&&Set.of("CANCELLED","FAILED","TIMEOUT").contains(states.get(0))?states.get(0):"";
    }
    public ObjectNode snapshot(JsonNode job){
        var result=mapper.createObjectNode().put("repairLimit",5).put("currentRunId",job.path("validationRunId").asText());
        var history=attempts(job.path("id").asText()).stream().map(p->mapper.<JsonNode>valueToTree(changes.getObject().get(p.path("id").asText()))).toList();result.put("repairCount",history.size());var entries=result.putArray("attempts");
        String lastStatus="",lastExecution="";boolean active=false;
        for(var p:history){
            lastStatus=p.path("status").asText();active|=ACTIVE.contains(lastStatus);
            var entry=entries.addObject().put("proposalId",p.path("id").asText()).put("status",lastStatus).put("baseRunId",p.path("baseRunId").asText());
            lastExecution=p.hasNonNull("validationRunId")?tests.get(p.path("validationRunId").asText()).status():"";
            if(p.hasNonNull("validationRunId"))entry.set("validationRunId",p.get("validationRunId"));
            if(Set.of("VALIDATED","REGRESSION_FAILED").contains(lastStatus)&&p.hasNonNull("validationRunId"))result.set("currentRunId",p.get("validationRunId"));
        }
        if(!result.path("currentRunId").asText().isBlank()){
            var current=tests.get(result.path("currentRunId").asText());
            result.put("currentStatus",current.status()).put("currentDecision",evidence.assessment(current).path("decision").asText()).put("currentWorkspace",current.target().workingDirectory());
            if(current.evidence()!=null)result.set("currentChecks",current.evidence().path("checks"));
        }
        String phase,reason;
        String build=job.path("status").asText();
        if(job.path("iterationCancelled").asBoolean()||!stoppedOwner(job).isBlank()){phase="CANCELLED";reason="Production or owning Agent stopped; pending proposals cannot be approved";}
        else if(!"BUILT".equals(build)){phase=Set.of("PREPARING","QUEUED","RUNNING").contains(build)?"IMPLEMENTING":Set.of("CANCELLED","INTERRUPTED").contains(build)?build:"FAILED";reason="Build status: "+build;}
        else if(active){phase="PENDING".equals(lastStatus)?"WAITING_INPUT":"VERIFYING";reason="Proposal requires a decision or registered regression completion";}
        else if("INTERRUPTED".equals(lastExecution)){phase="INTERRUPTED";reason="Interrupted candidate verification requires inspection; not replayed";}
        else if(Set.of("FAILED","INCONCLUSIVE","INTERRUPTED").contains(lastStatus)){phase="INTERRUPTED".equals(lastStatus)?"INTERRUPTED":history.size()>=5?"FAILED":"WAITING_INPUT";reason="Latest proposal did not produce complete verification; inspect its evidence";}
        else if(!job.hasNonNull("validationRunId")){phase="VERIFYING";reason="Independent baseline verification has not been started";}
        else {
            var run=tests.get(result.path("currentRunId").asText());String state=run.status();
            if(Set.of("QUEUED","RUNNING").contains(state)){phase="VERIFYING";reason="Independent verification running";}
            else if("INTERRUPTED".equals(state)||"INTERRUPTED".equals(lastStatus)){phase="INTERRUPTED";reason="Interrupted external work requires inspection; not replayed";}
            else if("PASS".equals(evidence.assessment(run).path("decision").asText())){phase="REVIEW";reason="Engine checks passed; human quality acceptance and delivery remain separate";}
            else if(history.size()>=5){phase="FAILED";reason="Repair proposal budget exhausted";}
            else if("FAIL".equals(evidence.assessment(run).path("decision").asText())){phase="IMPLEMENTING";reason="Complete failing checks are available for the next repair";}
            else{phase="WAITING_INPUT";reason="Incomplete verification or unsuccessful preparation requires inspection";}
        }
        return result.put("phase",phase).put("reason",reason);
    }
    /** Called inside the proposal creation transaction, before persisting a new proposal. */
    public void checkProposal(String baseRunId){
        var job=production(baseRunId);if(job==null)return;
        var state=snapshot(job);if(job.path("iterationCancelled").asBoolean()||!stoppedOwner(job).isBlank())throw BusinessException.conflict("Production cancelled or owning Agent stopped");
        if(state.path("repairCount").asInt()>=5)throw BusinessException.conflict("Production repair budget exhausted (5 proposals)");
        for(var p:attempts(job.path("id").asText()))if(ACTIVE.contains(p.path("status").asText()))throw BusinessException.conflict("Production already has an unresolved proposal");
        if(!state.path("currentRunId").asText().equals(baseRunId))throw BusinessException.conflict("Use the current production candidate baseline");
    }
    public synchronized void checkApproval(Proposal proposal){
        var job=production(proposal.baseRunId());if(job!=null&&(job.path("iterationCancelled").asBoolean()||!stoppedOwner(job).isBlank()))throw BusinessException.conflict("Production cancelled or owning Agent stopped; proposal cannot be approved");
        if(job!=null&&job.hasNonNull("ownerRunId")){
            var owner=runs.findById(job.path("ownerRunId").asLong()).orElseThrow();
            if("WAITING_APPROVAL".equals(owner.status())){var budget=sessions.check(owner,false);if(budget.hit())throw BusinessException.conflict(budget.reason());}
        }
    }
    /** Holds the same lock as cancellation until the child execution is durably linked. */
    public synchronized Proposal registerValidation(Proposal proposal,java.util.function.Supplier<Proposal> register){checkApproval(proposal);return register.get();}
    public synchronized void cancel(String id){
        db.update("UPDATE production_job SET record_json=json_set(record_json,'$.iterationCancelled',json('true')) WHERE id=?",id);
        for(var p:attempts(id))if(p.hasNonNull("validationRunId"))tests.cancel(p.path("validationRunId").asText());
    }
}
