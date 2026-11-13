package com.aigamelab.localtest;

import com.aigamelab.common.BusinessException;
import com.aigamelab.repository.FindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import com.aigamelab.llm.LlmGateway;
import com.aigamelab.llm.dto.*;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.repository.*;
import com.aigamelab.runtime.*;

/** Tests the platform against a tiny independent external program, not the Phaser demo. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="llm.gateway.api-key=")
class LocalWorkflowIntegrationTest {
    static class Gateway implements LlmGateway {
        final Queue<java.util.function.Function<LlmRequest,LlmResponse>> script=new java.util.concurrent.ConcurrentLinkedQueue<>();
        public LlmResponse complete(LlmRequest request){var next=script.poll();if(next==null)throw new IllegalStateException("Unexpected model request");return next.apply(request);}
    }
    @org.springframework.boot.test.context.TestConfiguration static class Config {@org.springframework.context.annotation.Bean Gateway gateway(){return new Gateway();}}
    static final Path ROOT;static{try{ROOT=Files.createTempDirectory("agl-local-workflow-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT.resolve("platform.db")+"?foreign_keys=ON");
        r.add("local-tests.catalog",()->ROOT.resolve("catalog.json").toString());
        r.add("local-tests.workspace-root",()->ROOT.resolve("workspaces").toString());
    }
    @Autowired LocalTestService tests;@Autowired LocalEvidenceService evidence;@Autowired LocalChangeService changes;
    @Autowired ObjectMapper mapper;@Autowired FindingRepository findings;
    @Autowired Gateway gateway;@Autowired AgentLoop loop;@Autowired RunRepository agentRuns;
    @Autowired RunControlService control;@Autowired org.springframework.jdbc.core.JdbcTemplate db;
    Path game;String targetId;
    @BeforeEach void setup()throws Exception {
        gateway.script.clear();
        targetId="fixture-"+UUID.randomUUID();game=Files.createDirectory(ROOT.resolve(targetId));Files.createDirectory(game.resolve("source"));Files.writeString(game.resolve("source/config.txt"),"bad");
        var target=new LocalTestService.Target(targetId,"Independent game",game.toString(),List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Fixture.class.getName()),10,"result.json",List.of("source"),List.of("artifact.txt"));
        mapper.writeValue(ROOT.resolve("catalog.json").toFile(),List.of(target));
    }
    LocalTestService.TestRun await(String id)throws Exception {
        long end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end){var run=tests.get(id);if(!Set.of("QUEUED","RUNNING").contains(run.status()))return run;Thread.sleep(20);}throw new AssertionError("Test did not finish");
    }
    LocalTestService.TestRun baseline()throws Exception {return await(tests.start(targetId).id());}
    LocalChangeService.Proposal proposal(LocalTestService.TestRun base,String text){return changes.create(base.id(),"Fix config",List.of(new LocalChangeService.Edit("source/config.txt",evidence.binding(base.id()).path("source").path("source/config.txt").asText(),text)));}
    @Test void completeTestFindingProposalApprovalAndIsolatedRegression()throws Exception {
        var before=baseline();assertThat(before.status()).isEqualTo("FAILED");assertThat(evidence.assessment(before).path("decision").asText()).isEqualTo("FAIL");
        var imported=evidence.importChecks(before);assertThat(imported.path("findingIds").size()).isEqualTo(1);
        assertThat(evidence.importChecks(before).path("findingIds").toString()).isEqualTo(imported.path("findingIds").toString());
        var finding=findings.findById(imported.path("findingIds").get(0).asLong()).orElseThrow();assertThat(finding.evidenceJson()).contains(before.id());assertThat(finding.configVersion()).startsWith("local-");
        var p=proposal(before,"good");assertThat(Files.readString(game.resolve("source/config.txt"))).isEqualTo("bad");
        p=changes.approve(p.id());assertThat(p.status()).isIn("VALIDATING","VALIDATED");
        var after=await(p.validationRunId());assertThat(after.status()).isEqualTo("PASSED");assertThat(changes.get(p.id()).status()).isEqualTo("VALIDATED");
        assertThat(Files.readString(game.resolve("source/config.txt"))).isEqualTo("bad");
        assertThat(Files.readString(Path.of(p.workspace()).resolve("source/config.txt"))).isEqualTo("good");
        assertThat(evidence.binding(before.id()).path("version")).isNotEqualTo(evidence.binding(after.id()).path("version"));
        assertThat(evidence.binding(after.id()).path("artifacts").has("artifact.txt")).isTrue();
        String id=p.id();assertThatThrownBy(()->changes.approve(id)).isInstanceOf(BusinessException.class);
    }
    @Test void missingBaselineCheckIsNotAFix()throws Exception {
        var before=baseline();var p=changes.approve(proposal(before,"omit").id());await(p.validationRunId());
        assertThat(changes.get(p.id()).status()).isEqualTo("INCONCLUSIVE");assertThat(changes.get(p.id()).error()).contains("omitted");
    }
    @Test void staleSourceCannotBeApplied()throws Exception {
        var before=baseline();var p=proposal(before,"good");Files.writeString(game.resolve("source/config.txt"),"external edit");
        assertThat(changes.approve(p.id()).status()).isEqualTo("FAILED");assertThat(Files.readString(game.resolve("source/config.txt"))).isEqualTo("external edit");
    }
    @Test void rejectionDoesNotCreateWorkspace()throws Exception {
        var p=proposal(baseline(),"good");p=changes.reject(p.id());assertThat(p.status()).isEqualTo("REJECTED");assertThat(p.workspace()).isNull();
    }
    @Test void sourcePathEscapeRejected()throws Exception {
        var before=baseline();assertThatThrownBy(()->changes.create(before.id(),"escape",List.of(new LocalChangeService.Edit("../outside.txt",null,"bad")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void incompleteEvidenceCannotImportFindings()throws Exception {
        Files.writeString(game.resolve("source/config.txt"),"incomplete");var run=baseline();
        assertThat(evidence.assessment(run).path("decision").asText()).isEqualTo("INCONCLUSIVE");assertThatThrownBy(()->evidence.importChecks(run)).isInstanceOf(BusinessException.class);
    }
    @Test void sourceMutationDuringTestInvalidatesEvidence()throws Exception {
        Files.writeString(game.resolve("source/config.txt"),"mutate");var run=baseline();
        assertThat(run.status()).isEqualTo("FAILED");assertThat(run.error()).contains("Source tree changed");
        assertThat(evidence.assessment(run).path("decision").asText()).isEqualTo("INCONCLUSIVE");
    }
    @Test void addedSourceFileInvalidatesOriginalManifest()throws Exception {
        var run=baseline();Files.writeString(game.resolve("source/new.txt"),"added");
        assertThatThrownBy(()->evidence.verify(run.id())).isInstanceOf(BusinessException.class);
    }
    static LlmResponse stop(){return new LlmResponse("test","stop","validated result",null,new TokenUsage(1,1,2,null,null),0L,"test");}
    static LlmResponse call(String id,String name,String arguments){return new LlmResponse("test","tool_calls",null,List.of(new ToolCall(id,name,arguments)),new TokenUsage(1,1,2,null,null),0L,"test");}
    Run newAgent(LocalTestService.TestRun base,String type) {
        var task=evidence.createAgentTask(base,type,"Review the tested game");
        return agentRuns.insert(new Run(null,task.id(),"PENDING",null,"test",0,20,100000,0,null,null,null,null,null));
    }
    Run awaitAgent(long id)throws Exception {
        long end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end){var run=agentRuns.findById(id).orElseThrow();if(RunStateMachine.isTerminal(RunStatus.valueOf(run.status())))return run;Thread.sleep(20);}throw new AssertionError("Agent did not finish");
    }
    @Test void designerAgentContinuesOnlyAfterHumanApprovalAndRealValidation()throws Exception {
        var base=baseline();var agent=newAgent(base,"DESIGNER");
        String hash=evidence.binding(base.id()).path("source").path("source/config.txt").asText();
        String args=mapper.createObjectNode().put("localTestRunId",base.id()).put("title","AI suggested fix").set("edits",mapper.valueToTree(List.of(new LocalChangeService.Edit("source/config.txt",hash,"good")))).toString();
        gateway.script.add(request->{assertThat(request.tools()).extracting(LlmToolSpec::name).contains("local_change_propose","local_source_read").doesNotContain("modify_game_config","game_start");return call("ai-proposal","local_change_propose",args);});
        gateway.script.add(request->{assertThat(request.messages()).anySatisfy(m->{assertThat(m.role()).isEqualTo("tool");assertThat(m.toolCallId()).isEqualTo("ai-proposal");assertThat(m.content()).contains("VALIDATED");});return stop();});
        loop.run(agent.id());assertThat(agentRuns.findById(agent.id()).orElseThrow().status()).isEqualTo("WAITING_APPROVAL");
        assertThat(Files.readString(game.resolve("source/config.txt"))).isEqualTo("bad");
        var pending=changes.list().stream().filter(p->p.baseRunId().equals(base.id())).findFirst().orElseThrow();
        var approved=changes.approve(pending.id());await(approved.validationRunId());
        assertThat(awaitAgent(agent.id()).status()).isEqualTo("COMPLETED");
        assertThat(Files.readString(game.resolve("source/config.txt"))).isEqualTo("bad");
    }
    @Test void testerAgentUsesRegisteredCommandAndReadsItsOwnEvidence()throws Exception {
        var base=baseline();var agent=newAgent(base,"TESTER");
        gateway.script.add(request->{assertThat(request.tools()).extracting(LlmToolSpec::name).contains("local_test_start").doesNotContain("local_change_propose");return call("start-local","local_test_start","{}");});
        gateway.script.add(request->{var msg=request.messages().stream().filter(m->"tool".equals(m.role())).findFirst().orElseThrow();try{return call("read-local","local_test_result",mapper.createObjectNode().put("localTestRunId",mapper.readTree(msg.content()).path("localTestRunId").asText()).toString());}catch(Exception e){throw new IllegalStateException(e);}});
        gateway.script.add(request->{assertThat(request.messages()).anySatisfy(m->{assertThat(m.role()).isEqualTo("tool");assertThat(m.content()).contains("config-health","FAIL");});return stop();});
        loop.run(agent.id());assertThat(awaitAgent(agent.id()).status()).isEqualTo("COMPLETED");
    }
    @Test void agentCannotReadAnotherManualExecutionEvenForSameTarget()throws Exception {
        var base=baseline();var other=baseline();var agent=newAgent(base,"TESTER");
        gateway.script.add(r->call("unauthorized-read","local_test_result",mapper.createObjectNode().put("localTestRunId",other.id()).toString()));
        gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.role()).isEqualTo("tool");assertThat(m.content()).contains("not authorized");});return stop();});
        loop.run(agent.id());assertThat(awaitAgent(agent.id()).status()).isEqualTo("COMPLETED");
    }
    @Test void missingDeclaredArtifactCannotProvePass()throws Exception {
        Files.writeString(game.resolve("source/config.txt"),"missing-artifact");var run=baseline();
        assertThat(run.status()).isEqualTo("PASSED");assertThat(evidence.assessment(run).path("decision").asText()).isEqualTo("INCONCLUSIVE");
        assertThatThrownBy(()->proposal(run,"good")).isInstanceOf(BusinessException.class);
    }
    @Test void cancellingAgentAlsoCancelsItsLocalExecution()throws Exception {
        var base=baseline();Files.writeString(game.resolve("source/config.txt"),"slow");
        base=baseline(); // establish a versioned slow baseline before starting the cancellable run
        var agent=newAgent(base,"TESTER");
        gateway.script.add(r->call("slow-test","local_test_start","{}"));
        Thread worker=Thread.startVirtualThread(()->loop.run(agent.id()));
        long end=System.currentTimeMillis()+5000;List<String> children;
        do {children=db.queryForList("SELECT run_id FROM local_test_context WHERE json_extract(binding_json,'$.ownerRunId')=?",String.class,agent.id());if(!children.isEmpty())break;Thread.sleep(20);}while(System.currentTimeMillis()<end);
        assertThat(children).hasSize(1);control.cancel(agent.id());worker.join(5000);
        assertThat(worker.isAlive()).isFalse();assertThat(tests.get(children.get(0)).status()).isEqualTo("CANCELLED");assertThat(agentRuns.findById(agent.id()).orElseThrow().status()).isEqualTo("CANCELLED");
    }
    public static class Fixture {
        public static void main(String[] args)throws Exception {
            String value=Files.readString(Path.of("source/config.txt"));boolean good=Set.of("good","omit","incomplete","mutate","missing-artifact").contains(value);
            if(value.equals("slow"))Thread.sleep(3000);
            var mapper=new ObjectMapper();var report=mapper.createObjectNode().put("runId",System.getenv("AGL_TEST_RUN_ID")).put("status",good?"PASS":"FAIL").put("coverageComplete",!value.equals("incomplete"));
            report.putArray("checks").addObject().put("id",value.equals("omit")?"different-check":"config-health").put("status",good?"PASS":"FAIL").put("title","Health config").put("expected","good").put("actual",value).put("severity","HIGH");
            if(!value.equals("missing-artifact"))Files.writeString(Path.of("artifact.txt"),"built:"+value);
            if(value.equals("mutate"))Files.writeString(Path.of("source/config.txt"),"changed during test");
            mapper.writeValue(Path.of(System.getenv("AGL_TEST_RESULT_PATH")).toFile(),report);
            if(!good)System.exit(1);
        }
    }
}
