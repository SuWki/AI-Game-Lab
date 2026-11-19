package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.aigamelab.localtest.LocalTestService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Production records delegate process ownership, cancellation and bounds to the shared executor. */
@Service
public class ProductionJobService {
    private static final List<String> FILES=List.of("engine.mjs","game.mjs","index.html","style.css","template.json");
    private final JdbcTemplate db;private final ObjectMapper mapper;private final ProductionArtifactService artifacts;private final LocalTestService executor;
    private final Path repo,root;private final String node;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.repository.TaskRepository tasks;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.aigamelab.localtest.LocalEvidenceService> evidence;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<ProductionIterationService> iterations;
    @org.springframework.beans.factory.annotation.Autowired private BuilderSessionService sessions;
    @org.springframework.beans.factory.annotation.Autowired private ManufacturingScopeService manufacturingScopes;
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.repository.RunRepository runs;
    private static final List<String> PROTECTED=List.of("config.json","production-manifest.json","specification.json","build-project.mjs","serve-project.mjs");
    public record Request(long taskId,String specId,int specRevision,String requestKey){}
    public ProductionJobService(JdbcTemplate db,ObjectMapper mapper,ProductionArtifactService artifacts,LocalTestService executor,
        @Value("${production.repository-root:..}")String repo,@Value("${production.workspace-root:./data/productions}")String root,@Value("${production.node:node}")String node){
        this.db=db;this.mapper=mapper;this.artifacts=artifacts;this.executor=executor;this.repo=Path.of(repo).toAbsolutePath().normalize();this.root=Path.of(root).toAbsolutePath().normalize();this.node=node;
        for(var value:db.query("SELECT record_json FROM production_job",(rs,n)->decode(rs.getString(1))))if(value.path("localRunId").isNull()&&"PREPARING".equals(value.path("status").asText())){value.put("status","INTERRUPTED").put("error","Interrupted before execution was linked; inspect workspace, then submit a new request key");save(value);}
    }
    public JsonNode template(){try{return mapper.createObjectNode().put("id","survival-web").put("version","1.0.0").put("hash",templateHash());}catch(Exception e){throw new IllegalArgumentException("Production template unavailable",e);}}
    private String templateHash()throws Exception{List<String> lines=new ArrayList<>();for(String name:FILES){Path p=repo.resolve("templates/survival-web").resolve(name);if(Files.isSymbolicLink(p)||!Files.isRegularFile(p))throw new IllegalArgumentException("Invalid template file");lines.add(name+":"+hash(Files.readAllBytes(p)));}return hash(String.join("\n",lines).getBytes(StandardCharsets.UTF_8));}
    private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public synchronized JsonNode start(Request request){return startOwned(request,null);}
    public synchronized JsonNode startForAgent(Request request,long ownerRunId){
        var run=runs.findById(ownerRunId).orElseThrow();
        if(!"RUNNING".equals(run.status()))throw BusinessException.conflict("Builder Run is not active");
        var task=tasks.findById(run.taskId()).orElseThrow();sessions.claim(task,run);
        var session=sessions.snapshot(run.taskId());
        if(!session.path("productionSpecId").asText().equals(request.specId())||session.path("productionSpecRevision").asInt()!=request.specRevision()||session.path("productionSourceTaskId").asLong()!=request.taskId())throw BusinessException.conflict("Builder specification mismatch");
        if(session.hasNonNull("productionId"))return claimForBuilder(session.path("productionId").asText(),ownerRunId);
        var verdict=sessions.check(run,false);if(verdict.hit())throw BusinessException.conflict(verdict.reason());
        var job=startOwned(new Request(request.taskId(),request.specId(),request.specRevision(),"builder-task-"+run.taskId()),ownerRunId);
        sessions.bind(ownerRunId,job.path("id").asText());return get(job.path("id").asText());
    }
    public synchronized JsonNode claimForBuilder(String id,long runId){
        var job=require(id);sessions.assertOwner(runId,id);
        if(job.path("iterationCancelled").asBoolean())throw BusinessException.conflict("Production cancelled; retry cannot reset it");
        if(job.path("ownerRunId").asLong(-1)!=runId){
            var old=runs.findById(job.path("ownerRunId").asLong()).orElseThrow();var next=runs.findById(runId).orElseThrow();
            if(old.taskId()!=next.taskId()||!com.aigamelab.runtime.RunStateMachine.isTerminal(com.aigamelab.domain.RunStatus.valueOf(old.status())))throw BusinessException.conflict("Production belongs to another Agent Run");
            job.withArray("ownerHistory").addObject().put("runId",old.id()).put("transferredAt",Instant.now().toString());job.put("ownerRunId",runId);save(job);
        }
        return get(id);
    }
    private JsonNode startOwned(Request request,Long ownerRunId){
        if(request.specId()==null||request.specRevision()<1||request.requestKey()==null||!request.requestKey().matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Exact specification revision and request key required");
        var spec=artifacts.get(request.specId(),request.specRevision());
        if(!"GameSpec".equals(spec.path("kind").asText())||spec.path("taskId").asLong()!=request.taskId())throw BusinessException.conflict("Specification does not belong to this production task");
        var existing=db.queryForList("SELECT id FROM production_job WHERE task_id=? AND request_key=?",String.class,request.taskId(),request.requestKey());
        if(!existing.isEmpty()){var job=get(existing.get(0));if(!job.path("specId").asText().equals(request.specId())||job.path("specRevision").asInt()!=request.specRevision()||!Objects.equals(ownerRunId,job.path("ownerRunId").isNumber()?job.path("ownerRunId").asLong():null))throw BusinessException.conflict("Request key already used for another specification or owner");return job;}
        var parameters=spec.path("content").path("systems").path("survivalWeb");
        if(!"web".equals(spec.path("content").path("targetPlatform").asText())||!Set.of("heat-burst","shield-dash").contains(parameters.path("mechanic").asText()))throw new IllegalArgumentException("Specification is unsupported by survival-web");
        String id=UUID.randomUUID().toString();ObjectNode job=mapper.createObjectNode().put("id",id).put("taskId",request.taskId()).put("specId",request.specId()).put("specRevision",request.specRevision()).put("specHash",spec.path("contentHash").asText()).put("requestKey",request.requestKey()).put("status","PREPARING").put("createdAt",Instant.now().toString());job.putNull("localRunId");
        if(ownerRunId!=null){
            job.put("ownerRunId",ownerRunId);
            manufacturingScopes.find(request.specId(),request.specRevision(),job.path("specHash").asText())
                    .ifPresent(confirmation -> job.set("manufacturingScope", mapper.valueToTree(confirmation)));
        }
        try{
            String fingerprint=templateHash();job.put("templateHash",fingerprint);save(job);
            Files.createDirectories(root);Path directory=Files.createDirectory(root.toRealPath().resolve(id));job.put("jobDirectory",directory.toString());save(job);
            var input=mapper.createObjectNode().put("templateHash",fingerprint);input.set("spec",spec);Files.writeString(directory.resolve("input.json"),input.toString(),StandardCharsets.UTF_8);
            var target=new LocalTestService.Target("production-"+id,"Build survival-web",directory.toString(),List.of(node,repo.resolve("scripts/production/run-production.mjs").toString()),60,"result.json",List.of("input.json"),List.of("result.json"));
            var run=ownerRunId==null?executor.startManaged(target):executor.startManaged(target,ownerRunId);
            job.put("localRunId",run.id()).put("status","QUEUED");save(job);
        }catch(Exception e){job.put("status","FAILED").put("error",e.getClass().getSimpleName()+": "+e.getMessage());save(job);}
        return get(id);
    }
    public synchronized JsonNode get(String id){
        var job=require(id);if(!job.path("localRunId").isNull()&&!job.path("localRunId").isMissingNode()){
            var run=executor.get(job.path("localRunId").asText());String state=run.status();
            if("PASSED".equals(state)){
                var result=run.evidence()==null?null:run.evidence().get("production");
                if(result==null||!job.path("specHash").asText().equals(result.path("provenance").path("spec").path("hash").asText())){state="FAILED";job.put("error","Production result does not match specification");}
                else{state="BUILT";job.set("result",result);}
            }else if("COMPLETED".equals(state))state="INCONCLUSIVE";
            if("FAILED".equals(state)&&run.evidence()!=null&&run.evidence().path("checks").isArray())for(var check:run.evidence().path("checks"))if("FAIL".equals(check.path("status").asText())){job.put("error",check.path("actual").asText("Production command failed"));break;}
            job.put("status",state);if(run.error()!=null)job.put("error",run.error());save(job);
        }
        if(job.hasNonNull("validationRunId")){var validation=executor.get(job.path("validationRunId").asText());job.put("validationStatus",validation.status());if(!Set.of("QUEUED","RUNNING").contains(validation.status())){job.put("validationDecision",evidence.getObject().assessment(validation).path("decision").asText());if(validation.evidence()!=null){job.set("validationChecks",validation.evidence().path("checks"));job.put("validationScope",validation.evidence().path("scope").asText());}}save(job);}
        if(iterations!=null)job.set("iteration",iterations.getObject().snapshot(job));
        if(sessions!=null&&job.hasNonNull("ownerRunId"))runs.findById(job.path("ownerRunId").asLong()).ifPresent(r->{var budget=sessions.snapshot(r.taskId());if(budget!=null)job.set("budget",budget);});
        return job;
    }
    public synchronized JsonNode cancel(String id){var job=require(id);iterations.getObject().cancel(id);if(!job.path("localRunId").isNull()&&!job.path("localRunId").isMissingNode())executor.cancel(job.path("localRunId").asText());if(job.hasNonNull("validationRunId"))executor.cancel(job.path("validationRunId").asText());return get(id);}
    public synchronized JsonNode validate(String id,Long ownerRunId){
        var job=(ObjectNode)get(id);
        if(job.path("iterationCancelled").asBoolean())throw BusinessException.conflict("Production cancelled");
        if(ownerRunId!=null&&job.path("ownerRunId").asLong(-1)!=ownerRunId)throw BusinessException.conflict("Production belongs to another Agent Run");
        if(!"BUILT".equals(job.path("status").asText()))throw BusinessException.conflict("A completed production build is required");
        if (job.hasNonNull("validationRunId") && !"INTERRUPTED".equals(job.path("validationStatus").asText())) return job;
        try{
            Path workspace=Path.of(job.path("result").path("workspace").asText()).toRealPath(),directory=Path.of(job.path("jobDirectory").asText()).toRealPath();
            if(!workspace.startsWith(directory.resolve("output")))throw new IllegalArgumentException("Production workspace is outside the managed output");
            Path contractPath = directory.resolve("acceptance-contract.json");
            Path program = repo.resolve("scripts/production/acceptance/validate-game.mjs");
            Path builder = repo.resolve("scripts/production/build-project.mjs");
            prepareContract(job, workspace, contractPath, program, builder);

            var sources = new ArrayList<>(List.of("engine.mjs", "game.mjs", "index.html", "style.css"));
            sources.addAll(PROTECTED);
            var target = new LocalTestService.Target("verify-" + id, "Independent production engine verification",
                    workspace.toString(), List.of(node, program.toString(), contractPath.toString(),
                    job.path("acceptanceContractHash").asText(), job.path("acceptanceProgramHash").asText(),
                    job.path("acceptanceBuilderHash").asText()), 60, "acceptance-result.json", sources,
                    List.of("latest-build.json", "acceptance-result.json"), PROTECTED);
            var existing = executor.latestManagedRun(target);
            var run = existing.filter(r -> !Set.of("INTERRUPTED", "REJECTED").contains(r.status())).orElse(null);
            if (run == null) run = ownerRunId == null ? executor.startManaged(target) : executor.startManaged(target, ownerRunId);
            job.put("validationRunId", run.id());
            job.remove(List.of("validationStatus", "validationDecision", "validationChecks", "validationScope"));
            save(job);
            return get(id);
        }catch(java.io.IOException e){throw BusinessException.conflict("Cannot prepare frozen verification: "+e.getMessage());}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}catch(Exception e){if(e instanceof RuntimeException re)throw re;throw new IllegalStateException(e);}
    }
    private void prepareContract(ObjectNode job, Path workspace, Path file, Path program, Path builder) throws Exception {
        String programHash = hash(Files.readAllBytes(program));
        String builderHash = hash(Files.readAllBytes(builder));
        if (!job.hasNonNull("acceptanceContractHash")) {
            String content = buildAcceptanceContract(job, workspace);
            String expected = hash(content.getBytes(StandardCharsets.UTF_8));
            // A legacy process may have written the file before saving the fingerprint.
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) verifyContractFile(file, expected);
            job.put("acceptanceContractHash", expected).put("acceptanceContractText", content)
                    .put("acceptanceProgramHash", programHash).put("acceptanceBuilderHash", builderHash);
            save(job);
        }
        if (!programHash.equals(job.path("acceptanceProgramHash").asText())
                || !builderHash.equals(job.path("acceptanceBuilderHash").asText())) {
            throw BusinessException.conflict("Frozen verification program changed");
        }
        String expected = job.path("acceptanceContractHash").asText();
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS) && job.hasNonNull("acceptanceContractText")) {
            byte[] content = job.path("acceptanceContractText").asText().getBytes(StandardCharsets.UTF_8);
            if (!hash(content).equals(expected)) throw BusinessException.conflict("Persisted acceptance contract changed");
            Path temp = Files.createTempFile(file.getParent(), "acceptance-", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        verifyContractFile(file, expected);
    }

    private String buildAcceptanceContract(ObjectNode job, Path workspace) throws Exception {
        var build = job.path("result").path("build");
        byte[] config = Files.readAllBytes(workspace.resolve(build.path("buildId").asText()).resolve("config.json"));
        if (!hash(config).equals(build.path("files").path("config.json").asText())) {
            throw BusinessException.conflict("Built configuration changed");
        }
        ObjectNode protectedHashes = mapper.createObjectNode();
        for (String name : PROTECTED) {
            Path input = workspace.resolve(name);
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Missing protected input: " + name);
            }
            protectedHashes.put(name, hash(Files.readAllBytes(input)));
        }
        ObjectNode contract = mapper.createObjectNode().put("schemaVersion", 1)
                .put("scope", "survival-web-engine-v1").put("productionId", job.path("id").asText())
                .put("specHash", job.path("specHash").asText());
        contract.set("config", mapper.readTree(config));
        contract.set("protectedFiles", protectedHashes);
        return contract.toString();
    }

    private void verifyContractFile(Path file, String expected) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024
                || !hash(Files.readAllBytes(file)).equals(expected)) {
            throw BusinessException.conflict("Frozen acceptance contract missing or changed");
        }
    }

    public com.aigamelab.domain.Task createBuilderTask(long taskId,String specId,int revision,String goal){
        var spec=artifacts.get(specId,revision);if(!"GameSpec".equals(spec.path("kind").asText())||spec.path("taskId").asLong()!=taskId)throw BusinessException.conflict("Specification scope mismatch");
        if(goal==null||goal.isBlank()||goal.length()>10000)throw new IllegalArgumentException("Builder goal required, up to 10000 characters");
        if (manufacturingScopes.find(specId, revision, spec.path("contentHash").asText()).isEmpty()) {
            throw BusinessException.conflict("Confirm manufacturing scope for this exact specification revision first");
        }
        var scope=mapper.createObjectNode().put("agent_type","BUILDER").put("productionSpecId",specId).put("productionSpecRevision",revision).put("productionSourceTaskId",taskId);
        return tasks.insert(new com.aigamelab.domain.Task(null,"Builder: "+spec.path("content").path("title").asText(),goal,scope.toString(),20,true,"ACTIVE",null));
    }
    public List<JsonNode> list(){return db.queryForList("SELECT id FROM production_job ORDER BY rowid DESC LIMIT 100",String.class).stream().map(this::get).toList();}
    private ObjectNode require(String id){return db.query("SELECT record_json FROM production_job WHERE id=?",(rs,n)->decode(rs.getString(1)),id).stream().findFirst().orElseThrow(()->BusinessException.notFound("Production job not found"));}
    private ObjectNode decode(String value){try{return (ObjectNode)mapper.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private void save(ObjectNode job){db.update("INSERT INTO production_job(id,task_id,request_key,record_json) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET record_json=excluded.record_json",job.path("id").asText(),job.path("taskId").asLong(),job.path("requestKey").asText(),job.toString());}
}
