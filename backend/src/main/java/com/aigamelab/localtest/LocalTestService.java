package com.aigamelab.localtest;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Executes operator-registered local test commands, never LLM-supplied shell text. */
@Service
public class LocalTestService implements AutoCloseable {
    public record Target(String id, String name, String workingDirectory, List<String> command,
                         int timeoutSeconds, String resultPath,List<String> versionFiles,List<String> artifactFiles) {
        public Target(String id,String name,String workingDirectory,List<String> command,int timeoutSeconds,String resultPath) {
            this(id,name,workingDirectory,command,timeoutSeconds,resultPath,List.of(),List.of());
        }
        public Target {versionFiles=versionFiles==null?List.of():List.copyOf(versionFiles);artifactFiles=artifactFiles==null?List.of():List.copyOf(artifactFiles);}
    }
    public record TestRun(String id, Target target, String status, String startedAt, String finishedAt,
                          Integer exitCode, String output, JsonNode evidence, String error) {}
    private final ObjectMapper mapper;
    private final JdbcTemplate db;
    private final Path catalog;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<LocalEvidenceService> evidenceProvider;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<LocalChangeService> changesProvider;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8));
    private final Map<String,Job> active = new ConcurrentHashMap<>();
    private static class Job {
        TestRun run; Process process; boolean cancelled;
        Job(TestRun run) { this.run=run; }
    }
    public LocalTestService(ObjectMapper mapper, JdbcTemplate db,
            @Value("${local-tests.catalog:./data/local-test-targets.json}") String catalog) {
        this.mapper=mapper; this.db=db; this.catalog=Path.of(catalog);
        // A restarted backend cannot infer what an external process accomplished.
        for (TestRun run:db.query("SELECT record_json FROM local_test_run",(rs,n)->decode(rs.getString(1)))) if (Set.of("QUEUED","RUNNING").contains(run.status()))
            save(new TestRun(run.id(),run.target(),"INTERRUPTED",run.startedAt(),now(),run.exitCode(),
                    run.output(),run.evidence(),"Backend restarted; inspect artifacts before retrying"));
    }
    public List<Target> targets() {
        if (!Files.exists(catalog)) return List.of();
        try {
            if (Files.size(catalog)>262144) throw new IllegalArgumentException("Catalog too large");
            List<Target> targets=List.of(mapper.readValue(catalog.toFile(),Target[].class));
            Set<String> ids=new HashSet<>();
            for(Target t:targets) {
                if(t.id()==null || !t.id().matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(t.id()) ||
                   t.name()==null || t.workingDirectory()==null || t.command()==null || t.command().isEmpty() ||
                   t.command().stream().anyMatch(s->s==null || s.isBlank()) ||
                   t.timeoutSeconds()<1 || t.timeoutSeconds()>3600) throw new IllegalArgumentException("Invalid target");
            }
            return targets;
        } catch(Exception e) { throw new IllegalArgumentException("Invalid local test catalog",e); }
    }
    public List<TestRun> list() {
        return db.query("SELECT record_json FROM local_test_run ORDER BY rowid DESC LIMIT 100",
                (rs,n)->decode(rs.getString(1)));
    }
    public TestRun get(String id) {
        return db.query("SELECT record_json FROM local_test_run WHERE id=?",(rs,n)->decode(rs.getString(1)),id)
                .stream().findFirst().orElseThrow(()->BusinessException.notFound("Local test run not found"));
    }
    public synchronized TestRun start(String targetId) {
        Target target=targets().stream().filter(t->t.id().equals(targetId)).findFirst()
                .orElseThrow(()->BusinessException.notFound("Local test target not found"));
        return startTarget(target,null);
    }
    public synchronized TestRun startForAgent(String targetId,long agentRunId) {
        Target target=targets().stream().filter(t->t.id().equals(targetId)).findFirst().orElseThrow(()->BusinessException.notFound("Local test target not found"));
        return startTarget(target,agentRunId);
    }
    synchronized TestRun startWorkspace(Target target) {return startTarget(target,null);}
    /** Internal trusted service integration. Never bind a Target from an HTTP request. */
    public synchronized TestRun startManaged(Target target) {return startTarget(target,null);}
    public synchronized TestRun startManaged(Target target,Long ownerRunId) {return startTarget(target,ownerRunId);}
    synchronized TestRun startWorkspace(Target target,Long ownerRunId) {return startTarget(target,ownerRunId);}
    private TestRun startTarget(Target target,Long ownerRunId) {
        try {
            Path root=Path.of(target.workingDirectory()).toRealPath();
            if(!Files.isDirectory(root))throw new IllegalArgumentException("Working directory is not a directory");
            if(target.resultPath()!=null)resolveResult(root,target.resultPath());
            for(Job existing:active.values()) synchronized(existing) {
                if(Set.of("QUEUED","RUNNING").contains(existing.run.status()) &&
                   Path.of(existing.run.target().workingDirectory()).toRealPath().equals(root))
                    throw BusinessException.conflict("Another local test is using this working directory");
            }
            target=new Target(target.id(),target.name(),root.toString(),List.copyOf(target.command()),target.timeoutSeconds(),target.resultPath(),target.versionFiles(),target.artifactFiles());
        } catch(IOException e) { throw new IllegalArgumentException("Working directory unavailable",e); }
        Job job=new Job(new TestRun(UUID.randomUUID().toString(),target,"QUEUED",now(),null,null,"",null,null));
        save(job.run);
        try {if(evidenceProvider!=null)evidenceProvider.getObject().capture(job.run,ownerRunId);}
        catch(RuntimeException e){finish(job,"REJECTED",null,"",null,e.getMessage());throw e;}
        active.put(job.run.id(),job);
        try { workers.execute(()->execute(job)); }
        catch(RejectedExecutionException e) {
            synchronized(job) { finish(job,"REJECTED",null,"",null,"Local test queue is full or shutting down"); }
            active.remove(job.run.id());
            throw BusinessException.conflict("Local test queue is full or shutting down");
        }
        return get(job.run.id());
    }
    public TestRun cancel(String id) {
        get(id);
        Job job=active.get(id);
        if(job!=null) synchronized(job) {
            if(!Set.of("QUEUED","RUNNING").contains(job.run.status()))return get(id);
            job.cancelled=true;
            if(job.process!=null)terminate(job.process);
            finish(job,"CANCELLED",null,job.run.output(),null,"Cancelled by user");
        }
        return get(id);
    }
    @org.springframework.context.event.EventListener
    public void onAgentTerminated(com.aigamelab.event.DomainEventEnvelope event) {
        if(event.runId()==null||!Set.of("RUN_CANCELLED","RUN_TIMEOUT","RUN_FAILED").contains(event.type()))return;
        for(String id:db.queryForList("SELECT run_id FROM local_test_context WHERE json_extract(binding_json,'$.ownerRunId')=?",String.class,event.runId()))cancel(id);
    }
    private void execute(Job job) {
        StringBuilder output=new StringBuilder();
        try {
            Target t=job.run.target(); Path root=Path.of(t.workingDirectory()).toRealPath();
            Path result=t.resultPath()==null?null:resolveResult(root,t.resultPath());
            String before=fingerprint(result);
            if(evidenceProvider!=null)evidenceProvider.getObject().verify(job.run.id());
            synchronized(job) {
                if(job.cancelled)return;
                job.run=new TestRun(job.run.id(),t,"RUNNING",job.run.startedAt(),null,null,"",null,null);save(job.run);
                ProcessBuilder builder=new ProcessBuilder(t.command().stream().map(arg->arg.replace("{workspace}",root.toString())).toList()).directory(root.toFile()).redirectErrorStream(true);
                builder.environment().put("AGL_TEST_RUN_ID",job.run.id());
                if(result!=null)builder.environment().put("AGL_TEST_RESULT_PATH",result.toString());
                else builder.environment().remove("AGL_TEST_RESULT_PATH");
                job.process=builder.start();
            }
            Process process=job.process;
            Thread reader=Thread.startVirtualThread(()->{
                try(Reader in=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)) {
                    char[] buf=new char[2048];int n;
                    while((n=in.read(buf))!=-1) synchronized(output) {
                        output.append(buf,0,n);if(output.length()>65536)output.delete(0,output.length()-65536);
                    }
                } catch(IOException ignored) { /* process termination closes its pipe */ }
            });
            boolean ended=process.waitFor(t.timeoutSeconds(),TimeUnit.SECONDS);
            if(!ended)terminate(process);
            reader.join(2000);
            String log; synchronized(output) { log=output.toString(); }
            synchronized(job) {
                if(job.cancelled)return;
                if(!ended) { finish(job,"TIMEOUT",null,log,null,"Command exceeded timeout");return; }
                int exit=process.exitValue();
                JsonNode evidence=null;
                if(result!=null) {
                    // Re-check confinement after command execution (symlinks may have changed).
                    result=resolveResult(root,t.resultPath());
                    if(!Files.isRegularFile(result)||Objects.equals(before,fingerprint(result))) {
                        finish(job,exit==0?"INCONCLUSIVE":"FAILED",exit,log,null,"Result missing or unchanged from before this run");return;
                    }
                    evidence=mapper.readTree(readBounded(result));
                    if(evidence==null || !job.run.id().equals(evidence.path("runId").asText())) {
                        finish(job,exit==0?"INCONCLUSIVE":"FAILED",exit,log,evidence,"Result runId does not match this run");return;
                    }
                }
                String status=exit!=0?"FAILED":evidence==null?"COMPLETED":
                        "PASS".equals(evidence.path("status").asText())?"PASSED":
                        "FAIL".equals(evidence.path("status").asText())?"FAILED":"INCONCLUSIVE";
                if(evidenceProvider!=null) {
                    evidenceProvider.getObject().verify(job.run.id());
                    evidenceProvider.getObject().captureArtifacts(job.run);
                }
                finish(job,status,exit,log,evidence,null);
            }
        } catch(Exception e) {
            String log; synchronized(output){log=output.toString();}
            synchronized(job) { if(!job.cancelled)finish(job,"FAILED",null,log,null,e.getClass().getSimpleName()+": "+e.getMessage()); }
        } finally {
            if(job.process!=null) { terminate(job.process);try {job.process.getInputStream().close();}catch(IOException ignored){} }
            active.remove(job.run.id());
        }
    }
    private static Path resolveResult(Path root,String relative)throws IOException {
        Path value=Path.of(relative);
        if(value.isAbsolute())throw new IllegalArgumentException("Result path must be relative");
        Path resolved=root.resolve(value).normalize();
        if(!resolved.startsWith(root))throw new IllegalArgumentException("Result path escapes working directory");
        Path ancestor=resolved;
        while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        if(!ancestor.toRealPath().startsWith(root))throw new IllegalArgumentException("Result symlink escapes working directory");
        return resolved;
    }
    private static String fingerprint(Path path)throws Exception {
        if(path==null||!Files.isRegularFile(path))return null;
        if(Files.size(path)>1048576)return "oversize:"+Files.getLastModifiedTime(path);
        return Files.getLastModifiedTime(path)+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(readBounded(path)));
    }
    private static byte[] readBounded(Path path)throws IOException {
        try(InputStream in=Files.newInputStream(path)) {
            byte[] bytes=in.readNBytes(1048577);
            if(bytes.length>1048576)throw new IOException("Result exceeds 1 MiB");
            return bytes;
        }
    }
    private static void terminate(Process process) {
        List<ProcessHandle> children=process.descendants().toList();
        for(int i=children.size()-1;i>=0;i--)children.get(i).destroyForcibly();
        if(process.isAlive())process.destroyForcibly();
        try { process.waitFor(2,TimeUnit.SECONDS); }catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    private void finish(Job j,String status,Integer exit,String output,JsonNode evidence,String error) {
        j.run=new TestRun(j.run.id(),j.run.target(),status,j.run.startedAt(),now(),exit,output,evidence,error);save(j.run);
        if(changesProvider!=null)try{changesProvider.getObject().onTestFinished(j.run.id());}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(LocalTestService.class).warn("Could not update change regression for {}",j.run.id(),e);}
    }
    private void save(TestRun run) {
        try { db.update("INSERT INTO local_test_run(id,record_json) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET record_json=excluded.record_json",run.id(),mapper.writeValueAsString(run)); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
    private TestRun decode(String json) { try { return mapper.readValue(json,TestRun.class); }catch(IOException e){throw new IllegalStateException(e);} }
    private static String now(){return Instant.now().toString();}
    @PreDestroy @Override public void close() {
        for(String id:List.copyOf(active.keySet()))cancel(id);
        workers.shutdown();
        try { if(!workers.awaitTermination(5,TimeUnit.SECONDS))workers.shutdownNow(); }
        catch(InterruptedException e){workers.shutdownNow();Thread.currentThread().interrupt();}
    }
}
