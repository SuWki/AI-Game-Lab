import type {BuilderBudget} from "./types";

export function BuilderBudgetPanel({budget}:{budget:BuilderBudget}){
  return <section style={{border:"1px solid #ddd",padding:12,margin:"12px 0"}} aria-label="Builder 累计预算">
    <h3>Builder 累计预算 · 任务 #{budget.taskId}</h3>
    <p>累计 token：{budget.tokensUsed} / {budget.tokenLimit} · 累计步骤：{budget.stepsUsed} / {budget.stepLimit}</p>
    <p>会话截止：{new Date(budget.deadlineEpochMs).toLocaleString()} · 当前 Run #{budget.activeRunId??"待启动"}</p>
    {budget.estimatedCostUsd===null?<p>费用：未配置单价，金额未知。</p>:<p>已报告用量的估算费用：USD {budget.estimatedCostUsd}{budget.maxCostUsd?` / ${budget.maxCostUsd}`:""}。不等于供应商账单。</p>}
    {budget.unsettledUsageTurns>0&&<p>有 {budget.unsettledUsageTurns} 次请求用量尚未确定，未计入金额估算。</p>}
    <p>重试沿用同一生产、候选链及累计消耗；单次模型响应可能超出剩余预算。</p>
  </section>;
}
