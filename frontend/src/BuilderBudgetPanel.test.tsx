import {expect,it} from "vitest";
import {render,screen} from "@testing-library/react";
import {BuilderBudgetPanel} from "./BuilderBudgetPanel";
import type {BuilderBudget} from "./types";
const base:BuilderBudget={taskId:9,activeRunId:12,tokensUsed:40,tokenLimit:100,stepsUsed:2,stepLimit:10,deadlineEpochMs:1900000000000,pricingStatus:"UNCONFIGURED",estimatedCostUsd:null,unsettledUsageTurns:1};
it("shows cumulative usage without inventing a free price",()=>{render(<BuilderBudgetPanel budget={base}/>);expect(screen.getByText(/累计 token：40 \/ 100/)).toBeInTheDocument();expect(screen.getByText("费用：未配置单价，金额未知。")).toBeInTheDocument();expect(screen.getByText(/1 次请求用量尚未确定/)).toBeInTheDocument();expect(screen.queryByText(/USD 0/)).not.toBeInTheDocument();});
it("labels frozen reported-usage estimates separately from the bill",()=>{render(<BuilderBudgetPanel budget={{...base,estimatedCostUsd:"0.000100",maxCostUsd:"1",pricingStatus:"ESTIMATE_REPORTED_USAGE",unsettledUsageTurns:0}}/>);expect(screen.getByText(/USD 0.000100 \/ 1/)).toHaveTextContent("不等于供应商账单");expect(screen.queryByText(/用量尚未确定/)).not.toBeInTheDocument();});
