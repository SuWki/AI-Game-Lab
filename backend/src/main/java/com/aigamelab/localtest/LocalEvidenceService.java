package com.aigamelab.localtest;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.*;
import com.aigamelab.repository.*;
import com.aigamelab.tester.FindingService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable source identity, artifact fingerprints and imported findings for external tests. */
@Service
public class LocalEvidenceService {
    private final JdbcTemplate db; private final ObjectMapper mapper;
    private final TaskRepository tasks;private final TestPlanRepository plans;private final FindingService findings;
    public LocalEvidenceService(JdbcTemplate db,ObjectMapper mapper,TaskRepository tasks,TestPlanRepository plans,FindingService findings) {
        this.db=db;this.mapper=mapper;this.tasks=tasks;this.plans=plans;this.findings=findings;
    }
    public JsonNode binding(String runId) {
        return db.query("SELECT binding_json FROM local_test_context WHERE run_id=?",(rs,n)->read(rs.getString(1)),runId).stream().findFirst().orElseThrow(()->BusinessException.notFound("Version binding not found"));
    }
    public void capture(LocalTestService.TestRun run,Long ownerRunId) {
        try {
            var source=manifest(Path.of(run.target().workingDirectory()),run.target().versionFiles(),false);
            ObjectNode binding=mapper.createObjectNode().put("targetId",run.target().id()).put("versioned",!source.isEmpty()).put("root",run.target().workingDirectory());
            binding.set("source",mapper.valueToTree(source));
            binding.set("versionFiles",mapper.valueToTree(run.target().versionFiles()));
            binding.put("commandHash",hash(mapper.writeValueAsBytes(run.target().command())));
            binding.put("version",source.isEmpty()?null:"local-"+hash((run.target().id()+":"+mapper.writeValueAsString(source)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if(ownerRunId!=null)binding.put("ownerRunId",ownerRunId);
            db.update("INSERT INTO local_test_context(run_id,binding_json) VALUES (?,?)",run.id(),binding.toString());
        }catch(IOException e){throw new IllegalArgumentException("Cannot snapshot source files",e);}
    }
    public void verify(String runId) {
        JsonNode binding=binding(runId);
        try {
            if(!binding.path("versioned").asBoolean())return;
            Map<String,String> expected=mapper.convertValue(binding.path("source"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
            List<String> paths=mapper.convertValue(binding.path("versionFiles"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
            if(!expected.equals(manifest(Path.of(binding.path("root").asText()),paths,false)))throw BusinessException.conflict("Source tree changed after test submission");
        }catch(IOException e){throw new IllegalStateException("Source version cannot be verified",e);}
    }
    public JsonNode source(String runId,String name) {
        JsonNode binding=binding(runId);verify(runId);
        if(!binding.path("source").has(name))throw BusinessException.notFound("Source file is not in this version manifest");
        try {
            Path file=resolve(Path.of(binding.path("root").asText()),name);
            if(Files.size(file)>1048576)throw new IllegalArgumentException("Source editor supports at most 1 MiB");
            return mapper.createObjectNode().put("path",name).put("sha256",binding.path("source").path(name).asText()).put("text",Files.readString(file,java.nio.charset.StandardCharsets.UTF_8));
        }catch(IOException e){throw new IllegalArgumentException("Source is not readable UTF-8 text",e);}
    }
    public void captureArtifacts(LocalTestService.TestRun run) {
        try {
            ObjectNode binding=(ObjectNode)binding(run.id());
            binding.set("artifacts",mapper.valueToTree(manifest(Path.of(run.target().workingDirectory()),run.target().artifactFiles(),true)));
            save(run.id(),binding);
        }catch(IOException e){throw new IllegalStateException("Artifact fingerprint failed",e);}
    }
    public Map<String,String> manifest(Path root,List<String> paths,boolean allowMissing)throws IOException {
        root=root.toRealPath();Map<String,String> files=new TreeMap<>();long total=0;
        for(String name:paths) {
            Path selected=resolve(root,name);
            if(!Files.exists(selected)&&allowMissing){files.put(name,"MISSING");continue;}
            try(var walk=Files.walk(selected)) {
                var iterator=walk.iterator();while(iterator.hasNext()) {Path path=iterator.next();
                    if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Symlink is not a source manifest entry");
                    if(!Files.isRegularFile(path))continue;
                    String relative=root.relativize(path).toString().replace('\\','/');if(files.containsKey(relative))continue;
                    total+=Files.size(path);if(files.size()>=5000||total>67108864)throw new IllegalArgumentException("Source manifest exceeds 5000 files / 64 MiB");
                    files.put(relative,fileHash(path));
                }
            }
        }
        return files;
    }
    static Path resolve(Path root,String name)throws IOException {
        root=root.toRealPath();Path relative=Path.of(name);
        Path file=root.resolve(relative).normalize();
        if(relative.isAbsolute()||!file.startsWith(root))throw new IllegalArgumentException("File must be relative and inside target root");
        Path ancestor=file;while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        if(!ancestor.toRealPath().startsWith(root)||Files.isSymbolicLink(file))throw new IllegalArgumentException("File symlink escapes target root");
        return file;
    }
    static String fileHash(Path path)throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(path)) {byte[] b=new byte[8192];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>67108864)throw new IOException("Source file exceeds 64 MiB");digest.update(b,0,n);}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
    public JsonNode assessment(LocalTestService.TestRun run) {
        ObjectNode result=mapper.createObjectNode().put("localTestRunId",run.id()).put("decision","INCONCLUSIVE");
        JsonNode binding=binding(run.id());result.set("binding",binding);
        if(!run.target().artifactFiles().isEmpty()) {
            if(binding.path("artifacts").isEmpty())return result.put("reason","Required artifact fingerprints are missing");
            for(JsonNode artifact:binding.path("artifacts"))if("MISSING".equals(artifact.asText()))return result.put("reason","A required artifact is missing");
        }
        JsonNode evidence=run.evidence();
        if(!binding.path("versioned").asBoolean()||!Set.of("PASSED","FAILED").contains(run.status())||evidence==null||!run.id().equals(evidence.path("runId").asText())||!evidence.path("coverageComplete").asBoolean()||!evidence.path("checks").isArray()||evidence.path("checks").isEmpty())return result.put("reason","Versioned, complete structured checks are required");
        Set<String> ids=new HashSet<>();int failed=0;
        for(JsonNode check:evidence.path("checks")) {
            String id=check.path("id").asText(),status=check.path("status").asText();
            if(id.isBlank()||!ids.add(id)||!Set.of("PASS","FAIL").contains(status))return result.put("reason","Invalid, duplicate or missing check identity");
            if("FAIL".equals(status)) {
                if(check.path("expected").asText().isBlank()||check.path("actual").asText().isBlank())return result.put("reason","Failed check needs concrete expected and actual values");
                failed++;
            }
        }
        if(run.exitCode()==null || (failed==0 && (!"PASSED".equals(run.status())||run.exitCode()!=0)))return result.put("reason","Execution did not provide a passing result");
        return result.put("decision",failed==0?"PASS":"FAIL").put("failedChecks",failed).put("totalChecks",ids.size());
    }
    @Transactional
    public JsonNode importChecks(LocalTestService.TestRun run) {
        ObjectNode binding=(ObjectNode)binding(run.id());
        if(binding.has("testPlanId"))return binding;
        if("INCONCLUSIVE".equals(assessment(run).path("decision").asText()))throw BusinessException.conflict("Complete versioned test evidence required before import");
        String version=binding.path("version").asText();
        Task task=tasks.insert(new Task(null,"Review "+run.target().name(),"Review findings from local test "+run.id(),mapper.createObjectNode().put("agent_type","TESTER").put("localTargetId",run.target().id()).put("localTestRunId",run.id()).toString(),20,true,"ACTIVE",null));
        ArrayNode scenarios=mapper.createArrayNode();scenarios.addObject().put("id","local-command").put("type","FUNCTIONAL").put("executionMode","LOCAL_COMMAND");
        TestPlan plan=plans.insert(new TestPlan(null,task.id(),version,"COMPLETED",scenarios.toString(),null));
        ArrayNode findingIds=mapper.createArrayNode();
        for(JsonNode check:run.evidence().path("checks"))if("FAIL".equals(check.path("status").asText())) {
            String severity=check.path("severity").asText("MEDIUM"),category=check.path("category").asText("OTHER");
            if(!Finding.SEVERITIES.contains(severity)||!Finding.CATEGORIES.contains(category))throw new IllegalArgumentException("Invalid finding classification");
            Map<String,Object> snapshot=Map.of("localTestRunId",run.id(),"version",version,"check",mapper.convertValue(check,Map.class));
            Finding.Evidence ev=new Finding.Evidence("local:"+run.id(),null,List.of(),snapshot,null,null,Map.of());
            try {
                Finding saved=findings.report(new Finding(null,null,plan.id(),task.id(),check.path("id").asText(),version,severity,category,"MANUAL",check.path("title").asText(check.path("id").asText()),check.path("expected").asText(),check.path("actual").asText(),"Execute registered target "+run.target().id()+"; inspect local test "+run.id(),mapper.writeValueAsString(ev),null,"OPEN",null));
                findingIds.add(saved.id());
            }catch(IOException e){throw new IllegalStateException(e);}
        }
        binding.put("taskId",task.id()).put("testPlanId",plan.id());binding.set("findingIds",findingIds);save(run.id(),binding);return binding;
    }
    public Task createAgentTask(LocalTestService.TestRun run,String type,String goal) {
        if(!Set.of("DESIGNER","TESTER").contains(type)||goal==null||goal.isBlank())throw new IllegalArgumentException("Agent type DESIGNER/TESTER and a goal are required");
        ObjectNode constraints=mapper.createObjectNode().put("agent_type",type).put("localTargetId",run.target().id()).put("localTestRunId",run.id());
        return tasks.insert(new Task(null,type+" · "+run.target().name(),goal,constraints.toString(),20,true,"ACTIVE",null));
    }
    private void save(String runId,JsonNode value){db.update("UPDATE local_test_context SET binding_json=? WHERE run_id=?",value.toString(),runId);}
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(IOException e){throw new IllegalStateException(e);}}
}
