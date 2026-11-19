package com.aigamelab.production;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.TaskRepository;
import com.aigamelab.localtest.LocalTestService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="llm.gateway.api-key=")
class ProductionJobServiceTest {
 static final Path ROOT;static final Path REPO;
 static{try{ROOT=Files.createTempDirectory("agl-production-jobs-");Path cwd=Path.of("").toAbsolutePath();REPO=Files.exists(cwd.resolve("scripts/production"))?cwd:cwd.getParent();}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT.resolve("test.db"));r.add("production.repository-root",()->REPO.toString());r.add("production.workspace-root",()->ROOT.resolve("jobs").toString());r.add("production.candidate-root",()->ROOT.resolve("candidates").toString());r.add("production.node",()->Files.exists(Path.of("D:/nodejs/node.exe"))?"D:/nodejs/node.exe":"node");}
 @Autowired ProductionJobService jobs;@Autowired ProductionArtifactService artifacts;@Autowired TaskRepository tasks;@Autowired ObjectMapper mapper;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
 @Autowired com.aigamelab.localtest.LocalEvidenceService evidence;@Autowired com.aigamelab.localtest.LocalChangeService changes;
 @Autowired ControlledExecutor executor;
 @Autowired ProductionCandidateService candidates;
 @Autowired CandidateReviewService reviews;
 @Autowired CandidateBrowserEvidenceService browser;
 @Autowired org.springframework.context.ApplicationContext application;
 static class ControlledExecutor extends LocalTestService {
  String mode="real";TestRun controlled;boolean cancelled;
  ControlledExecutor(ObjectMapper mapper,org.springframework.jdbc.core.JdbcTemplate db){super(mapper,db,ROOT.resolve("unused-catalog.json").toString());}
  @Override public synchronized TestRun startManaged(Target target){if("fail".equals(mode))throw new IllegalArgumentException("executor unavailable");if("controlled".equals(mode)){controlled=new TestRun("controlled",target,"QUEUED","now",null,null,"",null,null);return controlled;}return super.startManaged(target);}
  @Override public TestRun get(String id){return "controlled".equals(id)?controlled:super.get(id);}
  @Override public TestRun cancel(String id){if("controlled".equals(id)){cancelled=true;controlled=new TestRun(id,controlled.target(),"CANCELLED","now","now",null,"",null,"cancelled");return controlled;}return super.cancel(id);}
 }
 @org.springframework.boot.test.context.TestConfiguration static class Config {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary ControlledExecutor controlledExecutor(ObjectMapper mapper,org.springframework.jdbc.core.JdbcTemplate db){return new ControlledExecutor(mapper,db);}
 }
 @AfterEach void resetExecutor(){executor.mode="real";executor.cancelled=false;}
 long task;JsonNode spec;
 @BeforeEach void setup()throws Exception{
  executor.mode="real";task=tasks.insert(new Task(null,"make game","produce","{}",20,true,"ACTIVE",null)).id();
  JsonNode brief=artifacts.save(new ProductionArtifactService.Request(task,null,null,null,"ProductionBrief",mapper.readTree("{\"title\":\"Brief\",\"mode\":\"NEW\",\"genre\":\"survival\",\"targetPlatform\":\"web\",\"targetPlayer\":\"casual\",\"scope\":\"slice\"}"),List.of()));
  var content=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree("""
   {"title":"Production slice","genre":"survival","targetPlatform":"web","coreLoop":"survive","endConditions":"survive round","restartBehavior":"reset","systems":{"survivalWeb":{"mechanic":"heat-burst","duration":6,"enemySpeed":70,"spawnInterval":1,"seed":42}},"controls":["move"],"states":["play","end"],"contentScope":[],"assetNeeds":[],"exclusions":[],"openQuestions":[],"requirements":[{"id":"R1","description":"restart","acceptanceIds":["A1"]}],"acceptanceCriteria":[{"id":"A1","requirementId":"R1","expected":"reset","method":"browser"}]}
   """);content.put("briefRef",brief.path("artifactId").asText());
  spec=artifacts.save(new ProductionArtifactService.Request(task,null,null,null,"GameSpec",content,List.of(new ProductionArtifactService.Ref(brief.path("artifactId").asText(),1,brief.path("contentHash").asText()))));
 }
 ProductionJobService.Request request(String key){return new ProductionJobService.Request(task,spec.path("artifactId").asText(),1,key);}
 JsonNode await(String id)throws Exception{long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){var j=jobs.get(id);if(!Set.of("PREPARING","QUEUED","RUNNING").contains(j.path("status").asText()))return j;Thread.sleep(30);}throw new AssertionError("Production timed out");}
 @Test void createsActualBuildAndReusesRequestKey()throws Exception{
  var first=jobs.start(request("once"));var result=await(first.path("id").asText());assertThat(result.path("status").asText()).isEqualTo("BUILT");
  Path workspace=Path.of(result.path("result").path("workspace").asText());assertThat(workspace).startsWith(ROOT.resolve("jobs"));assertThat(Files.exists(workspace.resolve(result.path("result").path("build").path("buildId").asText()).resolve("index.html"))).isTrue();
  assertThat(result.path("specHash").asText()).isEqualTo(spec.path("contentHash").asText());assertThat(jobs.start(request("once")).path("id")).isEqualTo(first.path("id"));
 }
 JsonNode verified(String key)throws Exception{var built=await(jobs.start(request(key)).path("id").asText());jobs.validate(built.path("id").asText(),null);return awaitVerification(built.path("id").asText());}

 @Test void listWritesOnlyWhenPersistedExecutionChanges() throws Exception {
  var verified = verified("poll-write-count");
  String id = verified.path("id").asText();
  // Simulate a terminal executor outcome whose production summary is still stale.
  db.update("UPDATE production_job SET record_json=json_remove(record_json,'$.validationDecision') WHERE id=?", id);
  db.execute("CREATE TABLE production_poll_writes (id TEXT)");
  db.execute("""
      CREATE TRIGGER measure_production_poll_writes AFTER UPDATE ON production_job
      BEGIN INSERT INTO production_poll_writes VALUES (NEW.id); END
      """);
  try {
   for (int poll = 0; poll < 20; poll++) {
    var row = jobs.list().stream().filter(job -> id.equals(job.path("id").asText())).findFirst().orElseThrow();
    assertThat(row.path("validationDecision").asText()).isEqualTo("PASS");
    assertThat(row.path("iteration").path("phase").asText()).isEqualTo("REVIEW");
    assertThat(db.queryForObject("SELECT count(*) FROM production_poll_writes WHERE id=?", Integer.class, id)).isOne();
   }
  } finally {
   db.execute("DROP TRIGGER measure_production_poll_writes");
   db.execute("DROP TABLE production_poll_writes");
  }
 }

 @Test void resumesAfterContractFingerprintWasSavedButFileWasNotPublished() throws Exception {
  var built = await(jobs.start(request("resume-contract")).path("id").asText());
  String id = built.path("id").asText();
  executor.mode = "fail";
  assertThatThrownBy(() -> jobs.validate(id, null)).hasMessageContaining("executor unavailable");
  var prepared = jobs.get(id);
  Path contract = Path.of(prepared.path("jobDirectory").asText()).resolve("acceptance-contract.json");
  byte[] original = Files.readAllBytes(contract);
  Files.delete(contract);
  executor.mode = "real";
  jobs.validate(id, null);
  var verified = awaitVerification(id);
  assertThat(verified.path("validationDecision").asText()).isEqualTo("PASS");
  assertThat(verified.path("acceptanceContractHash")).isEqualTo(prepared.path("acceptanceContractHash"));
  assertThat(Files.readAllBytes(contract)).isEqualTo(original);
 }

 @Test void linksCompletedVerificationAfterInterruptionWithoutStartingAnotherRun() throws Exception {
  var verified = verified("resume-link");
  String id = verified.path("id").asText();
  db.update("UPDATE production_job SET record_json=json_remove(record_json,'$.validationRunId') WHERE id=?", id);
  assertThat(jobs.validate(id, null).path("validationRunId")).isEqualTo(verified.path("validationRunId"));
 }

 @Test void retriesAnInterruptedExecutionWithTheSameFrozenContract() throws Exception {
  var previous = verified("restart-execution");
  String id = previous.path("id").asText();
  String oldRun = previous.path("validationRunId").asText();
  db.update("UPDATE local_test_run SET record_json=json_set(record_json,'$.status','RUNNING') WHERE id=?", oldRun);
  // Recreate the executor startup checkpoint in the isolated test database.
  try (var restarted = new LocalTestService(mapper, db, ROOT.resolve("unused-catalog.json").toString())) {
   assertThat(restarted.get(oldRun).status()).isEqualTo("INTERRUPTED");
  }
  assertThat(jobs.get(id).path("validationStatus").asText()).isEqualTo("INTERRUPTED");
  jobs.validate(id, null);
  var recovered = awaitVerification(id);
  assertThat(recovered.path("validationRunId").asText()).isNotEqualTo(oldRun);
  assertThat(recovered.path("acceptanceContractHash")).isEqualTo(previous.path("acceptanceContractHash"));
  assertThat(recovered.path("validationDecision").asText()).isEqualTo("PASS");
  assertThat(executor.get(oldRun).status()).isEqualTo("INTERRUPTED");
 }

 @Test void legacyUnlinkedContractMustMatchExpectedBytes() throws Exception {
  var built = await(jobs.start(request("legacy-contract")).path("id").asText());
  String id = built.path("id").asText();
  executor.mode = "fail";
  assertThatThrownBy(() -> jobs.validate(id, null)).hasMessageContaining("executor unavailable");
  Path contract = Path.of(built.path("jobDirectory").asText()).resolve("acceptance-contract.json");
  byte[] original = Files.readAllBytes(contract);
  db.update("""
          UPDATE production_job SET record_json=json_remove(record_json,
          '$.acceptanceContractHash','$.acceptanceContractText','$.acceptanceProgramHash','$.acceptanceBuilderHash')
          WHERE id=?
          """, id);
  Files.writeString(contract, "{}");
  executor.mode = "real";
  assertThatThrownBy(() -> jobs.validate(id, null)).hasMessageContaining("contract missing or changed");
  assertThat(Files.readString(contract)).isEqualTo("{}");
  Files.write(contract, original);
  jobs.validate(id, null);
  assertThat(awaitVerification(id).path("validationDecision").asText()).isEqualTo("PASS");
 }
 JsonNode awaitVerification(String id)throws Exception{long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){var j=jobs.get(id);if(!Set.of("QUEUED","RUNNING").contains(j.path("validationStatus").asText()))return j;Thread.sleep(30);}throw new AssertionError("Verification timed out");}
 LocalTestService.TestRun awaitExecution(String id)throws Exception{long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){var r=executor.get(id);if(!Set.of("QUEUED","RUNNING").contains(r.status()))return r;Thread.sleep(30);}throw new AssertionError("Execution timed out");}
 @Test void independentVerificationRebuildsAndProtectsItsExecutionInputs()throws Exception{
  var j=verified("verified");assertThat(j.path("validationDecision").asText()).isEqualTo("PASS");var run=executor.get(j.path("validationRunId").asText());assertThat(run.evidence().path("checks").size()).isEqualTo(6);assertThat(j.path("validationChecks").size()).isEqualTo(6);assertThat(j.path("validationScope").asText()).isEqualTo("survival-web-engine-v1");
  assertThat(jobs.validate(j.path("id").asText(),null).path("validationRunId")).isEqualTo(j.path("validationRunId"));
  assertThat(run.evidence().path("validationBuild").path("buildId").asText()).isNotEqualTo(j.path("result").path("build").path("buildId").asText());
  for(String file:List.of("config.json","build-project.mjs","specification.json")){
   String before=evidence.binding(run.id()).path("source").path(file).asText();assertThatThrownBy(()->changes.create(run.id(),"Weaken acceptance",List.of(new com.aigamelab.localtest.LocalChangeService.Edit(file,before,"always pass")))).hasMessageContaining("Protected");
  }
 }
 @Test void brokenEngineProducesFindingAndApprovedIsolatedFixPassesFrozenChecks()throws Exception{
  var j=await(jobs.start(request("fix-game")).path("id").asText());Path original=Path.of(j.path("result").path("workspace").asText()).resolve("engine.mjs");String good=Files.readString(original),bad=good.replace("*210*dt","*0*dt");assertThat(bad).isNotEqualTo(good);Files.writeString(original,bad);
  jobs.validate(j.path("id").asText(),null);j=awaitVerification(j.path("id").asText());assertThat(j.path("validationDecision").asText()).isEqualTo("FAIL");String baseline=j.path("validationRunId").asText();var failed=executor.get(baseline);
  assertThat(evidence.importChecks(failed).path("findingIds").size()).isGreaterThan(0);
  var p=changes.create(baseline,"Restore movement",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",evidence.binding(baseline).path("source").path("engine.mjs").asText(),good)));
  var approved=changes.approve(p.id());var result=awaitExecution(approved.validationRunId());assertThat(result.status()).isEqualTo("PASSED");assertThat(changes.get(p.id()).status()).isEqualTo("VALIDATED");
  assertThat(Files.readString(original)).isEqualTo(bad);assertThat(result.evidence().path("contractHash")).isEqualTo(failed.evidence().path("contractHash"));assertThat(result.target().protectedFiles()).contains("build-project.mjs");
 }
 @Test void failedCandidatesFormOneDurableFiveProposalChain()throws Exception{
  var j=verified("iterations");String id=j.path("id").asText(),first=j.path("validationRunId").asText();
  String original=evidence.source(first,"engine.mjs").path("text").asText();String current=first;
  for(int attempt=1;attempt<=5;attempt++){
   var source=evidence.source(current,"engine.mjs");String broken=original.replace("*210*dt","*0*dt")+"\n// attempt "+attempt;
   var p=changes.create(current,"Repair attempt "+attempt,List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),broken)));
   final String before=current;assertThatThrownBy(()->changes.create(before,"Parallel proposal",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),broken)))).hasMessageContaining(attempt==5?"budget":"unresolved");
   var approved=changes.approve(p.id());awaitExecution(approved.validationRunId());assertThat(changes.get(p.id()).status()).isEqualTo("REGRESSION_FAILED");current=approved.validationRunId();
   var state=jobs.get(id).path("iteration");assertThat(state.path("repairCount").asInt()).isEqualTo(attempt);assertThat(state.path("currentRunId").asText()).isEqualTo(current);
   assertThat(state.path("phase").asText()).isEqualTo(attempt==5?"FAILED":"IMPLEMENTING");
   if(attempt<5)assertThatThrownBy(()->changes.create(first,"Stale original baseline",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",evidence.source(first,"engine.mjs").path("sha256").asText(),original)))).hasMessageContaining("current production");
  }
  var state=jobs.get(id).path("iteration");assertThat(state.path("attempts").size()).isEqualTo(5);
  var recovered=new ProductionIterationService(db,mapper,executor);application.getAutowireCapableBeanFactory().autowireBean(recovered);
  assertThat(recovered.snapshot(jobs.get(id))).isEqualTo(state);
  var source=evidence.source(current,"engine.mjs");String last=current;assertThatThrownBy(()->changes.create(last,"Sixth attempt",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),original)))).hasMessageContaining("budget");
  assertThat(Files.readString(Path.of(j.path("result").path("workspace").asText()).resolve("engine.mjs"))).isEqualTo(original);
 }
 @Test void cancellationBlocksPendingApprovalAndNewVerification()throws Exception{
  var j=verified("cancel-iteration");String id=j.path("id").asText(),base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  var p=changes.create(base,"Pending repair",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText()+"\n// candidate")));
  assertThat(jobs.cancel(id).path("iteration").path("phase").asText()).isEqualTo("CANCELLED");
  assertThatThrownBy(()->changes.approve(p.id())).hasMessageContaining("cancelled");assertThat(changes.get(p.id()).workspace()).isNull();
  assertThatThrownBy(()->jobs.validate(id,null)).hasMessageContaining("cancelled");
 }
 @Test void concurrentProposalsHaveOneDurableWinner()throws Exception{
  var j=verified("concurrent-proposals");String base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  var workers=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{
   java.util.concurrent.Callable<Boolean> submit=()->{start.await();try{changes.create(base,"Concurrent candidate",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText()+"\n// concurrent")));return true;}catch(com.aigamelab.common.BusinessException expected){return false;}};
   var a=workers.submit(submit);var b=workers.submit(submit);start.countDown();int winners=(a.get(15,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(b.get(15,java.util.concurrent.TimeUnit.SECONDS)?1:0);
   assertThat(winners).isEqualTo(1);assertThat(jobs.get(j.path("id").asText()).path("iteration").path("repairCount").asInt()).isEqualTo(1);
  }finally{workers.shutdownNow();}
 }
 @Test void rejectedAttemptsCountAndSuccessfulCandidateEntersReview()throws Exception{
  var j=verified("reject-and-pass");String id=j.path("id").asText(),base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  var edits=List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText().replace("*210*dt","*220*dt")));
  var rejected=changes.create(base,"Rejected change",edits);changes.reject(rejected.id());var accepted=changes.approve(changes.create(base,"Approved change",edits).id());awaitExecution(accepted.validationRunId());
  var state=jobs.get(id).path("iteration");assertThat(state.path("phase").asText()).isEqualTo("REVIEW");assertThat(state.path("repairCount").asInt()).isEqualTo(2);assertThat(state.path("currentRunId").asText()).isEqualTo(accepted.validationRunId());
 }
 @Test void incompleteCandidateIsNotHiddenByThePassingOriginalBaseline()throws Exception{
  var j=verified("incomplete-candidate");String base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  var p=changes.approve(changes.create(base,"Break module loading",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),"throw new Error('candidate runtime failure');"))).id());awaitExecution(p.validationRunId());
  assertThat(jobs.get(j.path("id").asText()).path("iteration").path("phase").asText()).isEqualTo("WAITING_INPUT");
 }
 @Test void cancelledAgentCannotApproveAnAlreadyPendingProposal()throws Exception{
  var j=verified("cancelled-owner");String base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  var p=changes.create(base,"Pending owned candidate",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText()+"\n// candidate")));
  var owner=application.getBean(com.aigamelab.repository.RunRepository.class).insert(new com.aigamelab.domain.Run(null,task,"CANCELLED",null,"checking",0,20,100000,0,null,null,null,null,null));
  db.update("UPDATE production_job SET record_json=json_set(record_json,'$.ownerRunId',?) WHERE id=?",owner.id(),j.path("id").asText());
  assertThatThrownBy(()->changes.approve(p.id())).hasMessageContaining("owning Agent stopped");assertThat(changes.get(p.id()).workspace()).isNull();
  assertThat(jobs.get(j.path("id").asText()).path("iteration").path("phase").asText()).isEqualTo("CANCELLED");
 }
 @Test void approvalRechecksTheWaitingBuilderSessionDeadline()throws Exception{
  var j=verified("expired-approval");String base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  application.getBean(ManufacturingScopeService.class).confirm(new ManufacturingScopeService.Request(
          task,spec.path("artifactId").asText(),1,spec.path("contentHash").asText()));
  var task=jobs.createBuilderTask(this.task,spec.path("artifactId").asText(),1,"Approve within budget");var runs=application.getBean(com.aigamelab.repository.RunRepository.class);var sessions=application.getBean(BuilderSessionService.class);
  var run=runs.insert(new com.aigamelab.domain.Run(null,task.id(),"RUNNING",null,"checking",0,20,100000,0,null,null,null,null,null));sessions.claim(task,run);sessions.bind(run.id(),j.path("id").asText());db.update("UPDATE production_job SET record_json=json_set(record_json,'$.ownerRunId',?) WHERE id=?",run.id(),j.path("id").asText());
  var p=changes.create(base,"Awaiting approval",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText()+"\n// pending")));
  runs.transitionIf(run.id(),"RUNNING",com.aigamelab.domain.RunStatus.WAITING_APPROVAL);db.update("UPDATE builder_session SET record_json=json_set(record_json,'$.deadlineEpochMs',0) WHERE task_id=?",task.id());
  assertThatThrownBy(()->changes.approve(p.id())).hasMessageContaining("deadline");assertThat(changes.get(p.id()).workspace()).isNull();
 }
 Map<String,byte[]> archive(String id)throws Exception{
  var result=new TreeMap<String,byte[]>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(candidates.download(id)))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),zip.readAllBytes());}return result;
 }
 @Test void freezesExactVerifiedBuildAndExcludesUnregisteredSecrets()throws Exception{
  var j=verified("package");Path workspace=Path.of(j.path("result").path("workspace").asText());Files.writeString(workspace.resolve(".env"),"PRIVATE_CANARY_DO_NOT_EXPORT");
  var c=candidates.create(j.path("id").asText(),j.path("validationRunId").asText());String id=c.path("id").asText();var files=archive(id);
  assertThat(files).hasSize(25);assertThat(files.keySet()).doesNotContain(".env","source/.env");assertThat(files.values()).allSatisfy(bytes->assertThat(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("PRIVATE_CANARY_DO_NOT_EXPORT"));
  assertThat(c.path("manifest").path("humanAccepted").asBoolean()).isFalse();assertThat(ProductionCandidateService.hash(candidates.download(id))).isEqualTo(c.path("archiveSha256").asText());
  assertThat(candidates.create(j.path("id").asText(),j.path("validationRunId").asText()).path("id").asText()).isEqualTo(id);
  byte[] frozen=candidates.download(id);Files.writeString(workspace.resolve("engine.mjs"),"changed after freeze");assertThat(candidates.download(id)).isEqualTo(frozen);
 }
 @Test void rejectsSourceAndBuildDriftBeforeFreezing()throws Exception{
  var a=verified("package-source-drift");Files.writeString(Path.of(a.path("result").path("workspace").asText()).resolve("engine.mjs"),"changed");assertThatThrownBy(()->candidates.create(a.path("id").asText(),a.path("validationRunId").asText())).hasMessageContaining("Source tree changed");
  var b=verified("package-build-drift");var r=executor.get(b.path("validationRunId").asText());Files.writeString(Path.of(r.target().workingDirectory()).resolve(r.evidence().path("validationBuild").path("buildId").asText()).resolve("engine.mjs"),"changed output");
  assertThatThrownBy(()->candidates.create(b.path("id").asText(),r.id())).hasMessageContaining("Build does not match");assertThat(candidates.list(b.path("id").asText()).get(0).path("status").asText()).isEqualTo("FAILED");
 }
 @Test void rejectsOldBaselineAndPackagesApprovedCandidateInstead()throws Exception{
  var j=verified("package-current");String id=j.path("id").asText(),base=j.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");
  var p=changes.approve(changes.create(base,"Accepted candidate",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText().replace("*210*dt","*220*dt")))).id());awaitExecution(p.validationRunId());
  assertThatThrownBy(()->candidates.create(id,base)).hasMessageContaining("current verified");var c=candidates.create(id,p.validationRunId());assertThat(new String(archive(c.path("id").asText()).get("build/engine.mjs"),java.nio.charset.StandardCharsets.UTF_8)).contains("*220*dt");
 }
 @Test void fixedFileOrArchiveTamperingBlocksDownloadAndPreview()throws Exception{
  var j=verified("package-tamper");var c=candidates.create(j.path("id").asText(),j.path("validationRunId").asText());String id=c.path("id").asText();Path engine=ROOT.resolve("candidates").resolve(id).resolve("build/engine.mjs");byte[] original=Files.readAllBytes(engine);Files.writeString(engine,"tampered");
  assertThatThrownBy(()->candidates.download(id)).hasMessageContaining("Fixed candidate changed");try(var preview=new CandidatePreviewService(candidates,mapper,3)){assertThatThrownBy(()->preview.start(id)).hasMessageContaining("Fixed candidate changed");}
  Files.write(engine,original);Files.writeString(ROOT.resolve("candidates").resolve(id+".zip"),"zip changed");assertThatThrownBy(()->candidates.download(id)).hasMessageContaining("archive changed");
 }
 @Test void previewServesOnlyFixedBuildAndStopsOrExpires()throws Exception{
  var j=verified("package-preview");var c=candidates.create(j.path("id").asText(),j.path("validationRunId").asText());String id=c.path("id").asText();var client=java.net.http.HttpClient.newHttpClient();
  try(var preview=new CandidatePreviewService(candidates,mapper,2)){
   var info=preview.start(id);String url=info.path("url").asText();assertThat(preview.start(id).path("url").asText()).isEqualTo(url);
   var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("sandbox allow-scripts","connect-src http://127.0.0.1");
   assertThat(client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url+"source/engine.mjs")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
   Thread.sleep(2200);assertThatThrownBy(()->client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString())).isInstanceOf(java.io.IOException.class);
   preview.stop(id);preview.stop(id);
  }
 }
 @Test void candidateRecordsAndHashesSurviveServiceRestart()throws Exception{
  var j=verified("package-restart");var c=candidates.create(j.path("id").asText(),j.path("validationRunId").asText());
  var interrupted=mapper.createObjectNode().put("id","interrupted-candidate").put("productionId",j.path("id").asText()).put("validationRunId",j.path("localRunId").asText()).put("status","PREPARING");db.update("INSERT INTO production_candidate VALUES (?,?,?,?)","interrupted-candidate",j.path("id").asText(),j.path("localRunId").asText(),interrupted.toString());
  var restored=new ProductionCandidateService(db,mapper,jobs,executor,evidence,ROOT.resolve("candidates").toString(),REPO.toString());assertThat(restored.get(c.path("id").asText())).isEqualTo(c);assertThat(restored.download(c.path("id").asText())).isEqualTo(candidates.download(c.path("id").asText()));assertThat(restored.get("interrupted-candidate").path("status").asText()).isEqualTo("INTERRUPTED");assertThatThrownBy(()->restored.download("interrupted-candidate")).hasMessageContaining("not ready");
 }
 @Test void previewCapacityIsBoundedAndReleasedOnStop()throws Exception{
  var ids=new ArrayList<String>();for(int i=0;i<3;i++){var j=verified("capacity-"+i);ids.add(candidates.create(j.path("id").asText(),j.path("validationRunId").asText()).path("id").asText());}
  try(var preview=new CandidatePreviewService(candidates,mapper,300)){preview.start(ids.get(0));preview.start(ids.get(1));assertThatThrownBy(()->preview.start(ids.get(2))).hasMessageContaining("At most two");preview.stop(ids.get(0));assertThat(preview.start(ids.get(2)).path("status").asText()).isEqualTo("RUNNING");}
 }
 @Test void refusesUnverifiedBuildAndFrozenAcceptanceDrift()throws Exception{
  var built=await(jobs.start(request("package-no-verification")).path("id").asText());assertThatThrownBy(()->candidates.create(built.path("id").asText(),"missing")).hasMessageContaining("current verified");
  var j=verified("package-contract-drift");Files.writeString(Path.of(j.path("jobDirectory").asText()).resolve("acceptance-contract.json"),"{}");assertThatThrownBy(()->candidates.create(j.path("id").asText(),j.path("validationRunId").asText())).hasMessageContaining("Frozen acceptance inputs");
 }
 String reviewCandidate(String key)throws Exception{var j=verified(key);return candidates.create(j.path("id").asText(),j.path("validationRunId").asText()).path("id").asText();}

 @Test void browserReceiptBindsTheFixedPackageAndClosesTheManualGateOnFailure() throws Exception {
  var content = (com.fasterxml.jackson.databind.node.ObjectNode) spec.path("content").deepCopy();
  var criterion = (com.fasterxml.jackson.databind.node.ObjectNode) content.path("acceptanceCriteria").get(0);
  criterion.putObject("browserProbe").put("profile", CandidateBrowserEvidenceService.PROFILE).put("field", "heat").put("expected", 0);
  spec = artifacts.save(new ProductionArtifactService.Request(task, null, null, 0, "GameSpec", content,
          List.of(mapper.treeToValue(spec.path("inputRefs").get(0), ProductionArtifactService.Ref.class))));
  String id = reviewCandidate("browser-gate");
  assertThat(browser.plan(id).path("checks").get(0).path("outcome").asText()).isEqualTo("NOT_TESTED");
  assertThat(reviews.save(id, reviewRequest(id, 0, "ACCEPTED", "PASS", "Operator observed reset"))
          .path("gate").path("eligibleForDelivery").asBoolean()).isTrue();
  var candidate = candidates.get(id);
  var observation = new CandidateBrowserEvidenceService.Observation(UUID.randomUUID().toString(),
          candidate.path("manifestHash").asText(), candidate.path("archiveSha256").asText(), "Chromium fixture",
          "胜利！存活目标达成", "进行中", "生命 100 · 热量 6 · 得分 0 · 时间 0.0 / 6 秒", java.time.Instant.now());
  var saved = browser.record(id, observation);
  assertThat(browser.record(id, observation)).isEqualTo(saved);
  assertThat(saved.path("checks").get(0).path("outcome").asText()).isEqualTo("FAIL");
  assertThat(saved.path("checks").get(0).path("actual").asInt()).isEqualTo(6);
  assertThat(browser.execution(id, observation.executionId()).path("specHash")).isEqualTo(spec.path("contentHash"));
  assertThat(reviews.view(id).path("gate").path("eligibleForDelivery").asBoolean()).isFalse();
  assertThatThrownBy(() -> reviews.save(id, reviewRequest(id, 1, "ACCEPTED", "PASS", "Still pass")))
          .hasMessageContaining("failed browser probe");
  String second = reviewCandidate("browser-other-candidate");
  assertThat(browser.latest(second)).isEmpty();
  assertThatThrownBy(() -> browser.record(second, observation)).hasMessageContaining("different fixed package");
 }
 @Test void inputProbeUsesFixedThresholdAndTimeoutClosesDelivery() throws Exception {
  var content = (com.fasterxml.jackson.databind.node.ObjectNode) spec.path("content").deepCopy();
  var criterion = (com.fasterxml.jackson.databind.node.ObjectNode) content.path("acceptanceCriteria").get(0);
  criterion.putObject("browserProbe").put("profile", CandidateBrowserEvidenceService.INPUT_PROFILE)
          .put("field", "heat").put("expected", 30);
  spec = artifacts.save(new ProductionArtifactService.Request(task, null, null, 0, "GameSpec", content,
          List.of(mapper.treeToValue(spec.path("inputRefs").get(0), ProductionArtifactService.Ref.class))));
  String id = reviewCandidate("browser-input-gate");
  var candidate = candidates.get(id);
  String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8ioAAAAASUVORK5CYII=";
  String before = "生命 100 · 热量 6 · 得分 0 · 时间 0.1 / 6 秒";
  var pass = new CandidateBrowserEvidenceService.Interaction(before, "生命 100 · 热量 49 · 得分 0 · 时间 0.2 / 6 秒", true, 100, png);
  var timeout = new CandidateBrowserEvidenceService.Interaction(before, before, true, 2500, png);
  java.util.function.Function<CandidateBrowserEvidenceService.Interaction, CandidateBrowserEvidenceService.Observation> observe =
          input -> new CandidateBrowserEvidenceService.Observation(UUID.randomUUID().toString(),
                  candidate.path("manifestHash").asText(), candidate.path("archiveSha256").asText(),
                  "Chromium fixture", null, null, null, java.time.Instant.now(), input);
  var observed = browser.record(id, observe.apply(pass)).path("checks").get(0);
  assertThat(observed.path("actual").asInt()).isEqualTo(43);
  assertThat(observed.path("outcome").asText()).isEqualTo("PASS");
  assertThat(reviews.save(id, reviewRequest(id, 0, "ACCEPTED", "PASS", "Fixture observation"))
          .path("gate").path("eligibleForDelivery").asBoolean()).isTrue();
  assertThatThrownBy(() -> browser.record(id, observe.apply(null))).hasMessageContaining("input trace");
  var failed = browser.record(id, observe.apply(timeout)).path("checks").get(0);
  assertThat(failed.path("reason").asText()).isEqualTo("RESOURCE_CHANGE_TIMEOUT");
  assertThat(reviews.view(id).path("gate").path("eligibleForDelivery").asBoolean()).isFalse();
 }
 CandidateReviewService.Request reviewRequest(String id,int revision,String decision,String outcome,String note){return new CandidateReviewService.Request(revision,candidates.get(id).path("manifestHash").asText(),"Fixture operator","Bound fixed-build observation",decision,List.of(new CandidateReviewService.Check("A1",outcome,note)));}
 @Test void enginePassDoesNotInventManualSpecificationCoverage()throws Exception{
  String id=reviewCandidate("manual-unreviewed");var view=reviews.view(id);assertThat(view.path("revision").asInt()).isZero();assertThat(view.path("decision").asText()).isEqualTo("UNREVIEWED");assertThat(view.path("engineChecks").size()).isEqualTo(6);assertThat(view.path("checks").get(0).path("expected").asText()).isEqualTo("reset");assertThat(view.path("coverage").path("untested").asInt()).isEqualTo(1);assertThat(view.path("coverage").path("requirements").get(0).path("status").asText()).isEqualTo("NOT_TESTED");assertThat(view.path("gate").path("eligibleForDelivery").asBoolean()).isFalse();assertThatThrownBy(()->reviews.receipt(id)).hasMessageContaining("No manual review");
 }
 @Test void manualAcceptanceRequiresCompleteBoundObservedChecks()throws Exception{
  String id=reviewCandidate("manual-invalid");assertThatThrownBy(()->reviews.save(id,reviewRequest(id,0,"ACCEPTED","NOT_TESTED",""))).hasMessageContaining("every specification");assertThatThrownBy(()->reviews.save(id,reviewRequest(id,0,"ACCEPTED","PASS"," "))).hasMessageContaining("observation");
  var valid=reviewRequest(id,0,"ACCEPTED","PASS","Reset observed");assertThatThrownBy(()->reviews.save(id,new CandidateReviewService.Request(0,"wrong",valid.reviewer(),valid.summary(),valid.decision(),valid.checks()))).hasMessageContaining("precondition");
  assertThatThrownBy(()->reviews.save(id,new CandidateReviewService.Request(0,valid.manifestHash(),valid.reviewer(),valid.summary(),"DRAFT",List.of(new CandidateReviewService.Check("other","PASS","Observed"))))).hasMessageContaining("missing");
  assertThatThrownBy(()->reviews.save(id,new CandidateReviewService.Request(0,valid.manifestHash(),valid.reviewer(),valid.summary(),"DRAFT",List.of()))).hasMessageContaining("All fixed");assertThat(reviews.history(id)).isEmpty();
 }
 @Test void acceptanceIsIdempotentAndDoesNotRewriteArchiveOrManifest()throws Exception{
  String id=reviewCandidate("manual-accepted");byte[] archive=candidates.download(id);var candidate=candidates.get(id).deepCopy();var request=reviewRequest(id,0,"ACCEPTED","PASS","Started fixed build, restarted, observed reset");var saved=reviews.save(id,request);
  assertThat(saved.path("gate").path("eligibleForDelivery").asBoolean()).isTrue();assertThat(saved.path("coverage").path("requirements").get(0).path("status").asText()).isEqualTo("PASS");assertThat(reviews.save(id,request).path("revision").asInt()).isEqualTo(1);assertThat(reviews.save(id,reviewRequest(id,1,"ACCEPTED","PASS",request.checks().get(0).evidenceNote())).path("revision").asInt()).isEqualTo(1);
  var receipt=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(reviews.receipt(id));assertThat(receipt.path("candidateId").asText()).isEqualTo(id);assertThat(receipt.path("archiveSha256")).isEqualTo(candidate.path("archiveSha256"));String hash=receipt.remove("reviewHash").asText();assertThat(ProductionCandidateService.hash(receipt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(hash);assertThat(candidates.download(id)).isEqualTo(archive);assertThat(candidates.get(id)).isEqualTo(candidate);assertThat(candidate.path("manifest").path("humanAccepted").asBoolean()).isFalse();assertThat(reviews.history(id)).hasSize(1);
 }
 @Test void revisionsPreserveHistoryCloseGateAndSurviveRestart()throws Exception{
  String id=reviewCandidate("manual-history");reviews.save(id,reviewRequest(id,0,"ACCEPTED","PASS","Reset observed"));var first=reviews.history(id).get(0).deepCopy();reviews.save(id,reviewRequest(id,1,"REJECTED","FAIL","Reset failed on another observation"));assertThat(reviews.view(id).path("gate").path("eligibleForDelivery").asBoolean()).isFalse();
  assertThat(reviews.history(id)).hasSize(2);assertThat(reviews.history(id,2)).containsExactly(first);assertThatThrownBy(()->reviews.save(id,reviewRequest(id,1,"DRAFT","NOT_TESTED",""))).hasMessageContaining("changed");reviews.save(id,reviewRequest(id,2,"DRAFT","NOT_TESTED",""));assertThat(reviews.view(id).path("gate").path("humanAccepted").asBoolean()).isFalse();
  var restored=new CandidateReviewService(candidates,jobs,mapper,db,application.getBean(org.springframework.transaction.PlatformTransactionManager.class),browser);assertThat(restored.view(id)).isEqualTo(reviews.view(id));assertThat(restored.receipt(id)).isEqualTo(reviews.receipt(id));assertThat(restored.history(id)).hasSize(3);assertThatThrownBy(()->reviews.history(id,0)).hasMessageContaining("cursor");
 }
 @Test void pendingWorkAndNewCandidateInvalidateEarlierAcceptance()throws Exception{
  String id=reviewCandidate("manual-current");var c=candidates.get(id);reviews.save(id,reviewRequest(id,0,"ACCEPTED","PASS","Reset observed"));String base=c.path("validationRunId").asText();var source=evidence.source(base,"engine.mjs");var p=changes.create(base,"Next version",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText()+"\n// reviewed successor")));
  assertThat(reviews.view(id).path("gate").path("eligibleForDelivery").asBoolean()).isFalse();assertThatThrownBy(()->reviews.save(id,reviewRequest(id,1,"ACCEPTED","PASS","Repeated observation"))).hasMessageContaining("current REVIEW");var approved=changes.approve(p.id());awaitExecution(approved.validationRunId());candidates.create(c.path("productionId").asText(),approved.validationRunId());assertThat(reviews.view(id).path("gate").path("candidateCurrent").asBoolean()).isFalse();assertThat(reviews.history(id)).hasSize(1);
 }
 @Test void corruptPackageCannotBeReviewedOrExportAReceipt()throws Exception{
  String id=reviewCandidate("manual-corrupt");var request=reviewRequest(id,0,"DRAFT","NOT_TESTED","");reviews.save(id,request);Files.writeString(ROOT.resolve("candidates").resolve(id+".zip"),"changed");assertThatThrownBy(()->reviews.view(id)).hasMessageContaining("archive changed");assertThatThrownBy(()->reviews.save(id,request)).hasMessageContaining("archive changed");assertThatThrownBy(()->reviews.receipt(id)).hasMessageContaining("archive changed");assertThat(reviews.history(id)).hasSize(1);
 }
 @Test void competingReviewRevisionsHaveOneWinnerWithoutDeadlock()throws Exception{
  String id=reviewCandidate("manual-concurrent");var workers=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{java.util.concurrent.Callable<Boolean> submit=()->{start.await();try{reviews.save(id,reviewRequest(id,0,"DRAFT","NOT_TESTED",Thread.currentThread().getName()));return true;}catch(com.aigamelab.common.BusinessException expected){return false;}};var a=workers.submit(submit);var b=workers.submit(submit);start.countDown();assertThat(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);assertThat(reviews.history(id)).hasSize(1);}finally{workers.shutdownNow();}
 }
 @Test void rejectsCrossTaskSpecificationBeforeStarting(){assertThatThrownBy(()->jobs.start(new ProductionJobService.Request(task+1000,spec.path("artifactId").asText(),1,"cross"))).hasMessageContaining("does not belong");}
 @Test void invalidTemplateParametersFailWithSpecificEvidence()throws Exception{
  var content=spec.path("content").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)content.path("systems").path("survivalWeb")).put("duration",-1);
  var refs=new ArrayList<ProductionArtifactService.Ref>();for(var r:spec.path("inputRefs"))refs.add(new ProductionArtifactService.Ref(r.path("id").asText(),r.path("revision").asInt(),r.path("hash").asText()));
  spec=artifacts.save(new ProductionArtifactService.Request(task,null,null,null,"GameSpec",content,refs));
  var result=await(jobs.start(request("bad-parameter")).path("id").asText());assertThat(result.path("status").asText()).isEqualTo("FAILED");assertThat(result.path("error").asText()).contains("duration");
 }
 @Test void processFailureIsVisibleAndRetained()throws Exception{
  executor.mode="fail";var j=jobs.start(request("fail"));assertThat(j.path("status").asText()).isEqualTo("FAILED");assertThat(jobs.get(j.path("id").asText()).path("error").asText()).contains("executor unavailable");
 }
 @Test void cancellationDelegatesToSharedProcessOwner(){
  executor.mode="controlled";
  var j=jobs.start(request("cancel"));assertThat(jobs.cancel(j.path("id").asText()).path("status").asText()).isEqualTo("CANCELLED");assertThat(executor.cancelled).isTrue();
 }
 @Test void restartDoesNotReplayUnlinkedPreparation(){
  var j=mapper.createObjectNode().put("id","interrupted").put("taskId",task).put("requestKey","old").put("status","PREPARING");j.putNull("localRunId");
  db.update("INSERT INTO production_job VALUES (?,?,?,?)","interrupted",task,"old",j.toString());
  var recovered=new ProductionJobService(db,mapper,artifacts,executor,REPO.toString(),ROOT.resolve("jobs").toString(),"node");assertThat(recovered.get("interrupted").path("status").asText()).isEqualTo("INTERRUPTED");
 }
}
