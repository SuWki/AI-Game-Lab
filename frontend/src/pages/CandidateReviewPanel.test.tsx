import {beforeEach,expect,it,vi} from "vitest";
import {render,screen,fireEvent,waitFor} from "@testing-library/react";
import {QueryClient,QueryClientProvider} from "react-query";
import {api,type ProductionCandidate,type CandidateReview} from "@/api";
import {CandidateReviewPanel} from "./CandidateReviewPanel";
const candidate:ProductionCandidate={id:"candidate",productionId:"production",validationRunId:"run",status:"READY"};
export function reviewFixture():CandidateReview{return {candidateId:"candidate",manifestHash:"fixed-manifest",archiveSha256:"fixed-zip",revision:0,decision:"UNREVIEWED",checks:[{criterionId:"A1",requirementId:"R1",expected:"Restart resets state",method:"browser",evidenceKind:"HUMAN_OBSERVATION",outcome:"NOT_TESTED",evidenceNote:""}],coverage:{total:1,passed:0,failed:0,untested:1,allPassed:false,requirements:[{id:"R1",description:"Restart",status:"NOT_TESTED"}]},engineScope:"survival-web-engine-v1",gate:{candidateCurrent:true,filesVerified:true,humanAccepted:false,eligibleForDelivery:false,reason:"Acceptance missing"}};}
beforeEach(()=>{vi.restoreAllMocks();vi.spyOn(api,"candidateReview").mockResolvedValue(reviewFixture());});
function show(){render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false,cacheTime:0}}})}><CandidateReviewPanel candidate={candidate}/></QueryClientProvider>);}
async function fill(){await screen.findByLabelText("评审者");fireEvent.change(screen.getByLabelText("评审者"),{target:{value:"Fixture operator"}});fireEvent.change(screen.getByLabelText("评审总结"),{target:{value:"Fixed build observed"}});}
it("requires every manual pass with observation and binds the fixed revision",async()=>{
 const saved={...reviewFixture(),revision:1,decision:"ACCEPTED",gate:{...reviewFixture().gate,humanAccepted:true,eligibleForDelivery:true}};const save=vi.spyOn(api,"saveCandidateReview").mockResolvedValue(saved);show();await fill();const accept=screen.getByRole("button",{name:"接受此固定候选"});expect(accept).toBeDisabled();fireEvent.change(screen.getByLabelText("验收 A1 结论"),{target:{value:"PASS"}});expect(accept).toBeDisabled();fireEvent.change(screen.getByLabelText("验收 A1 观察说明"),{target:{value:"Restarted and state reset"}});fireEvent.click(accept);
 await waitFor(()=>expect(save).toHaveBeenCalledWith("candidate",{expectedRevision:0,manifestHash:"fixed-manifest",reviewer:"Fixture operator",summary:"Fixed build observed",decision:"ACCEPTED",checks:[{criterionId:"A1",outcome:"PASS",evidenceNote:"Restarted and state reset"}]}));expect(await screen.findByText("门禁查询：此版本可交付")).toBeInTheDocument();expect(screen.getByRole("status")).toHaveTextContent("原 ZIP 未改写");
});
it("keeps edits on a concurrent revision conflict and reloads only explicitly",async()=>{
 vi.spyOn(api,"saveCandidateReview").mockRejectedValue(new Error("Review changed; reload the latest revision"));show();await fill();fireEvent.click(screen.getByRole("button",{name:"保存评审草稿"}));expect(await screen.findByRole("alert")).toHaveTextContent("Review changed");expect(screen.getByLabelText("评审总结")).toHaveValue("Fixed build observed");vi.mocked(api.candidateReview).mockResolvedValue({...reviewFixture(),revision:3});fireEvent.click(screen.getByRole("button",{name:"重新载入最新评审"}));expect(await screen.findByText("编辑人工意见（基于 r3）")).toBeInTheDocument();expect(screen.getByLabelText("评审总结")).toHaveValue("");
});
it("records rejection without inventing passing observations",async()=>{
 const save=vi.spyOn(api,"saveCandidateReview").mockResolvedValue({...reviewFixture(),revision:1,decision:"REJECTED"});show();await fill();fireEvent.click(screen.getByRole("button",{name:"拒绝此固定候选"}));await waitFor(()=>expect(save).toHaveBeenCalledWith("candidate",expect.objectContaining({decision:"REJECTED",checks:[{criterionId:"A1",outcome:"NOT_TESTED",evidenceNote:""}]})));expect(screen.getByText("门禁查询：此版本尚不可交付")).toBeInTheDocument();
});
it("blocks acceptance of historical candidates even when draft checks pass",async()=>{
 vi.mocked(api.candidateReview).mockResolvedValue({...reviewFixture(),gate:{...reviewFixture().gate,candidateCurrent:false}});show();await fill();fireEvent.change(screen.getByLabelText("验收 A1 结论"),{target:{value:"PASS"}});fireEvent.change(screen.getByLabelText("验收 A1 观察说明"),{target:{value:"Old build observed"}});expect(screen.getByRole("button",{name:"接受此固定候选"})).toBeDisabled();expect(screen.getByRole("button",{name:"拒绝此固定候选"})).toBeEnabled();
});
it.each(["Fixed candidate archive changed","Network unavailable"])("withdraws cached eligibility when revalidation fails: %s",async(message)=>{
 vi.mocked(api.candidateReview).mockResolvedValue({...reviewFixture(),decision:"ACCEPTED",gate:{...reviewFixture().gate,eligibleForDelivery:true}});show();await screen.findByText("门禁查询：此版本可交付");vi.mocked(api.candidateReview).mockRejectedValue(new Error(message));fireEvent.click(screen.getByRole("button",{name:"重新载入最新评审"}));expect(await screen.findByRole("alert")).toHaveTextContent(message);expect(screen.queryByText("门禁查询：此版本可交付")).not.toBeInTheDocument();expect(screen.getByText("门禁查询：当前状态未确认，请重新校验")).toBeInTheDocument();
 vi.mocked(api.candidateReview).mockResolvedValue({...reviewFixture(),gate:{...reviewFixture().gate,eligibleForDelivery:false}});fireEvent.click(screen.getByRole("button",{name:"重新载入最新评审"}));expect(await screen.findByText("门禁查询：此版本尚不可交付")).toBeInTheDocument();
});
