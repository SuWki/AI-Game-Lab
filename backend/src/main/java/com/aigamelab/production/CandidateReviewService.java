package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Manual observations are versioned separately from immutable packages and engine evidence. */
@Service
public class CandidateReviewService {
    public record Check(String criterionId,String outcome,String evidenceNote){}
    public record Request(int expectedRevision,String manifestHash,String reviewer,String summary,String decision,List<Check> checks){}
    private final ProductionCandidateService candidates;private final ProductionJobService jobs;private final ObjectMapper mapper;private final JdbcTemplate db;private final TransactionTemplate transactions;
    public CandidateReviewService(ProductionCandidateService candidates,ProductionJobService jobs,ObjectMapper mapper,JdbcTemplate db,PlatformTransactionManager manager){this.candidates=candidates;this.jobs=jobs;this.mapper=mapper;this.db=db;this.transactions=new TransactionTemplate(manager);}
    private ObjectNode read(String text){try{return (ObjectNode)mapper.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode spec(Map<String,byte[]> files,ObjectNode candidate){
        try{var envelope=mapper.readTree(files.get("source/specification.json"));if(!envelope.path("contentHash").equals(candidate.path("manifest").path("specHash")))throw BusinessException.conflict("Packaged specification differs from candidate");return envelope.path("content");}catch(java.io.IOException e){throw BusinessException.conflict("Packaged specification is unreadable");}
    }
    public List<ObjectNode> history(String id){return history(id,null);}
    public List<ObjectNode> history(String id,Integer beforeRevision){candidates.get(id);if(beforeRevision!=null&&beforeRevision<1)throw new IllegalArgumentException("Invalid history cursor");return db.query("SELECT record_json FROM candidate_review WHERE candidate_id=? AND revision<? ORDER BY revision DESC LIMIT 100",(rs,n)->read(rs.getString(1)),id,beforeRevision==null?Integer.MAX_VALUE:beforeRevision);}
    private ObjectNode latest(String id){return db.query("SELECT record_json FROM candidate_review WHERE candidate_id=? ORDER BY revision DESC LIMIT 1",(rs,n)->read(rs.getString(1)),id).stream().findFirst().orElse(null);}
    private ArrayNode checks(JsonNode spec,JsonNode previous){
        Map<String,JsonNode> outcomes=new HashMap<>();if(previous!=null)previous.path("checks").forEach(c->outcomes.put(c.path("criterionId").asText(),c));
        var result=mapper.createArrayNode();for(var criterion:spec.path("acceptanceCriteria")){
            String id=criterion.path("id").asText();var row=result.addObject().put("criterionId",id).put("requirementId",criterion.path("requirementId").asText()).put("expected",criterion.path("expected").asText()).put("method",criterion.path("method").asText()).put("evidenceKind","HUMAN_OBSERVATION");
            var saved=outcomes.get(id);row.put("outcome",saved==null?"NOT_TESTED":saved.path("outcome").asText()).put("evidenceNote",saved==null?"":saved.path("evidenceNote").asText());
        }return result;
    }
    private ObjectNode coverage(JsonNode spec,ArrayNode checks){
        int passed=0,failed=0;for(var check:checks){if("PASS".equals(check.path("outcome").asText()))passed++;if("FAIL".equals(check.path("outcome").asText()))failed++;}
        var value=mapper.createObjectNode().put("total",checks.size()).put("passed",passed).put("failed",failed).put("untested",checks.size()-passed-failed).put("fullyTested",passed+failed==checks.size()).put("allPassed",!checks.isEmpty()&&passed==checks.size());
        var requirements=value.putArray("requirements");for(var requirement:spec.path("requirements")){
            String id=requirement.path("id").asText();var associated=new ArrayList<JsonNode>();checks.forEach(c->{if(id.equals(c.path("requirementId").asText()))associated.add(c);});
            String status=associated.stream().anyMatch(c->"FAIL".equals(c.path("outcome").asText()))?"FAIL":!associated.isEmpty()&&associated.stream().allMatch(c->"PASS".equals(c.path("outcome").asText()))?"PASS":associated.stream().anyMatch(c->"PASS".equals(c.path("outcome").asText()))?"PARTIAL":"NOT_TESTED";
            var row=requirements.addObject().put("id",id).put("description",requirement.path("description").asText()).put("status",status);row.set("acceptanceIds",requirement.path("acceptanceIds"));
        }return value;
    }
    public ObjectNode view(String id){
        var candidate=candidates.get(id);var specification=spec(candidates.verifiedFiles(id),candidate);var latest=latest(id);var rows=checks(specification,latest);var coverage=coverage(specification,rows);
        var value=mapper.createObjectNode().put("candidateId",id).put("manifestHash",candidate.path("manifestHash").asText()).put("archiveSha256",candidate.path("archiveSha256").asText()).put("revision",latest==null?0:latest.path("revision").asInt()).put("decision",latest==null?"UNREVIEWED":latest.path("decision").asText());
        value.set("checks",rows);value.set("coverage",coverage);value.set("engineChecks",candidate.path("manifest").path("checks"));value.put("engineScope",candidate.path("manifest").path("scope").asText());
        if(latest!=null)value.set("latestReview",latest);
        var job=jobs.get(candidate.path("productionId").asText());boolean current=idCurrent(candidate,job)&&"REVIEW".equals(job.path("iteration").path("phase").asText());boolean accepted="ACCEPTED".equals(value.path("decision").asText());
        boolean eligible=current&&accepted&&coverage.path("allPassed").asBoolean();
        var gate=value.putObject("gate").put("candidateCurrent",current).put("filesVerified",true).put("eligibleForDelivery",eligible).put("humanAccepted",accepted);
        gate.put("reason",eligible?"Current fixed candidate explicitly accepted with complete manual specification coverage":!current?"Candidate is historical or production has unresolved work":!accepted?"Explicit human acceptance is missing or has been revised":"Manual specification coverage is incomplete");return value;
    }
    private boolean idCurrent(JsonNode candidate,JsonNode job){return candidate.path("validationRunId").asText().equals(job.path("iteration").path("currentRunId").asText());}
    private String text(String value,String name,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("Required "+name+" up to "+max+" characters");return value.trim();}
    /** Files and service snapshots are obtained before entering the single-connection transaction. */
    public synchronized ObjectNode save(String id,Request request){
        var candidate=candidates.get(id);var specification=spec(candidates.verifiedFiles(id),candidate);
        if(request.expectedRevision()<0||!candidate.path("manifestHash").asText().equals(request.manifestHash()))throw BusinessException.conflict("Review revision or manifest precondition differs");
        if(!Set.of("DRAFT","ACCEPTED","REJECTED").contains(request.decision()==null?"":request.decision()))throw new IllegalArgumentException("Invalid manual decision");
        var input=mapper.createObjectNode().put("reviewer",text(request.reviewer(),"reviewer",120)).put("summary",text(request.summary(),"summary",4000)).put("decision",request.decision());
        if(request.checks()==null||request.checks().size()!=specification.path("acceptanceCriteria").size())throw new IllegalArgumentException("All fixed specification criteria are required");
        Map<String,Check> supplied=new HashMap<>();for(var check:request.checks()){
            if(check==null||check.criterionId()==null||supplied.put(check.criterionId(),check)!=null||!Set.of("PASS","FAIL","NOT_TESTED").contains(check.outcome()==null?"":check.outcome()))throw new IllegalArgumentException("Invalid or duplicate manual criterion");
            if(!"NOT_TESTED".equals(check.outcome()))text(check.evidenceNote(),"observation for "+check.criterionId(),4000);
            else if(check.evidenceNote()!=null&&check.evidenceNote().length()>4000)throw new IllegalArgumentException("Observation too long");
        }
        var normalized=input.putArray("checks");for(var criterion:specification.path("acceptanceCriteria")){var check=supplied.remove(criterion.path("id").asText());if(check==null)throw new IllegalArgumentException("Unknown or missing specification criterion");normalized.addObject().put("criterionId",check.criterionId()).put("outcome",check.outcome()).put("evidenceNote",check.evidenceNote()==null?"":check.evidenceNote().trim());}
        if(!supplied.isEmpty()||input.toString().getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("Review exceeds fixed scope or size limit");
        if("ACCEPTED".equals(request.decision())&&!coverage(specification,checks(specification,input)).path("allPassed").asBoolean())throw BusinessException.conflict("Acceptance requires every specification criterion to pass with observations");
        var job=jobs.get(candidate.path("productionId").asText());if("ACCEPTED".equals(request.decision())&&(!idCurrent(candidate,job)||!"REVIEW".equals(job.path("iteration").path("phase").asText())))throw BusinessException.conflict("Only the current REVIEW candidate may be accepted");
        String inputHash=ProductionCandidateService.hash(input.toString().getBytes(StandardCharsets.UTF_8));
        transactions.executeWithoutResult(status->{
            var previous=latest(id);int revision=previous==null?0:previous.path("revision").asInt();
            if(previous!=null&&(previous.path("baseRevision").asInt()==request.expectedRevision()||revision==request.expectedRevision())&&previous.path("inputHash").asText().equals(inputHash))return;
            if(revision!=request.expectedRevision())throw BusinessException.conflict("Review changed; reload the latest revision");
            if("ACCEPTED".equals(request.decision()))assertCurrentInTransaction(candidate);
            var record=input.deepCopy().put("candidateId",id).put("manifestHash",candidate.path("manifestHash").asText()).put("archiveSha256",candidate.path("archiveSha256").asText()).put("revision",revision+1).put("baseRevision",revision).put("inputHash",inputHash).put("recordedAt",Instant.now().toString()).put("evidenceKind","HUMAN_OBSERVATION");
            record.put("reviewHash",ProductionCandidateService.hash(record.toString().getBytes(StandardCharsets.UTF_8)));db.update("INSERT INTO candidate_review VALUES (?,?,?)",id,revision+1,record.toString());
        });return view(id);
    }
    private void assertCurrentInTransaction(JsonNode candidate){
        String production=candidate.path("productionId").asText();var job=read(db.queryForObject("SELECT record_json FROM production_job WHERE id=?",String.class,production));
        if(job.path("iterationCancelled").asBoolean())throw BusinessException.conflict("Production was cancelled");
        if(job.hasNonNull("ownerRunId")){
            var owner=db.queryForList("SELECT status FROM run WHERE id=?",String.class,job.path("ownerRunId").asLong());
            if(!owner.isEmpty()&&Set.of("CANCELLED","FAILED","TIMEOUT").contains(owner.get(0)))throw BusinessException.conflict("Owning Agent stopped during review");
        }
        var last=db.queryForList("SELECT p.status FROM local_change_proposal p JOIN local_test_context c ON c.run_id=json_extract(p.proposal_json,'$.baseRunId') WHERE json_extract(c.binding_json,'$.targetId')=? ORDER BY p.rowid DESC LIMIT 1",String.class,"verify-"+production);
        if(!last.isEmpty()&&!Set.of("VALIDATED","REGRESSION_FAILED","REJECTED").contains(last.get(0)))throw BusinessException.conflict("Production has unresolved candidate work");
        var current=db.queryForList("SELECT json_extract(p.proposal_json,'$.validationRunId') FROM local_change_proposal p JOIN local_test_context c ON c.run_id=json_extract(p.proposal_json,'$.baseRunId') WHERE json_extract(c.binding_json,'$.targetId')=? AND p.status IN ('VALIDATED','REGRESSION_FAILED') ORDER BY p.rowid DESC LIMIT 1",String.class,"verify-"+production);
        String run=current.isEmpty()?job.path("validationRunId").asText():current.get(0);
        int pending=db.queryForObject("SELECT COUNT(*) FROM local_change_proposal p JOIN local_test_context c ON c.run_id=json_extract(p.proposal_json,'$.baseRunId') WHERE json_extract(c.binding_json,'$.targetId')=? AND p.status IN ('PENDING','APPLYING','VALIDATING')",Integer.class,"verify-"+production);
        if(pending>0||!candidate.path("validationRunId").asText().equals(run))throw BusinessException.conflict("Current candidate changed during review");
    }
    public byte[] receipt(String id){candidates.verifiedFiles(id);var record=latest(id);if(record==null)throw BusinessException.notFound("No manual review recorded");return record.toPrettyString().getBytes(StandardCharsets.UTF_8);}
}
