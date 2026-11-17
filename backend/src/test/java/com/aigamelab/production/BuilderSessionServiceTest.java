package com.aigamelab.production;

import com.aigamelab.domain.*;
import com.aigamelab.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="llm.gateway.api-key=")
class BuilderSessionServiceTest {
 static final Path DB;static{try{DB=Files.createTempFile("agl-builder-session-",".db");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);}
 @Autowired BuilderSessionService sessions;@Autowired TaskRepository tasks;@Autowired RunRepository runs;@Autowired StepRepository steps;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;
 @BeforeEach void prices(){ReflectionTestUtils.setField(sessions,"promptPrice","2");ReflectionTestUtils.setField(sessions,"completionPrice","4");ReflectionTestUtils.setField(sessions,"maxCost","");}
 Task task(){return tasks.insert(new Task(null,"Builder session","continue","{\"agent_type\":\"BUILDER\",\"productionSpecId\":\"spec\",\"productionSpecRevision\":1,\"productionSourceTaskId\":1}",10,true,"ACTIVE",null));}
 Run insert(Task t){return runs.insert(new Run(null,t.id(),"PENDING",null,"checking",0,10,100,0,null,null,null,null,null));}
 Run active(Task t){var r=insert(t);runs.transitionIf(r.id(),"PENDING",RunStatus.RUNNING);r=runs.findById(r.id()).orElseThrow();sessions.claim(t,r);return r;}
 void usage(Run r,int seq,int prompt,int completion){steps.insert(new Step(null,r.id(),seq,"stop","done",prompt,completion,1L,null));runs.addTokensUsed(r.id(),prompt+completion);}
 @Test void handoffKeepsCapsDeadlineAndExactEstimatedUsageAfterNewServiceInstance(){
  var task=task();var a=active(task);usage(a,1,10,5);var before=sessions.snapshot(task.id());runs.finish(a.id(),RunStatus.FAILED,null,"retry");var b=active(task);usage(b,1,20,5);
  var restored=new BuilderSessionService(db,mapper,runs).snapshot(task.id());assertThat(restored.path("tokensUsed").asLong()).isEqualTo(40);assertThat(restored.path("stepsUsed").asLong()).isEqualTo(2);assertThat(restored.path("estimatedCostUsd").asText()).isEqualTo("0.000100");
  assertThat(restored.path("deadlineEpochMs")).isEqualTo(before.path("deadlineEpochMs"));assertThat(restored.path("tokenLimit").asInt()).isEqualTo(100);assertThat(restored.path("activeRunId").asLong()).isEqualTo(b.id());
 }
 @Test void admissionRejectsConcurrentRunAndRollsBackExhaustedRetry(){
  var task=task();var a=sessions.admit(task,()->insert(task));assertThatThrownBy(()->sessions.admit(task,()->insert(task))).hasMessageContaining("active Run");runs.addTokensUsed(a.id(),100);runs.finish(a.id(),RunStatus.FAILED,null,"exhausted");
  assertThatThrownBy(()->sessions.admit(task,()->insert(task))).hasMessageContaining("token budget");assertThat(runs.findByTask(task.id())).hasSize(1);
 }
 @Test void responseCostLimitStopsAndConfigurationCannotResetFrozenRates(){
  ReflectionTestUtils.setField(sessions,"maxCost","0.00001");var task=task();var a=active(task);usage(a,1,10,0);assertThat(sessions.check(a,false).reason()).contains("cost budget");
  ReflectionTestUtils.setField(sessions,"promptPrice","0");ReflectionTestUtils.setField(sessions,"completionPrice","0");assertThat(sessions.snapshot(task.id()).path("promptUsdPerMillion").asText()).isEqualTo("2");
 }
 @Test void missingPricesAreUnknownRatherThanFree(){
  ReflectionTestUtils.setField(sessions,"promptPrice","");ReflectionTestUtils.setField(sessions,"completionPrice","");var task=task();var a=active(task);usage(a,1,2,3);var state=sessions.snapshot(task.id());assertThat(state.path("pricingStatus").asText()).isEqualTo("UNCONFIGURED");assertThat(state.path("estimatedCostUsd").isNull()).isTrue();
 }
 @Test void invalidPricingAdmissionLeavesNoPartialRunOrSession(){
  ReflectionTestUtils.setField(sessions,"completionPrice","");ReflectionTestUtils.setField(sessions,"maxCost","1");var task=task();
  assertThatThrownBy(()->sessions.admit(task,()->insert(task))).hasMessageContaining("requires both");assertThat(runs.findByTask(task.id())).isEmpty();assertThat(sessions.snapshot(task.id())).isNull();
 }
 @Test void stepAccountingSurvivesCrashBeforeRunCounterUpdateAndHonorsLastAllowedTurn(){
  var task=task();var a=active(task);for(int i=1;i<=10;i++)steps.insert(new Step(null,a.id(),i,"stop","done",1,1,1L,null));
  assertThat(sessions.snapshot(task.id()).path("tokensUsed").asLong()).isEqualTo(20);assertThat(sessions.check(a,true).reason()).contains("step budget");assertThat(sessions.check(a,false).hit()).isFalse();
 }
 @Test void deadlineAndUnknownUsageAreRetainedAcrossRetry(){
  var task=task();var a=active(task);steps.insert(new Step(null,a.id(),1,"LLM_CALL",null,0,0,null,null));assertThat(sessions.snapshot(task.id()).path("unsettledUsageTurns").asInt()).isEqualTo(1);
  db.update("UPDATE builder_session SET record_json=json_set(record_json,'$.deadlineEpochMs',0) WHERE task_id=?",task.id());assertThat(sessions.check(a,false).target()).isEqualTo(RunStatus.TIMEOUT);
 }
 @Test void rejectsChangedBindingAndAmbiguousLegacyProductions(){
  var task=task();var a=active(task);var changed=new Task(task.id(),task.title(),task.goal(),task.constraintsJson().replace("\"spec\"","\"other\""),10,true,"ACTIVE",null);assertThatThrownBy(()->sessions.claim(changed,a)).hasMessageContaining("specification changed");
  var legacy=task();var r=insert(legacy);for(int i=0;i<2;i++)db.update("INSERT INTO production_job VALUES (?,?,?,?)","legacy-"+r.id()+"-"+i,legacy.id(),"key-"+i,"{\"ownerRunId\":"+r.id()+"}");assertThatThrownBy(()->sessions.claim(legacy,r)).hasMessageContaining("Multiple legacy");
 }
}
