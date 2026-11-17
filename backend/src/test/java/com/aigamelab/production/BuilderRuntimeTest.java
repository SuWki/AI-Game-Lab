package com.aigamelab.production;
import com.aigamelab.domain.*;
import com.aigamelab.llm.*;
import com.aigamelab.llm.dto.*;
import com.aigamelab.repository.*;
import com.aigamelab.runtime.*;
import com.aigamelab.tool.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="llm.gateway.api-key=")
class BuilderRuntimeTest {
 static final Path ROOT,REPO;static{try{ROOT=Files.createTempDirectory("agl-builder-");Path cwd=Path.of("").toAbsolutePath();REPO=Files.exists(cwd.resolve("scripts/production"))?cwd:cwd.getParent();}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT.resolve("test.db"));r.add("production.repository-root",()->REPO.toString());r.add("production.workspace-root",()->ROOT.resolve("jobs").toString());r.add("production.node",()->Files.exists(Path.of("D:/nodejs/node.exe"))?"D:/nodejs/node.exe":"node");}
 static class CheckingGateway implements LlmGateway{final Queue<java.util.function.Function<LlmRequest,LlmResponse>> script=new java.util.concurrent.ConcurrentLinkedQueue<>();public LlmResponse complete(LlmRequest r){var f=script.poll();if(f==null)throw new IllegalStateException("Unexpected model call");return f.apply(r);}}
 @org.springframework.boot.test.context.TestConfiguration static class Config{@org.springframework.context.annotation.Bean CheckingGateway gateway(){return new CheckingGateway();}}
 @Autowired ProductionArtifactService artifacts;@Autowired ProductionJobService jobs;@Autowired ProductionTools tools;@Autowired TaskRepository tasks;@Autowired RunRepository runs;@Autowired RuntimeConversation conversation;@Autowired AgentLoop loop;@Autowired CheckingGateway gateway;@Autowired ObjectMapper mapper;@Autowired AgentTypeResolver resolver;
 @Autowired com.aigamelab.localtest.LocalChangeService changes;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
 @Autowired BuilderSessionService sessions;@Autowired RunControlService control;
 JsonNode spec;long sourceTask;
 @BeforeEach void setup()throws Exception{
  gateway.script.clear();sourceTask=tasks.insert(new Task(null,"source","make game","{}",20,true,"ACTIVE",null)).id();
  var brief=artifacts.save(new ProductionArtifactService.Request(sourceTask,null,null,null,"ProductionBrief",mapper.readTree("{\"title\":\"Brief\",\"mode\":\"NEW\",\"genre\":\"survival\",\"targetPlatform\":\"web\",\"targetPlayer\":\"casual\",\"scope\":\"slice\"}"),List.of()));
  var content=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree("""
   {"title":"Bound Builder game","genre":"survival","targetPlatform":"web","coreLoop":"survive","endConditions":"survive round","restartBehavior":"reset","systems":{"survivalWeb":{"mechanic":"heat-burst","duration":6,"enemySpeed":70,"spawnInterval":1,"seed":42}},"controls":["move"],"states":["play","end"],"contentScope":[],"assetNeeds":[],"exclusions":[],"openQuestions":[],"requirements":[{"id":"R1","description":"restart","acceptanceIds":["A1"]}],"acceptanceCriteria":[{"id":"A1","requirementId":"R1","expected":"reset","method":"browser"}]}
   """);content.put("briefRef",brief.path("artifactId").asText());spec=artifacts.save(new ProductionArtifactService.Request(sourceTask,null,null,null,"GameSpec",content,List.of(new ProductionArtifactService.Ref(brief.path("artifactId").asText(),1,brief.path("contentHash").asText()))));
 }
 Run builder(){var task=jobs.createBuilderTask(sourceTask,spec.path("artifactId").asText(),1,"Generate template and report evidence");return runs.insert(new Run(null,task.id(),"PENDING",null,"checking",0,20,100000,0,null,null,null,null,null));}
 static LlmResponse call(String id,String name,String args){return new LlmResponse("test","tool_calls",null,List.of(new com.aigamelab.llm.dto.ToolCall(id,name,args)),new TokenUsage(1,1,2,null,null),0L,"test");}
 static LlmResponse stop(){return new LlmResponse("test","stop","Template built; full quality acceptance pending",null,new TokenUsage(1,1,2,null,null),0L,"test");}
 @Test void requestDrivenAgentBuildsThroughSharedRuntimeAndObservesEvidence(){
  gateway.script.add(r->{assertThat(r.tools()).extracting(t->t.name()).containsExactlyInAnyOrder("production_context","production_start","production_result","production_validate","production_source_read","production_change_propose","candidate_package");return call("context","production_context","{}");});
  gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.toolCallId()).isEqualTo("context");assertThat(m.content()).contains("Bound Builder game");});return call("start","production_start","{}");});
  final String[] id={null};gateway.script.add(r->{var tool=r.messages().stream().filter(m->"start".equals(m.toolCallId())).findFirst().orElseThrow();try{id[0]=mapper.readTree(tool.content()).path("id").asText();}catch(Exception e){throw new RuntimeException(e);}return call("result","production_result","{\"productionId\":\""+id[0]+"\"}");});
  gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.toolCallId()).isEqualTo("result");assertThat(m.content()).contains("BUILT","qualityAccepted", "false");});return stop();});
  var run=builder();loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThat(jobs.get(id[0]).path("ownerRunId").asLong()).isEqualTo(run.id());assertThat(gateway.script).isEmpty();
 }
 @Test void cannotReadManualProductionEvenForSameSpec(){var run=builder();conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());var manual=jobs.start(new ProductionJobService.Request(sourceTask,spec.path("artifactId").asText(),1,"manual"));var result=tools.productionResult().execute(mapper.createObjectNode().put("productionId",manual.path("id").asText()),ToolContext.of(run.id(),1L));assertThat(result.status()).isEqualTo("FAILED");assertThat(result.summaryText()).contains("another Agent Run");jobs.cancel(manual.path("id").asText());}
 @Test void repeatedStartForOneRunHasOneProduction(){var run=builder();runs.transitionIf(run.id(),"PENDING",RunStatus.RUNNING);conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());var ctx=ToolContext.of(run.id(),1L);var a=tools.productionStart().execute(mapper.createObjectNode(),ctx);var b=tools.productionStart().execute(mapper.createObjectNode(),ctx);try{assertThat(mapper.readTree(a.payloadJson()).path("id")).isEqualTo(mapper.readTree(b.payloadJson()).path("id"));}catch(Exception e){throw new RuntimeException(e);}}
 @Test void cancelledBuilderCannotCreateAProduction(){var run=builder();conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());runs.finishIfActive(run.id(),RunStatus.CANCELLED,null,"cancelled");var before=jobs.list().size();assertThat(tools.productionStart().execute(mapper.createObjectNode(),ToolContext.of(run.id(),1L)).status()).isEqualTo("FAILED");assertThat(jobs.list()).hasSize(before);}
 @Test void unboundBuilderFailsBeforeModelInvocation(){var task=tasks.insert(new Task(null,"bad","bad","{\"agent_type\":\"BUILDER\"}",20,true,"ACTIVE",null));var run=runs.insert(new Run(null,task.id(),"PENDING",null,"checking",0,20,100000,0,null,null,null,null,null));loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("FAILED");}
 @Test void otherAgentRolesHaveNoProductionTools(){for(var type:AgentType.values())if(type!=AgentType.BUILDER)assertThat(resolver.toolWhitelist(type)).doesNotContain("production_start","production_context","production_result","candidate_package");}
 @Test void builderPackagesOnlyItsVerifiedCandidateWithoutPublishing(){
  final String[] id={null};gateway.script.add(r->call("start","production_start","{}"));gateway.script.add(r->{id[0]=observation(r,"start").path("id").asText();return call("result","production_result","{\"productionId\":\""+id[0]+"\"}");});
  gateway.script.add(r->call("verify","production_validate","{\"productionId\":\""+id[0]+"\"}"));gateway.script.add(r->call("package","candidate_package",mapper.createObjectNode().put("productionId",id[0]).put("validationRunId",observation(r,"verify").path("validationRunId").asText()).toString()));
  gateway.script.add(r->{var packaged=observation(r,"package");assertThat(packaged.path("status").asText()).isEqualTo("READY");assertThat(packaged.path("archiveSha256").asText()).hasSize(64);assertThat(packaged.path("qualityAccepted").asBoolean()).isFalse();assertThat(packaged.path("published").asBoolean()).isFalse();return stop();});
  var run=builder();loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThat(db.queryForObject("SELECT COUNT(*) FROM production_candidate WHERE production_id=?",Integer.class,id[0])).isEqualTo(1);
 }
 @Test void retryReusesProductionAndValidatedCandidateWithCumulativeUsage()throws Exception{
  final String[] id={null};gateway.script.add(r->call("start","production_start","{}"));
  gateway.script.add(r->{id[0]=observation(r,"start").path("id").asText();return call("result","production_result","{\"productionId\":\""+id[0]+"\"}");});
  gateway.script.add(r->call("verify","production_validate","{\"productionId\":\""+id[0]+"\"}"));gateway.script.add(r->stop());
  var first=builder();loop.run(first.id());assertThat(runs.findById(first.id()).orElseThrow().status()).isEqualTo("COMPLETED");
  var j=jobs.get(id[0]);String baseline=j.path("validationRunId").asText();var evidence=org.springframework.test.util.ReflectionTestUtils.getField(tools,"evidence");
  var source=((com.aigamelab.localtest.LocalEvidenceService)evidence).source(baseline,"engine.mjs");
  var approved=changes.approve(changes.create(baseline,"Keep candidate across retry",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText().replace("*210*dt","*220*dt")))).id());
  long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end&&"VALIDATING".equals(changes.get(approved.id()).status()))Thread.sleep(20);assertThat(changes.get(approved.id()).status()).isEqualTo("VALIDATED");
  long tokens=sessions.snapshot(first.taskId()).path("tokensUsed").asLong();var deadline=sessions.snapshot(first.taskId()).path("deadlineEpochMs");
  gateway.script.add(r->call("context","production_context","{}"));gateway.script.add(r->{assertThat(observation(r,"context").path("budget").path("productionId").asText()).isEqualTo(id[0]);return call("restart","production_start","{}");});
  gateway.script.add(r->{var reused=observation(r,"restart");assertThat(reused.path("id").asText()).isEqualTo(id[0]);assertThat(reused.path("iteration").path("repairCount").asInt()).isEqualTo(1);assertThat(reused.path("validationRunId").asText()).isEqualTo(approved.validationRunId());return stop();});
  var next=control.retry(first.id());end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end&&!RunStateMachine.isTerminal(RunStatus.valueOf(runs.findById(next.id()).orElseThrow().status())))Thread.sleep(20);
  assertThat(runs.findById(next.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThat(jobs.get(id[0]).path("ownerRunId").asLong()).isEqualTo(next.id());assertThat(jobs.get(id[0]).path("ownerHistory").size()).isEqualTo(1);
  assertThat(sessions.snapshot(first.taskId()).path("tokensUsed").asLong()).isGreaterThan(tokens);assertThat(sessions.snapshot(first.taskId()).path("deadlineEpochMs")).isEqualTo(deadline);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM production_job WHERE task_id=?",Integer.class,sourceTask)).isEqualTo(1);
  assertThat(tools.productionSourceRead().execute(mapper.createObjectNode().put("productionId",id[0]).put("path","engine.mjs"),ToolContext.of(first.id(),1L)).status()).isEqualTo("FAILED");
 }
 @Test void overBudgetResponseCannotDispatchItsProductionToolOrResetViaRetry(){
  var first=builder();int before=jobs.list().size();gateway.script.add(r->new LlmResponse("overflow","tool_calls",null,List.of(new com.aigamelab.llm.dto.ToolCall("start","production_start","{}")),new TokenUsage(100000,1,100001,null,null),0L,"checking"));loop.run(first.id());
  assertThat(runs.findById(first.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(jobs.list()).hasSize(before);assertThatThrownBy(()->control.retry(first.id())).hasMessageContaining("token budget");assertThat(runs.findByTask(first.taskId())).hasSize(1);
 }
 @Test void finalAllowedStepCanCompleteButCannotBeReset(){
  var t=jobs.createBuilderTask(sourceTask,spec.path("artifactId").asText(),1,"one step");var run=runs.insert(new Run(null,t.id(),"PENDING",null,"checking",0,1,100000,0,null,null,null,null,null));gateway.script.add(r->stop());loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThatThrownBy(()->control.retry(run.id())).hasMessageContaining("step budget");
 }
 @Test void pendingProposalMustBeResolvedBeforeRetryHandoff()throws Exception{
  final String[] id={null};gateway.script.add(r->call("start","production_start","{}"));gateway.script.add(r->{id[0]=observation(r,"start").path("id").asText();return call("result","production_result","{\"productionId\":\""+id[0]+"\"}");});gateway.script.add(r->call("verify","production_validate","{\"productionId\":\""+id[0]+"\"}"));gateway.script.add(r->stop());
  var first=builder();loop.run(first.id());String base=jobs.get(id[0]).path("validationRunId").asText();var source=((com.aigamelab.localtest.LocalEvidenceService)org.springframework.test.util.ReflectionTestUtils.getField(tools,"evidence")).source(base,"engine.mjs");
  var pending=changes.create(base,"Unresolved previous proposal",List.of(new com.aigamelab.localtest.LocalChangeService.Edit("engine.mjs",source.path("sha256").asText(),source.path("text").asText()+"\n// pending")));
  var next=control.retry(first.id());long end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end&&!"FAILED".equals(runs.findById(next.id()).orElseThrow().status()))Thread.sleep(20);
  assertThat(runs.findById(next.id()).orElseThrow().errorMessage()).contains("Resolve previous");assertThat(changes.get(pending.id()).status()).isEqualTo("PENDING");assertThat(jobs.get(id[0]).path("ownerRunId").asLong()).isEqualTo(first.id());changes.reject(pending.id());
 }
 @Test void lateUsageAfterCancellationIsSettledBeforeRetryCanCallTheModel()throws Exception{
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var first=builder();
  gateway.script.add(r->{entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Gate timeout");}catch(InterruptedException e){throw new RuntimeException(e);}return new LlmResponse("late","stop","late",null,new TokenUsage(100000,1,100001,null,null),0L,"checking");});
  var worker=new Thread(()->loop.run(first.id()));worker.start();Run next;
  try{assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();control.cancel(first.id());next=control.retry(first.id());}finally{release.countDown();worker.join(15000);}
  long end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end&&!RunStateMachine.isTerminal(RunStatus.valueOf(runs.findById(next.id()).orElseThrow().status())))Thread.sleep(20);
  assertThat(runs.findById(first.id()).orElseThrow().status()).isEqualTo("CANCELLED");assertThat(runs.findById(next.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(db.queryForObject("SELECT COUNT(*) FROM step WHERE run_id=?",Integer.class,next.id())).isZero();assertThat(sessions.snapshot(first.taskId()).path("tokensUsed").asLong()).isEqualTo(100001);
 }
 JsonNode observation(LlmRequest r,String id){try{return mapper.readTree(r.messages().stream().filter(m->id.equals(m.toolCallId())).findFirst().orElseThrow().content());}catch(Exception e){throw new RuntimeException(e);}}
 @Test void sourceProposalWaitsForHumanThenResumesWithIndependentRegression()throws Exception{
  final String[] production={null};final StringBuilder source=new StringBuilder();final String[] sourceHash={null};
  gateway.script.add(r->call("start","production_start","{}"));
  gateway.script.add(r->{production[0]=observation(r,"start").path("id").asText();return call("result","production_result","{\"productionId\":\""+production[0]+"\"}");});
  gateway.script.add(r->{assertThat(observation(r,"result").path("status").asText()).isEqualTo("BUILT");return call("verify","production_validate","{\"productionId\":\""+production[0]+"\"}");});
  class Reader implements java.util.function.Function<LlmRequest,LlmResponse>{int page=0;public LlmResponse apply(LlmRequest r){var file=observation(r,"source-"+page);source.append(file.path("text").asText());sourceHash[0]=file.path("sha256").asText();if(source.length()<file.path("totalCharacters").asInt()){page++;gateway.script.add(this);return call("source-"+page,"production_source_read",mapper.createObjectNode().put("productionId",production[0]).put("path","engine.mjs").put("offset",source.length()).put("limit",2000).toString());}
    gateway.script.add(next->{assertThat(observation(next,"proposal").path("validationStatus").asText()).isEqualTo("VALIDATED");return call("next-source","production_source_read",mapper.createObjectNode().put("productionId",production[0]).put("path","engine.mjs").put("offset",0).put("limit",2000).toString());});
    gateway.script.add(next->{assertThat(observation(next,"next-source").path("sha256").asText()).isNotEqualTo(sourceHash[0]);return stop();});
    var args=mapper.createObjectNode().put("productionId",production[0]).put("title","Adjust movement speed within accepted rules");args.putArray("edits").addObject().put("path","engine.mjs").put("beforeSha256",sourceHash[0]).put("afterText",source.toString().replace("*210*dt","*220*dt"));return call("proposal","production_change_propose",args.toString());
  }}
  gateway.script.add(r->{assertThat(observation(r,"verify").path("validationDecision").asText()).isEqualTo("PASS");assertThat(observation(r,"verify").path("validationChecks").size()).isEqualTo(6);gateway.script.add(new Reader());return call("source-0","production_source_read",mapper.createObjectNode().put("productionId",production[0]).put("path","engine.mjs").put("offset",0).put("limit",2000).toString());});
  var run=builder();loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("WAITING_APPROVAL");
  String proposalId=mapper.readTree(db.queryForObject("SELECT result_json FROM tool_call WHERE run_id=? AND tool_name='production_change_propose'",String.class,run.id())).path("localProposalId").asText();
  Path original=Path.of(jobs.get(production[0]).path("result").path("workspace").asText()).resolve("engine.mjs");assertThat(Files.readString(original)).contains("*210*dt");
  changes.approve(proposalId);long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end&&!"COMPLETED".equals(runs.findById(run.id()).orElseThrow().status()))Thread.sleep(30);
  assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThat(changes.get(proposalId).status()).isEqualTo("VALIDATED");assertThat(Files.readString(original)).contains("*210*dt");assertThat(Files.readString(Path.of(changes.get(proposalId).workspace()).resolve("engine.mjs"))).contains("*220*dt");
 }
}
