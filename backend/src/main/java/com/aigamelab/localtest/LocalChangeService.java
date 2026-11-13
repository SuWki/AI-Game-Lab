package com.aigamelab.localtest;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Human-reviewed source changes are applied only to a managed source snapshot, never the original game. */
@Service
public class LocalChangeService {
    public record Edit(String path,String beforeSha256,String beforeText,String afterText) {
        public Edit(String path,String beforeSha256,String afterText){this(path,beforeSha256,null,afterText);}
    }
    public record Proposal(String id,String status,String baseRunId,String title,List<Edit> edits,String workspace,String validationRunId,String error) {}
    private final JdbcTemplate db;private final ObjectMapper mapper;private final LocalTestService tests;private final LocalEvidenceService evidence;private final Path workspaceRoot;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.aigamelab.runtime.RunControlService> runControl;
    public LocalChangeService(JdbcTemplate db,ObjectMapper mapper,LocalTestService tests,LocalEvidenceService evidence,@Value("${local-tests.workspace-root:./data/local-workspaces}")String workspaceRoot) {
        this.db=db;this.mapper=mapper;this.tests=tests;this.evidence=evidence;this.workspaceRoot=Path.of(workspaceRoot);
        for(Proposal p:db.query("SELECT proposal_json FROM local_change_proposal WHERE status='APPLYING'",(rs,n)->read(rs.getString(1))))
            save(new Proposal(p.id(),"INTERRUPTED",p.baseRunId(),p.title(),p.edits(),p.workspace(),p.validationRunId(),"Interrupted while preparing workspace; inspect before creating a new proposal"));
    }
    public List<Proposal> list(){return db.query("SELECT proposal_json FROM local_change_proposal ORDER BY rowid DESC LIMIT 100",(rs,n)->read(rs.getString(1))).stream().map(p->get(p.id())).toList();}
    public Proposal create(String baseRunId,String title,List<Edit> edits) {
        var base=tests.get(baseRunId);var assessment=evidence.assessment(base);
        if("INCONCLUSIVE".equals(assessment.path("decision").asText()))throw BusinessException.conflict("Versioned structured baseline evidence required");
        if(title==null||title.isBlank()||edits==null||edits.isEmpty()||edits.size()>20)throw new IllegalArgumentException("A title and 1..20 file edits are required");
        evidence.verify(baseRunId);
        var binding=evidence.binding(baseRunId);Set<String> paths=new HashSet<>();List<Edit> checked=new ArrayList<>();int chars=0;
        try {
            Path root=Path.of(base.target().workingDirectory());
            for(Edit edit:edits) {
                Path file=LocalEvidenceService.resolve(root,edit.path());String name=root.toRealPath().relativize(file).toString().replace('\\','/');
                boolean allowed=false;
                for(String declared:base.target().versionFiles()) {
                    Path selected=LocalEvidenceService.resolve(root,declared);
                    if(file.equals(selected)||(Files.isDirectory(selected)&&file.startsWith(selected)))allowed=true;
                }
                if(!allowed||!paths.add(name)||edit.afterText()==null)throw new IllegalArgumentException("Edit must target a unique declared source file");
                chars+=edit.afterText().getBytes(StandardCharsets.UTF_8).length;if(chars>1048576)throw new IllegalArgumentException("Proposal exceeds 1 MiB of UTF-8 text");
                String expected=binding.path("source").path(name).isMissingNode()?null:binding.path("source").path(name).asText();
                if(!Objects.equals(expected,edit.beforeSha256()))throw BusinessException.conflict("Edit base hash does not match tested source: "+name);
                if(expected==null&&Files.exists(file))throw BusinessException.conflict("Existing file was not part of the tested source");
                if(Files.exists(file)&&Files.size(file)>1048576)throw new IllegalArgumentException("Edited source file exceeds 1 MiB");
                checked.add(new Edit(name,expected,Files.exists(file)?Files.readString(file,StandardCharsets.UTF_8):null,edit.afterText()));
            }
        }catch(java.io.IOException e){throw new IllegalArgumentException("Cannot validate proposal paths",e);}
        var p=new Proposal(UUID.randomUUID().toString(),"PENDING",baseRunId,title,List.copyOf(checked),null,null,null);
        save(p);return p;
    }
    public Proposal approve(String id) {
        Proposal p=require(id);
        if(db.update("UPDATE local_change_proposal SET status='APPLYING' WHERE id=? AND status='PENDING'",id)!=1)throw BusinessException.conflict("Proposal already decided");
        Path workspace=null;String validationId=null;
        try {
            evidence.verify(p.baseRunId());var base=tests.get(p.baseRunId());var binding=evidence.binding(p.baseRunId());
            Files.createDirectories(workspaceRoot);Path parent=workspaceRoot.toRealPath();workspace=Files.createDirectory(parent.resolve(p.id()));
            var source=binding.path("source");var entries=source.fields();
            while(entries.hasNext()) {
                var entry=entries.next();Path from=LocalEvidenceService.resolve(Path.of(base.target().workingDirectory()),entry.getKey());
                Path to=LocalEvidenceService.resolve(workspace,entry.getKey());Files.createDirectories(to.getParent());Files.copy(from,to);
                if(!entry.getValue().asText().equals(LocalEvidenceService.fileHash(to)))throw BusinessException.conflict("Source changed while copying workspace");
            }
            for(Edit edit:p.edits()) {
                Path file=LocalEvidenceService.resolve(workspace,edit.path());
                if(edit.beforeSha256()!=null&&!edit.beforeSha256().equals(LocalEvidenceService.fileHash(file)))throw BusinessException.conflict("Workspace base mismatch");
                Files.createDirectories(file.getParent());Files.writeString(file,edit.afterText(),StandardCharsets.UTF_8);
            }
            List<String> command=new ArrayList<>();String original=Path.of(base.target().workingDirectory()).toRealPath().toString().replace('\\','/').toLowerCase(Locale.ROOT);
            for(String arg:base.target().command()) {
                if(arg.replace('\\','/').toLowerCase(Locale.ROOT).contains(original))throw new IllegalArgumentException("Validation command references original working directory; register relative arguments or {workspace}");
                command.add(arg);
            }
            var owners=db.queryForList("SELECT run_id FROM tool_call WHERE approval_status='PENDING' AND CASE WHEN json_valid(result_json) THEN json_extract(result_json,'$.localProposalId') END=?",Long.class,p.id());
            Long owner=owners.isEmpty()?null:owners.get(0);
            var target=base.target();var run=tests.startWorkspace(new LocalTestService.Target(target.id(),target.name(),workspace.toString(),command,target.timeoutSeconds(),target.resultPath(),target.versionFiles(),target.artifactFiles()),owner);
            validationId=run.id();p=new Proposal(p.id(),"VALIDATING",p.baseRunId(),p.title(),p.edits(),workspace.toString(),validationId,null);
        }catch(Exception e){p=new Proposal(p.id(),"FAILED",p.baseRunId(),p.title(),p.edits(),workspace==null?null:workspace.toString(),validationId,e.getClass().getSimpleName()+": "+e.getMessage());}
        save(p);return get(p.id());
    }
    public Proposal reject(String id) {
        Proposal p=require(id);
        if(db.update("UPDATE local_change_proposal SET status='REJECTED' WHERE id=? AND status='PENDING'",id)!=1)throw BusinessException.conflict("Proposal already decided");
        p=new Proposal(p.id(),"REJECTED",p.baseRunId(),p.title(),p.edits(),null,null,null);save(p);settle(p);return p;
    }
    public Proposal get(String id) {
        Proposal p=require(id);
        if("APPLYING".equals(db.queryForObject("SELECT status FROM local_change_proposal WHERE id=?",String.class,id)))return new Proposal(p.id(),"APPLYING",p.baseRunId(),p.title(),p.edits(),p.workspace(),p.validationRunId(),p.error());
        if(!"VALIDATING".equals(p.status())){if(!"PENDING".equals(p.status()))settle(p);return p;}
        var run=tests.get(p.validationRunId());if(Set.of("QUEUED","RUNNING").contains(run.status()))return p;
        var assessment=evidence.assessment(run);String decision=assessment.path("decision").asText(),status="INCONCLUSIVE";String reason=null;
        if("FAIL".equals(decision))status="REGRESSION_FAILED";
        else if("PASS".equals(decision)) {
            Set<String> before=new HashSet<>(),after=new HashSet<>();
            tests.get(p.baseRunId()).evidence().path("checks").forEach(c->before.add(c.path("id").asText()));
            run.evidence().path("checks").forEach(c->after.add(c.path("id").asText()));
            if(after.containsAll(before))status="VALIDATED";else reason="Passing report omitted baseline checks; absence is not proof of a fix";
        } else reason="Validation execution "+run.status()+": "+(run.error()==null?assessment.path("reason").asText("Validation evidence incomplete"):run.error());
        p=new Proposal(p.id(),status,p.baseRunId(),p.title(),p.edits(),p.workspace(),p.validationRunId(),reason);save(p);settle(p);return p;
    }
    public void onTestFinished(String runId) {
        for(String id:db.queryForList("SELECT id FROM local_change_proposal WHERE status='VALIDATING' AND json_extract(proposal_json,'$.validationRunId')=?",String.class,runId))get(id);
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverOnStartup(){list();}
    private void settle(Proposal proposal) {
        if(Set.of("PENDING","APPLYING","VALIDATING").contains(proposal.status()))return;
        String decision="REJECTED".equals(proposal.status())?"REJECTED":"APPROVED";
        String result=mapper.createObjectNode().put("localProposalId",proposal.id()).put("approval",decision).put("validationStatus",proposal.status()).put("validationRunId",proposal.validationRunId()).toString();
        var runs=db.queryForList("SELECT DISTINCT run_id FROM tool_call WHERE approval_status='PENDING' AND CASE WHEN json_valid(result_json) THEN json_extract(result_json,'$.localProposalId') END=?",Long.class,proposal.id());
        for(Long run:runs) {
            int count=db.update("UPDATE tool_call SET status='COMPLETED',approval_status=?,result_json=?,ended_at=datetime('now','localtime') WHERE run_id=? AND approval_status='PENDING' AND CASE WHEN json_valid(result_json) THEN json_extract(result_json,'$.localProposalId') END=?",decision,result,run,proposal.id());
            if(count>0)try{runControl.getObject().resume(run);}catch(com.aigamelab.common.BusinessException ignored){/* cancelled or original loop has not suspended yet */}
        }
    }
    private Proposal require(String id){return db.query("SELECT proposal_json FROM local_change_proposal WHERE id=?",(rs,n)->read(rs.getString(1)),id).stream().findFirst().orElseThrow(()->BusinessException.notFound("Local change proposal not found"));}
    private Proposal read(String value){try{return mapper.readValue(value,Proposal.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private void save(Proposal p){try{db.update("INSERT INTO local_change_proposal(id,status,proposal_json) VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,proposal_json=excluded.proposal_json",p.id(),p.status(),mapper.writeValueAsString(p));}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
