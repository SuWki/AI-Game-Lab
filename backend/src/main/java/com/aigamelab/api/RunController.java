package com.aigamelab.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aigamelab.common.ApiResp;
import com.aigamelab.domain.Run;
import com.aigamelab.domain.Step;
import com.aigamelab.runtime.RunControlService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P1-4：Run 生命周期控制端点 + P7-3 Run Trace 只读查询。
 *
 * <p>新增的 GET 端点（P7-3 Sprint 内补齐，非 P6-4，不扩展 Run 管理服务）：
 * <ul>
 *   <li>GET /api/runs — Run 列表（按 id 倒序，供 Trace 页左栏）</li>
 *   <li>GET /api/runs/{id} — Run 详情：Run + Steps + 每 Step 的 ToolCalls
 *       （供 Trace 页右栏时间线 + Drawer 详情）</li>
 * </ul>
 *
 * <p>红线（P7-3 裁决）：复用既有 RunRepository / StepRepository / ToolCallRepository，
 * 不修改 cancel/resume/retry 契约、不改 schema、不改状态机、不新增 P7 之外 Run API。
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {
    @org.springframework.beans.factory.annotation.Autowired private com.aigamelab.runtime.RuntimeConversation conversation;

    private final RunControlService control;
    private final com.aigamelab.repository.RunRepository runRepo;
    private final com.aigamelab.repository.StepRepository stepRepo;
    private final com.aigamelab.repository.ToolCallRepository toolCallRepo;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RunController(RunControlService control,
            com.aigamelab.repository.RunRepository runRepo,
            com.aigamelab.repository.StepRepository stepRepo,
            com.aigamelab.repository.ToolCallRepository toolCallRepo,
            JdbcTemplate jdbc) {
        this.control = control;
        this.runRepo = runRepo;
        this.stepRepo = stepRepo;
        this.toolCallRepo = toolCallRepo;
        this.jdbc = jdbc;
    }

    @PostMapping("/{id}/cancel")
    public ApiResp<Void> cancel(@PathVariable long id,@org.springframework.web.bind.annotation.RequestHeader(value="X-AGL-Local-Test",required=false)String marker,jakarta.servlet.http.HttpServletRequest request) {
        guardLocal(id,marker,request);
        control.cancel(id);
        return ApiResp.ok();
    }

    @PostMapping("/{id}/resume")
    public ApiResp<Void> resume(@PathVariable long id,@org.springframework.web.bind.annotation.RequestHeader(value="X-AGL-Local-Test",required=false)String marker,jakarta.servlet.http.HttpServletRequest request) {
        guardLocal(id,marker,request);
        control.resume(id);
        return ApiResp.ok();
    }

    /** 重试：新建 Run，返回新 Run id */
    @PostMapping("/{id}/retry")
    public ApiResp<Long> retry(@PathVariable long id,@org.springframework.web.bind.annotation.RequestHeader(value="X-AGL-Local-Test",required=false)String marker,jakarta.servlet.http.HttpServletRequest request) {
        guardLocal(id,marker,request);
        Run newRun = control.retry(id);
        return ApiResp.ok(newRun.id());
    }
    private void guardLocal(long id,String marker,jakarta.servlet.http.HttpServletRequest request) {
        var constraints=jdbc.queryForList("SELECT t.constraints_json FROM task t JOIN run r ON r.task_id=t.id WHERE r.id=?",String.class,id);
        if(!constraints.isEmpty()&&(constraints.get(0).contains("\"localTargetId\"")||constraints.get(0).contains("\"productionSpecId\"")))LocalTestController.requireLocal(marker,request);
    }

    /** P7-3：Run 列表（最近优先）。 */
    @GetMapping
    public ApiResp<List<Run>> list() {
        List<Run> runs = jdbc.query(
                "SELECT * FROM run ORDER BY id DESC LIMIT 200",
                (rs, i) -> new Run(
                        rs.getLong("id"),
                        rs.getLong("task_id"),
                        rs.getString("status"),
                        getAsLong(rs, "prompt_version_id"),
                        rs.getString("model"),
                        rs.getInt("current_step"),
                        rs.getInt("max_steps"),
                        rs.getInt("token_budget"),
                        rs.getLong("tokens_used"),
                        rs.getString("error_message"),
                        rs.getString("final_result"),
                        toTs(rs.getTimestamp("started_at")),
                        toTs(rs.getTimestamp("finished_at")),
                        toTs(rs.getTimestamp("created_at"))));
        return ApiResp.ok(runs);
    }

    /** P7-3：Run 详情 + Steps + ToolCalls（供时间线 / Drawer）。 */
    @GetMapping("/{id}")
    public ApiResp<Map<String, Object>> detail(@PathVariable long id) {
        Run run = runRepo.findById(id)
                .orElseThrow(() -> new com.aigamelab.common.BusinessException(40401, "Run 不存在: " + id));
        List<Step> steps = stepRepo.findByRunId(id);
        List<Map<String, Object>> stepViews = new ArrayList<>();
        for (Step s : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("step", s);
            item.put("toolCalls", toolCallRepo.findByStep(s.id()));
            stepViews.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", run);
        data.put("steps", stepViews);
        data.put("executionContext",conversation.context(id));
        return ApiResp.ok(data);
    }

    private static Long getAsLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static java.time.LocalDateTime toTs(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
