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
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT.resolve("test.db"));r.add("production.repository-root",()->REPO.toString());r.add("production.workspace-root",()->ROOT.resolve("jobs").toString());r.add("production.node",()->Files.exists(Path.of("D:/nodejs/node.exe"))?"D:/nodejs/node.exe":"node");}
 @Autowired ProductionJobService jobs;@Autowired ProductionArtifactService artifacts;@Autowired TaskRepository tasks;@Autowired ObjectMapper mapper;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
 @Autowired ControlledExecutor executor;
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
