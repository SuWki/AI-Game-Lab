package com.aigamelab.runtime;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.domain.Task;
import com.aigamelab.config.ApprovalService;
import com.aigamelab.config.ConfigVersionService;
import com.aigamelab.llm.LlmGateway;
import com.aigamelab.llm.dto.*;
import com.aigamelab.repository.*;
import com.aigamelab.tool.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="llm.gateway.api-key=")
class RuntimeIntegrityTest {
    static final Path DB;static{try{DB=Files.createTempFile("agl-runtime-integrity-",".db");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB+"?foreign_keys=ON");}
    static class CheckingGateway implements LlmGateway {
        final Queue<Function<LlmRequest,LlmResponse>> script=new ConcurrentLinkedQueue<>();final AtomicInteger count=new AtomicInteger();
        public LlmResponse complete(LlmRequest request){count.incrementAndGet();var next=script.poll();if(next==null)throw new IllegalStateException("Unexpected extra model request");return next.apply(request);}
    }
    @TestConfiguration static class Config {@Bean CheckingGateway gateway(){return new CheckingGateway();}}
    @Autowired CheckingGateway gateway;@Autowired AgentLoop loop;@Autowired RunControlService control;
    @Autowired RunRepository runs;@Autowired TaskRepository tasks;@Autowired StepRepository steps;
    @Autowired ApprovalService approvals;@Autowired ConfigVersionService configs;@Autowired JdbcTemplate db;
    @Autowired RuntimeConversation conversation;@Autowired ToolRegistry registry;
    @Autowired ChangeProposalRepository proposals;
    @BeforeEach void reset(){gateway.script.clear();gateway.count.set(0);}
    Run run(){var task=tasks.insert(new Task(null,"test","Inspect actual data","{}",10,true,"ACTIVE",null));return runs.insert(new Run(null,task.id(),"PENDING",null,"test-model",0,10,100000,0,null,null,null,null,null));}
    static LlmResponse stop(){return new LlmResponse("test","stop","done",null,new TokenUsage(1,1,2,null,null),0L,"test");}
    static LlmResponse calls(ToolCall... calls){return new LlmResponse("test","tool_calls",null,List.of(calls),new TokenUsage(1,1,2,null,null),0L,"test");}
    Run await(long id)throws Exception {long end=System.nanoTime()+10_000_000_000L;while(System.nanoTime()<end){Run r=runs.findById(id).orElseThrow();if(RunStateMachine.isTerminal(com.aigamelab.domain.RunStatus.valueOf(r.status())))return r;Thread.sleep(20);}throw new AssertionError("Run did not terminate");}
    @Test void secondModelRequestMustContainActualToolResultAndOriginalId(){
        gateway.script.add(r->calls(new ToolCall("provider-original-83","get_game_config","{}")));
        gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.role()).isEqualTo("tool");assertThat(m.toolCallId()).isEqualTo("provider-original-83");assertThat(m.content()).contains("weapons");});return stop();});
        Run run=run();loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");assertThat(gateway.count.get()).isEqualTo(2);
    }
    @Test void executionWhitelistRejectsRegisteredButUnauthorizedTool(){
        AtomicInteger effects=new AtomicInteger();String name="forbidden_"+UUID.randomUUID().toString().replace("-","");
        registry.register(new GameTool(){public String name(){return name;}public Map<String,Object> jsonSchema(){return Map.of("type","object");}public boolean requiresApproval(){return false;}public ToolResult execute(com.fasterxml.jackson.databind.JsonNode a,ToolContext c){effects.incrementAndGet();return ToolResult.success("{}","bad");}});
        gateway.script.add(r->calls(new ToolCall("deny-1",name,"{}")));
        gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.role()).isEqualTo("tool");assertThat(m.content()).contains("not authorized");});return stop();});
        Run run=run();loop.run(run.id());assertThat(effects.get()).isZero();assertThat(db.queryForObject("SELECT status FROM tool_call WHERE run_id=?",String.class,run.id())).isEqualTo("FAILED");
    }
    @Test void approvalResumesRemainingBatchAndReplaysDecision()throws Exception {approvalBatch(true);}
    @Test void rejectionResumesRemainingBatchWithoutCreatingVersion()throws Exception {approvalBatch(false);}
    void approvalBatch(boolean approve)throws Exception {
        String before=configs.getCurrent().version();Run run=run();
        gateway.script.add(r->calls(new ToolCall("original-write","modify_game_config","{\"baseVersion\":\""+before+"\",\"patch\":{\"player\":{\"maxHp\":101}},\"reason\":\"test\"}"),new ToolCall("original-read","get_game_config","{}")));
        gateway.script.add(r->{var tool=r.messages().stream().filter(m->"tool".equals(m.role())).toList();assertThat(tool).hasSize(2);assertThat(tool.get(0).toolCallId()).isEqualTo("original-write");assertThat(tool.get(0).content()).contains(approve?"APPROVED":"REJECTED");assertThat(tool.get(1).toolCallId()).isEqualTo("original-read");return stop();});
        loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("WAITING_APPROVAL");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call WHERE run_id=? AND status='QUEUED'",Integer.class,run.id())).isEqualTo(1);
        assertThat(gateway.count.get()).isEqualTo(1);
        long proposal=db.queryForObject("SELECT id FROM change_proposal WHERE run_id=?",Long.class,run.id());
        if(approve)approvals.approve(proposal,"ok");else approvals.reject(proposal,"no");
        assertThat(await(run.id()).status()).isEqualTo("COMPLETED");assertThat(gateway.count.get()).isEqualTo(2);
        if(!approve)assertThat(configs.getCurrent().version()).isEqualTo(before);
        assertThatThrownBy(()->approvals.approve(proposal,"duplicate")).isInstanceOf(com.aigamelab.common.BusinessException.class);
    }
    @Test void lateModelResponseCannotOverwriteCancellation(){
        Run run=run();gateway.script.add(r->{control.cancel(run.id());return stop();});
        loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_event WHERE run_id=? AND type='RUN_COMPLETED'",Integer.class,run.id())).isZero();
    }
    @Test void unknownInFlightToolIsNotReexecutedAfterRecovery(){
        Run run=run();var step=steps.insert(new Step(null,run.id(),1,"tool_calls",null,0,0,0L,null));
        conversation.persistBatch(run,step,calls(new ToolCall("crashed-id","get_game_config","{}")));
        db.update("UPDATE tool_call SET status='STARTED' WHERE run_id=?",run.id());
        loop.run(run.id());assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("FAILED");assertThat(gateway.count.get()).isZero();
    }
    @Test void concurrentLoopEntriesHaveOneModelCaller()throws Exception {
        Run run=run();gateway.script.add(r->{try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}return stop();});
        try(var pool=Executors.newFixedThreadPool(2)){var one=pool.submit(()->loop.run(run.id()));var two=pool.submit(()->loop.run(run.id()));one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);}
        assertThat(gateway.count.get()).isEqualTo(1);assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    }
    @Test void queuedCancellationDoesNotStartAModelCall(){
        Run run=run();control.cancel(run.id());loop.run(run.id());assertThat(gateway.count.get()).isZero();assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo("CANCELLED");
    }
    @Test void staleApprovalCannotOverwriteNewConfiguration(){
        var base=configs.getCurrent();var proposal=proposals.insert(new com.aigamelab.domain.ChangeProposal(null,null,base.version(),null,"old","reason","[]","[]",base.configJson(),"PENDING",null,null,null));
        var newer=configs.createVersion(base.configJson(),base.version(),null,"external newer version");
        assertThatThrownBy(()->approvals.approve(proposal.id(),"stale")).isInstanceOf(com.aigamelab.common.BusinessException.class);
        assertThat(configs.getCurrent().version()).isEqualTo(newer.version());assertThat(proposals.findById(proposal.id()).orElseThrow().status()).isEqualTo("PENDING");
    }
    @Test void durableApprovalIsReconciledOnRecoveryWithoutRepeatingTheWrite()throws Exception {
        Run run=run();var current=configs.getCurrent();
        var p=proposals.insert(new com.aigamelab.domain.ChangeProposal(null,run.id(),current.version(),null,"recovered approval","reason","[]","[]",current.configJson(),"APPROVED",null,null,null));
        var step=steps.insert(new Step(null,run.id(),1,"tool_calls",null,0,0,0L,null));
        conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());conversation.persistBatch(run,step,calls(new ToolCall("recovered-provider-id","modify_game_config","{}")));
        db.update("UPDATE tool_call SET status='STARTED',approval_status='PENDING',result_json=? WHERE run_id=?","{\"proposalId\":"+p.id()+"}",run.id());
        runs.transitionIf(run.id(),"PENDING",RunStatus.RUNNING);runs.transitionIf(run.id(),"RUNNING",RunStatus.WAITING_APPROVAL);
        gateway.script.add(request->{assertThat(request.messages()).anySatisfy(m->{assertThat(m.role()).isEqualTo("tool");assertThat(m.toolCallId()).isEqualTo("recovered-provider-id");assertThat(m.content()).contains("APPROVED");});return stop();});
        control.recoverOnStartup();assertThat(await(run.id()).status()).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM agent_event WHERE run_id=? AND type='TOOL_CALLED'",Integer.class,run.id())).isZero();
    }
    @Test void decidedToolObservationBeforeCrashStillResumesWaitingRun()throws Exception {
        Run run=run();var step=steps.insert(new Step(null,run.id(),1,"tool_calls",null,0,0,0L,null));
        conversation.freeze(run,tasks.findById(run.taskId()).orElseThrow());conversation.persistBatch(run,step,calls(new ToolCall("decided-before-crash","modify_game_config","{}")));
        db.update("UPDATE tool_call SET status='COMPLETED',approval_status='APPROVED',result_json='{\"approval\":\"APPROVED\"}' WHERE run_id=?",run.id());
        runs.transitionIf(run.id(),"PENDING",RunStatus.RUNNING);runs.transitionIf(run.id(),"RUNNING",RunStatus.WAITING_APPROVAL);
        gateway.script.add(r->{assertThat(r.messages()).anySatisfy(m->{assertThat(m.toolCallId()).isEqualTo("decided-before-crash");assertThat(m.content()).contains("APPROVED");});return stop();});
        control.recoverOnStartup();assertThat(await(run.id()).status()).isEqualTo("COMPLETED");assertThat(gateway.count.get()).isEqualTo(1);
    }
}
