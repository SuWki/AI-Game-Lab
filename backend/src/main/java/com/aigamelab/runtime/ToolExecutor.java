package com.aigamelab.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.aigamelab.domain.Run;
import com.aigamelab.domain.RunStatus;
import com.aigamelab.domain.Step;
import com.aigamelab.event.AgentEventPublisher;
import com.aigamelab.repository.RunRepository;
import com.aigamelab.tool.JsonSchemaValidator;
import com.aigamelab.tool.ToolContext;
import com.aigamelab.tool.ToolException;
import com.aigamelab.tool.ToolRegistry;
import com.aigamelab.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P1-2 / P3-5：工具执行编排。
 * 蓝图 §9.3 executeTool 逻辑 + §11 调用链 + §16 事件：
 * 落 STARTED 行 → 发 TOOL_CALLED → 参数 JSON Schema 校验（P3-5）→
 * dispatch 执行 → requiresApproval?挂起:PENDING:落 COMPLETED/FAILED → 发 TOOL_RETURNED；
 * ToolException 捕获写 error 不中断 Run（critical 除外，由 AgentLoop 转 Run FAILED）。
 *
 * 历史：P1-2 仅最小实现（落库+审批挂起）；P3-5 补 Schema 校验与 TOOL_* 事件接线
 * （P1-6 事件链路已就绪但运行时未发这两类事件，本次闭环）；
 * TD-P3-001：修正 approval 工具执行时序——先 execute() 再挂起，确保 tool
 * 内部逻辑（如落 PENDING 提案 + 发事件）在 Run 进入 WAITING_APPROVAL 前完成。
 */
@Component
public class ToolExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 回传 LLM 的摘要/结果截断上限，防 token 爆炸（蓝图 §11） */
    private static final int MAX_RESULT_CHARS = 4000;

    private final ToolRegistry registry;
    private final RunRepository runRepo;
    private final JdbcTemplate jdbc;
    private final AgentEventPublisher publisher;
    @org.springframework.beans.factory.annotation.Autowired private RuntimeConversation conversation;

    public ToolExecutor(ToolRegistry registry, RunRepository runRepo, JdbcTemplate jdbc,
            AgentEventPublisher publisher) {
        this.registry = registry;
        this.runRepo = runRepo;
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    /**
     * 执行一个 LLM 工具调用：落 STARTED 行→dispatch→写结果。
     *
     * @return true = Run 已挂起 WAITING_APPROVAL（本步终止后续 tool call）
     */
    public boolean execute(Run run, Step step, com.aigamelab.llm.dto.ToolCall dto) {
        long tcId = insertToolCall(run, step, dto);
        return executePrepared(run,step,dto,tcId);
    }

    public boolean executePrepared(Run run,Step step,com.aigamelab.llm.dto.ToolCall dto,long tcId) {
        if(jdbc.update("UPDATE tool_call SET status='STARTED',started_at=datetime('now','localtime') WHERE id=? AND status='QUEUED' AND EXISTS(SELECT 1 FROM run WHERE id=? AND status='RUNNING')",tcId,run.id())!=1)return false;
        if(!conversation.allowed(run,dto.name())) {
            failToolCall(tcId,"Tool not authorized for this Run: "+dto.name());
            publisher.publish("TOOL_RETURNED",run.id(),MAPPER.createObjectNode().put("tool",dto.name()).put("status","FAILED").put("error","Tool not authorized for this Run").toString());
            return false;
        }
        publisher.publish("TOOL_CALLED", run.id(),
                "{\"tool\":\"" + dto.name() + "\",\"args\":" + safeArgs(dto.argumentsJson()) + "}");

        var tool = registry.get(dto.name());
        if (tool == null) {
            String err = "未注册的工具: " + dto.name();
            failToolCall(tcId, err);
            publisher.publish("TOOL_RETURNED", run.id(),
                    "{\"tool\":\"" + dto.name() + "\",\"status\":\"FAILED\",\"error\":\"未注册\"}");
            return false;
        }
        if(!conversation.contractMatches(run,tool)){failToolCall(tcId,"Tool contract changed since this Run was created; create a new Run");return false;}
        try {
            JsonNode args = parseArgs(dto.argumentsJson());
            // P3-5：JSON Schema 校验（蓝图 §11）——非法参数直接 FAILED 不进 execute
            var schemaErrors = JsonSchemaValidator.validate(tool.jsonSchema(), args);
            if (!schemaErrors.isEmpty()) {
                String err = "参数非法: " + String.join("; ", schemaErrors);
                failToolCall(tcId, err);
                publisher.publish("TOOL_RETURNED", run.id(),
                        "{\"tool\":\"" + dto.name() + "\",\"status\":\"FAILED\",\"error\":\""
                                + truncate(err) + "\"}");
                return false; // observation 回传 LLM，Run 继续
            }
            ToolResult result = registry.dispatch(dto.name(), args,
                    ToolContext.of(run.id(), step.id()));
            // TD-P3-001：先执行 execute，再按 requiresApproval 挂起（蓝图 §9.7 修正）
            boolean suspended = false;
            if (tool.requiresApproval() && !"FAILED".equals(result.status())) {
                jdbc.update("UPDATE tool_call SET approval_status='PENDING',result_json=? WHERE id=?",result.payloadJson(),tcId);
                if(!runRepo.transitionIf(run.id(),"RUNNING",RunStatus.WAITING_APPROVAL))return false;
                if(reconcileApproval(run.id(),tcId,result.payloadJson()))return false;
                publisher.publish("TOOL_RETURNED", run.id(),
                        "{\"tool\":\"" + dto.name() + "\",\"status\":\"SUSPENDED\"}");
                suspended = true;
            } else {
                completeToolCall(tcId, result);
                publisher.publish("TOOL_RETURNED", run.id(),
                        "{\"tool\":\"" + dto.name() + "\",\"status\":\"" + result.status()
                                + "\",\"truncated\":" + isTruncated(result.summaryText()) + "}");
            }
            return suspended;
        } catch (ToolException e) {
            failToolCall(tcId, e.getMessage());
            publisher.publish("TOOL_RETURNED", run.id(),
                    "{\"tool\":\"" + dto.name() + "\",\"status\":\"FAILED\",\"error\":\""
                            + truncate(e.getMessage()) + "\"}");
            if (e.isCritical()) {
                throw e; // 关键失败 → AgentLoop 将 Run 置 FAILED
            }
            return false; // 非 critical：observation 回传 LLM，Run 继续
        } catch (RuntimeException e) {
            failToolCall(tcId, String.valueOf(e.getMessage()));
            publisher.publish("TOOL_RETURNED", run.id(),
                    "{\"tool\":\"" + dto.name() + "\",\"status\":\"FAILED\",\"error\":\""
                            + truncate(e.getMessage()) + "\"}");
            return false;
        }
    }

    private long insertToolCall(Run run, Step step, com.aigamelab.llm.dto.ToolCall dto) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO tool_call(step_id, run_id, tool_name, arguments_json, status, approval_status)
                    VALUES(?,?,?,?, 'QUEUED', 'NOT_REQUIRED')
                    """, new String[] {"id"});
            ps.setLong(1, step.id());
            ps.setLong(2, run.id());
            ps.setString(3, dto.name());
            ps.setString(4, dto.argumentsJson());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void completeToolCall(long id, ToolResult result) {
        jdbc.update("""
                UPDATE tool_call SET status=?, result_json=?, error=?, ended_at=datetime('now','localtime')
                WHERE id=?
                """, result.status(), result.payloadJson(),"FAILED".equals(result.status())?result.summaryText():null,id);
    }

    private void failToolCall(long id, String error) {
        jdbc.update("""
                UPDATE tool_call SET status='FAILED', error=?, ended_at=datetime('now','localtime')
                WHERE id=?
                """, error, id);
    }

    private static JsonNode parseArgs(String json) {
        try {
            return MAPPER.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new ToolException("工具参数非法 JSON: " + e.getMessage());
        }
    }

    /** TOOL_CALLED 负载内嵌 args，需保证是合法 JSON（解析失败回退空对象占位） */
    private static String safeArgs(String argumentsJson) {
        try {
            return MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 回传 LLM 的内容截断（蓝图 §11 防 token 爆炸），同时用于事件 error 字段 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String bounded=text.length() <= MAX_RESULT_CHARS ? text : text.substring(0, MAX_RESULT_CHARS);
        try {String json=MAPPER.writeValueAsString(bounded);return json.substring(1,json.length()-1);}catch(Exception e){return "error";}
    }

    /** Approval can commit between proposal creation and WAITING_APPROVAL; reconcile after suspending. */
    private boolean reconcileApproval(long runId,long callId,String payload) {
        String state=jdbc.queryForObject("SELECT status FROM tool_call WHERE id=?",String.class,callId);
        if("COMPLETED".equals(state)) {runRepo.transitionIf(runId,"WAITING_APPROVAL",RunStatus.RUNNING);return true;}
        try {
            JsonNode value=MAPPER.readTree(payload==null?"{}":payload);
            com.fasterxml.jackson.databind.node.ObjectNode decision=MAPPER.createObjectNode();String approval=null;
            if(value.has("proposalId")) {
                var rows=jdbc.query("SELECT status,target_version FROM change_proposal WHERE id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},value.path("proposalId").asLong());
                if(!rows.isEmpty()&&java.util.Set.of("APPROVED","REJECTED").contains(rows.get(0)[0])) {
                    approval=rows.get(0)[0];decision.put("proposalId",value.path("proposalId").asLong()).put("approval",approval).put("targetVersion",rows.get(0)[1]);
                }
            } else if(value.has("localProposalId")) {
                var rows=jdbc.queryForList("SELECT status FROM local_change_proposal WHERE id=?",String.class,value.path("localProposalId").asText());
                if(!rows.isEmpty()&&!java.util.Set.of("PENDING","APPLYING","VALIDATING").contains(rows.get(0))) {
                    approval="REJECTED".equals(rows.get(0))?"REJECTED":"APPROVED";
                    decision.put("localProposalId",value.path("localProposalId").asText()).put("approval",approval).put("validationStatus",rows.get(0));
                }
            }
            if(approval!=null) {
                jdbc.update("UPDATE tool_call SET status='COMPLETED',approval_status=?,result_json=?,ended_at=datetime('now','localtime') WHERE id=? AND approval_status='PENDING'",approval,decision.toString(),callId);
                runRepo.transitionIf(runId,"WAITING_APPROVAL",RunStatus.RUNNING);return true;
            }
        }catch(java.io.IOException e){throw new ToolException("Approval tool did not return valid JSON");}
        return false;
    }
    public void reconcileSuspendedRun(long runId) {
        var pending=jdbc.query("SELECT id,result_json FROM tool_call WHERE run_id=? AND approval_status='PENDING'",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},runId);
        for(var row:pending)reconcileApproval(runId,(Long)row[0],(String)row[1]);
        Integer remaining=jdbc.queryForObject("SELECT COUNT(*) FROM tool_call WHERE run_id=? AND approval_status='PENDING'",Integer.class,runId);
        Integer decided=jdbc.queryForObject("SELECT COUNT(*) FROM tool_call tc WHERE tc.run_id=? AND tc.status='COMPLETED' AND tc.approval_status IN ('APPROVED','REJECTED') AND EXISTS(SELECT 1 FROM runtime_turn t WHERE t.step_id=tc.step_id)",Integer.class,runId);
        if(remaining!=null&&remaining==0&&decided!=null&&decided>0)runRepo.transitionIf(runId,"WAITING_APPROVAL",RunStatus.RUNNING);
    }

    /** 是否被截断（事件负载 truncated 标记用） */
    private static boolean isTruncated(String text) {
        return text != null && text.length() > MAX_RESULT_CHARS;
    }
}
