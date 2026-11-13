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
  gateway.script.add(r->{assertThat(r.tools()).extracting(t->t.name()).containsExactlyInAnyOrder("production_context","production_start","production_result");return call("context","production_context","{}");});
  gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.toolCallId()).isEqualTo("context");assertThat(m.content()).contains("Bound Builder game");});return call("start","production_start","{}");});
  final String[] id={null};gateway.script.add(r->{var tool=r.messages().stream().filter(m->"start".equals(m.toolCallId())).findFirst().orElseThrow();try{id[0]=mapper.readTree(tool.content()).path("id").asText();}catch(Exception e){throw new RuntimeException(e);}return call("result","production_result","{\"productionId\":\""+id[0]+"\"}");});
  gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.toolCallId()).isEqualTo("result");assertThat(m.content()).contains("BUILT","qualityAccepted", "false");});return stop();});
  var run=builder();loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThat(jobs.get(id[0]).path("ownerRunId").asLong()).isEqualTo(run.id());assertThat(gateway.script).isEmpty();
 }
 @Test void cannotReadManualProductionEvenForSameSpec(){var run=builder();conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());var manual=jobs.start(new ProductionJobService.Request(sourceTask,spec.path("artifactId").asText(),1,"manual"));var result=tools.productionResult().execute(mapper.createObjectNode().put("productionId",manual.path("id").asText()),ToolContext.of(run.id(),1L));assertThat(result.status()).isEqualTo("FAILED");assertThat(result.summaryText()).contains("another Agent Run");jobs.cancel(manual.path("id").asText());}
 @Test void repeatedStartForOneRunHasOneProduction(){var run=builder();runs.transitionIf(run.id(),"PENDING",RunStatus.RUNNING);conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());var ctx=ToolContext.of(run.id(),1L);var a=tools.productionStart().execute(mapper.createObjectNode(),ctx);var b=tools.productionStart().execute(mapper.createObjectNode(),ctx);try{assertThat(mapper.readTree(a.payloadJson()).path("id")).isEqualTo(mapper.readTree(b.payloadJson()).path("id"));}catch(Exception e){throw new RuntimeException(e);}}
 @Test void cancelledBuilderCannotCreateAProduction(){var run=builder();conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());runs.finishIfActive(run.id(),RunStatus.CANCELLED,null,"cancelled");var before=jobs.list().size();assertThat(tools.productionStart().execute(mapper.createObjectNode(),ToolContext.of(run.id(),1L)).status()).isEqualTo("FAILED");assertThat(jobs.list()).hasSize(before);}
 @Test void unboundBuilderFailsBeforeModelInvocation(){var task=tasks.insert(new Task(null,"bad","bad","{\"agent_type\":\"BUILDER\"}",20,true,"ACTIVE",null));var run=runs.insert(new Run(null,task.id(),"PENDING",null,"checking",0,20,100000,0,null,null,null,null,null));loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("FAILED");}
 @Test void otherAgentRolesHaveNoProductionTools(){for(var type:AgentType.values())if(type!=AgentType.BUILDER)assertThat(resolver.toolWhitelist(type)).doesNotContain("production_start","production_context","production_result");}
}
