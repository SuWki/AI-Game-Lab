package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.aigamelab.localtest.*;
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
import java.util.zip.*;
import java.io.*;

/** Freezes only versioned inputs and the exact engine-verified output; never walks a user workspace. */
@Service
public class ProductionCandidateService {
    static final List<String> SOURCE=List.of("engine.mjs","game.mjs","index.html","style.css","config.json","production-manifest.json","specification.json","build-project.mjs","serve-project.mjs");
    static final List<String> BUILD=List.of("index.html","game.mjs","engine.mjs","style.css","config.json");
    private final JdbcTemplate db;private final ObjectMapper mapper;private final ProductionJobService jobs;private final LocalTestService tests;private final LocalEvidenceService evidence;
    private final Path root,repo;
    public ProductionCandidateService(JdbcTemplate db,ObjectMapper mapper,ProductionJobService jobs,LocalTestService tests,LocalEvidenceService evidence,
            @Value("${production.candidate-root:./data/candidates}")String root,@Value("${production.repository-root:..}")String repo){
        this.db=db;this.mapper=mapper;this.jobs=jobs;this.tests=tests;this.evidence=evidence;this.root=Path.of(root).toAbsolutePath().normalize();this.repo=Path.of(repo).toAbsolutePath().normalize();
        for(var record:db.query("SELECT record_json FROM production_candidate",(rs,n)->read(rs.getString(1))))if("PREPARING".equals(record.path("status").asText())){record.put("status","INTERRUPTED").put("error","Packaging interrupted; inspect files, no automatic replay");save(record);}
    }
    private ObjectNode read(String text){try{return (ObjectNode)mapper.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
    private void save(ObjectNode record){db.update("INSERT INTO production_candidate VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET record_json=excluded.record_json",record.path("id").asText(),record.path("productionId").asText(),record.path("validationRunId").asText(),record.toString());}
    public ObjectNode get(String id){return db.query("SELECT record_json FROM production_candidate WHERE id=?",(rs,n)->read(rs.getString(1)),id).stream().findFirst().orElseThrow(()->BusinessException.notFound("Candidate not found"));}
    public List<ObjectNode> list(String productionId){return db.query("SELECT record_json FROM production_candidate WHERE production_id=? ORDER BY rowid DESC",(rs,n)->read(rs.getString(1)),productionId);}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private byte[] file(Path directory,String name)throws IOException{
        Path base=directory.toRealPath(),relative=Path.of(name);if(relative.isAbsolute()||name.contains("\\")||!base.resolve(relative).normalize().startsWith(base))throw BusinessException.conflict("Invalid candidate path");
        Path cursor=base;for(Path part:relative){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw BusinessException.conflict("Candidate symlink rejected");}
        if(!Files.isRegularFile(cursor)||Files.size(cursor)>16_777_216)throw BusinessException.conflict("Missing or oversized candidate input: "+name);return Files.readAllBytes(cursor);
    }
    private void put(Map<String,byte[]> files,String name,byte[] bytes){files.put(name,bytes);if(files.size()>40||files.values().stream().mapToLong(b->b.length).sum()>8_388_608)throw BusinessException.conflict("Candidate exceeds package bounds");}
    private void json(Map<String,byte[]> files,String name,JsonNode value){put(files,name,value.toPrettyString().getBytes(StandardCharsets.UTF_8));}
    public synchronized ObjectNode create(String productionId,String validationRunId){
        if(validationRunId==null||validationRunId.isBlank())throw new IllegalArgumentException("Exact validationRunId required");
        var job=jobs.get(productionId);var current=job.path("iteration");
        if(!"REVIEW".equals(current.path("phase").asText())||!validationRunId.equals(current.path("currentRunId").asText()))throw BusinessException.conflict("Package only the current verified candidate");
        var existing=db.queryForList("SELECT id FROM production_candidate WHERE production_id=? AND validation_run_id=?",String.class,productionId,validationRunId);
        if(!existing.isEmpty()){var saved=get(existing.get(0));if(!"READY".equals(saved.path("status").asText()))throw BusinessException.conflict("Previous packaging requires inspection: "+saved.path("status").asText());verifiedFiles(saved.path("id").asText());return saved;}
        var run=tests.get(validationRunId);var assessment=evidence.assessment(run);var report=run.evidence();
        if(!"PASS".equals(assessment.path("decision").asText())||report==null||!"survival-web-engine-v1".equals(report.path("scope").asText())||!job.path("acceptanceContractHash").equals(report.path("contractHash")))throw BusinessException.conflict("Independent production acceptance required");
        var baseline=tests.get(job.path("validationRunId").asText());if(!baseline.target().command().equals(run.target().command())||!run.target().id().equals("verify-"+productionId))throw BusinessException.conflict("Acceptance command or target changed");
        evidence.verify(validationRunId);var binding=evidence.binding(validationRunId);String id=UUID.nameUUIDFromBytes((productionId+":"+validationRunId).getBytes(StandardCharsets.UTF_8)).toString();
        var record=mapper.createObjectNode().put("id",id).put("productionId",productionId).put("validationRunId",validationRunId).put("status","PREPARING").put("createdAt",Instant.now().toString());save(record);
        try{
            var files=new TreeMap<String,byte[]>();Path workspace=Path.of(run.target().workingDirectory());
            for(String name:SOURCE){byte[] bytes=file(workspace,name);if(!hash(bytes).equals(binding.path("source").path(name).asText()))throw BusinessException.conflict("Tested source changed: "+name);put(files,"source/"+name,bytes);}
            var build=report.path("validationBuild");String buildId=build.path("buildId").asText();if(!buildId.matches("build-[a-f0-9-]{36}"))throw BusinessException.conflict("Invalid verified build ID");
            if(build.path("files").size()!=BUILD.size())throw BusinessException.conflict("Unexpected build inventory");
            for(String name:BUILD){byte[] bytes=file(workspace,buildId+"/"+name);String expected=build.path("files").path(name).asText();if(!hash(bytes).equals(expected)||!expected.equals(binding.path("source").path(name).asText()))throw BusinessException.conflict("Build does not match tested source: "+name);put(files,"build/"+name,bytes);}
            byte[] buildManifest=file(workspace,buildId+"/build-manifest.json");if(!mapper.readTree(buildManifest).equals(build))throw BusinessException.conflict("Verified build manifest changed");put(files,"build/build-manifest.json",buildManifest);
            byte[] latest=file(workspace,"latest-build.json");if(!hash(latest).equals(binding.path("artifacts").path("latest-build.json").asText())||!mapper.readTree(latest).equals(build))throw BusinessException.conflict("Build artifact drift");
            byte[] result=file(workspace,"acceptance-result.json");if(!hash(result).equals(binding.path("artifacts").path("acceptance-result.json").asText())||!mapper.readTree(result).equals(report))throw BusinessException.conflict("Acceptance artifact drift");
            var command=run.target().command();Path program=Path.of(command.get(1));byte[] verifier=file(program.getParent(),program.getFileName().toString());byte[] builder=file(program.getParent().getParent(),"build-project.mjs");byte[] contract=file(Path.of(job.path("jobDirectory").asText()),"acceptance-contract.json");
            if(!hash(verifier).equals(job.path("acceptanceProgramHash").asText())||!hash(builder).equals(job.path("acceptanceBuilderHash").asText())||!hash(contract).equals(job.path("acceptanceContractHash").asText()))throw BusinessException.conflict("Frozen acceptance inputs drifted");
            put(files,"verification/acceptance/validate-game.mjs",verifier);put(files,"verification/build-project.mjs",builder);put(files,"verification/acceptance-contract.json",contract);
            put(files,"evidence/validation.json",result);var exportBinding=(ObjectNode)binding.deepCopy();exportBinding.remove("root");json(files,"evidence/binding.json",exportBinding);
            put(files,"serve-candidate.mjs",file(repo.resolve("scripts/production/candidate"),"serve-candidate.mjs"));
            put(files,"README.md",("# AI Game Lab fixed candidate\n\nProduction: "+productionId+"\nValidation: "+validationRunId+"\n\nUse Node 20+: `node serve-candidate.mjs 4180`, then open the loopback URL printed in the terminal. This serves the fixed build. To rebuild source: `cd source` then `node build-project.mjs`; compare output file hashes with candidate-manifest.json. No external dependencies or lockfile required.\n\nEngine checks only; not full GameSpec/browser or human quality acceptance. See KNOWN_ISSUES.md and assets.json. No automatic publication.\n").getBytes(StandardCharsets.UTF_8));
            put(files,"KNOWN_ISSUES.md","# Known boundaries\n\nOnly six survival-web-engine-v1 checks passed. Browser/UI, performance, all natural-language GameSpec requirements and human acceptance are not certified. Geometry is placeholder art. Review source for hardcoded secrets before distributing. This trusted local workflow is not an OS sandbox.\n".getBytes(StandardCharsets.UTF_8));
            json(files,"assets.json",mapper.createObjectNode().put("kind","procedural-placeholder-geometry").put("externalAssetFiles",0).put("source","survival-web template").put("templateHash",job.path("templateHash").asText()).put("licenseReview","No external assets exported; review rights and suitability before release"));
            var manifest=mapper.createObjectNode().put("schemaVersion",1).put("candidateId",id).put("productionId",productionId).put("validationRunId",validationRunId).put("specHash",job.path("specHash").asText()).put("templateHash",job.path("templateHash").asText()).put("sourceVersion",binding.path("version").asText()).put("buildId",buildId).put("scope",report.path("scope").asText()).put("engineDecision","PASS").put("humanAccepted",false);manifest.set("provenance",job.path("result").path("provenance"));manifest.set("checks",report.path("checks"));
            var inventory=manifest.putObject("files");files.forEach((name,bytes)->inventory.put(name,hash(bytes)));byte[] manifestBytes=manifest.toPrettyString().getBytes(StandardCharsets.UTF_8);put(files,"candidate-manifest.json",manifestBytes);
            evidence.verify(validationRunId);if(!jobs.get(productionId).path("iteration").path("currentRunId").asText().equals(validationRunId)||!"REVIEW".equals(jobs.get(productionId).path("iteration").path("phase").asText()))throw BusinessException.conflict("Candidate changed during packaging");
            Files.createDirectories(root);Path parent=root.toRealPath(),temporary=Files.createDirectory(parent.resolve(id+".preparing"));
            for(var entry:files.entrySet()){Path output=temporary.resolve(entry.getKey());Files.createDirectories(output.getParent());Files.write(output,entry.getValue(),StandardOpenOption.CREATE_NEW);}
            Path archive=parent.resolve(id+".zip.part");try(var zip=new ZipOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW))){for(var entry:files.entrySet()){var item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue());zip.closeEntry();}}
            byte[] archiveBytes=Files.readAllBytes(archive);Files.move(temporary,parent.resolve(id),StandardCopyOption.ATOMIC_MOVE);Files.move(archive,parent.resolve(id+".zip"),StandardCopyOption.ATOMIC_MOVE);
            record.put("status","READY").put("manifestHash",hash(manifestBytes)).put("archiveSha256",hash(archiveBytes)).put("archiveBytes",archiveBytes.length);record.set("manifest",manifest);save(record);return record;
        }catch(Exception e){record.put("status","FAILED").put("error",String.valueOf(e.getMessage()));save(record);if(e instanceof RuntimeException re)throw re;throw BusinessException.conflict("Cannot freeze candidate: "+e.getMessage());}
    }
    public Map<String,byte[]> verifiedFiles(String id){
        var record=get(id);if(!"READY".equals(record.path("status").asText()))throw BusinessException.conflict("Candidate is not ready");
        try{
            Path directory=root.resolve(id);if(Files.isSymbolicLink(directory)||!directory.toRealPath().startsWith(root.toRealPath()))throw BusinessException.conflict("Fixed candidate directory changed");byte[] manifest=file(directory,"candidate-manifest.json");if(!hash(manifest).equals(record.path("manifestHash").asText()))throw BusinessException.conflict("Fixed candidate manifest changed");
            var value=mapper.readTree(manifest);var files=new TreeMap<String,byte[]>();var names=value.path("files").fieldNames();while(names.hasNext()){String name=names.next();byte[] bytes=file(directory,name);if(!hash(bytes).equals(value.path("files").path(name).asText()))throw BusinessException.conflict("Fixed candidate changed: "+name);put(files,name,bytes);}
            byte[] archive=file(root,id+".zip");if(!hash(archive).equals(record.path("archiveSha256").asText()))throw BusinessException.conflict("Fixed candidate archive changed");return files;
        }catch(IOException e){throw BusinessException.conflict("Fixed candidate cannot be read: "+e.getMessage());}
    }
    public byte[] download(String id){verifiedFiles(id);try{byte[] bytes=file(root,id+".zip");if(!hash(bytes).equals(get(id).path("archiveSha256").asText()))throw BusinessException.conflict("Fixed candidate archive changed");return bytes;}catch(IOException e){throw BusinessException.conflict("Candidate archive unavailable");}}
}
