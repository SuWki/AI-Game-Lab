import "@testing-library/jest-dom";
import {beforeEach,expect,it,vi} from "vitest";
import {render,screen,fireEvent,waitFor} from "@testing-library/react";
import {QueryClient,QueryClientProvider} from "react-query";import {MemoryRouter} from "react-router-dom";
import {api,type ProductionJob} from "@/api";import {ProductionsPage} from "./ProductionsPage";
const job:ProductionJob={id:"production-a",taskId:1,specId:"spec-a",specRevision:2,specHash:"hash",status:"RUNNING",localRunId:"execution-a"};
beforeEach(()=>{vi.restoreAllMocks();vi.spyOn(api,"productionCandidates").mockResolvedValue([]);vi.spyOn(api,"listTasks").mockResolvedValue([{id:1,title:"Game",goal:"build",constraintsJson:"{}",maxSteps:20,approvalRequired:true,status:"ACTIVE",createdAt:null}]);vi.spyOn(api,"productionArtifacts").mockResolvedValue([{artifactId:"spec-a",revision:2,kind:"GameSpec",taskId:1,contentHash:"hash",content:{title:"Heat game"}}]);vi.spyOn(api,"productionJobs").mockResolvedValue([]);});
function show(){render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false,cacheTime:0}}})}><MemoryRouter><ProductionsPage/></MemoryRouter></QueryClientProvider>);}
it("submits the exact selected spec revision",async()=>{const start=vi.spyOn(api,"startProduction").mockResolvedValue(job);show();await screen.findByRole("option",{name:"Game · #1"});fireEvent.change(await screen.findByLabelText("任务"),{target:{value:"1"}});await screen.findByRole("option",{name:"Heat game · r2"});fireEvent.change(screen.getByLabelText("规格版本"),{target:{value:"spec-a:2"}});fireEvent.click(screen.getByRole("button",{name:"生成游戏项目"}));await waitFor(()=>expect(start).toHaveBeenCalledWith(expect.objectContaining({taskId:1,specId:"spec-a",specRevision:2})));});
it("exposes active-job cancellation and execution evidence",async()=>{vi.spyOn(api,"productionJobs").mockResolvedValue([job]);const cancel=vi.spyOn(api,"cancelProduction").mockResolvedValue({...job,status:"CANCELLED"});show();fireEvent.click(await screen.findByRole("button",{name:"取消生产"}));await waitFor(()=>expect(cancel).toHaveBeenCalledWith(job.id));expect(screen.getByRole("link",{name:"查看执行日志与证据"})).toHaveAttribute("href","/local-tests?runId=execution-a");});
it("shows submission errors without claiming a build succeeded",async()=>{vi.spyOn(api,"productionJobs").mockResolvedValue([{...job,status:"FAILED",error:"Template changed"}]);show();expect(await screen.findByRole("alert")).toHaveTextContent("Template changed");expect(screen.queryByText(/项目目录/)).not.toBeInTheDocument();});
it("starts independent verification for a built project",async()=>{vi.spyOn(api,"productionJobs").mockResolvedValue([{...job,status:"BUILT"}]);const verify=vi.spyOn(api,"validateProduction").mockResolvedValue({...job,status:"BUILT",validationRunId:"check-a",validationStatus:"QUEUED"});show();fireEvent.click(await screen.findByRole("button",{name:"执行独立验收"}));await waitFor(()=>expect(verify).toHaveBeenCalledWith(job.id));});
it("offers a retry for an interrupted independent verification", async () => {
  vi.spyOn(api, "productionJobs").mockResolvedValue([
    { ...job, status: "BUILT", validationRunId: "interrupted-check", validationStatus: "INTERRUPTED" },
  ]);
  const verify = vi.spyOn(api, "validateProduction").mockResolvedValue({
    ...job, status: "BUILT", validationRunId: "recovered-check", validationStatus: "QUEUED",
  });
  show();
  fireEvent.click(await screen.findByRole("button", { name: "重试中断的独立验收" }));
  await waitFor(() => expect(verify).toHaveBeenCalledWith(job.id));
});
it("links the current repair candidate and allows cancelling pending approval",async()=>{
 vi.spyOn(api,"productionJobs").mockResolvedValue([{...job,status:"BUILT",validationRunId:"original",iteration:{phase:"WAITING_INPUT",reason:"Pending approval",repairCount:2,repairLimit:5,currentRunId:"candidate",attempts:[{proposalId:"proposal",baseRunId:"candidate",status:"PENDING"}]}}]);
 show();expect(await screen.findByRole("heading",{name:"修复阶段 · WAITING_INPUT"})).toBeInTheDocument();expect(screen.getByText("提案次数：2 / 5")).toBeInTheDocument();expect(screen.getByRole("link",{name:"查看验收与修改源码"})).toHaveAttribute("href","/local-tests?runId=candidate");expect(screen.getByRole("button",{name:"取消生产"})).toBeEnabled();
});

it("creates a Builder task bound to the selected revision",async()=>{const create=vi.spyOn(api,"createBuilderTask").mockResolvedValue({id:9,title:"Builder",goal:"build",constraintsJson:"{}",maxSteps:20,approvalRequired:true,status:"ACTIVE",createdAt:null});show();await screen.findByRole("option",{name:"Game · #1"});fireEvent.change(screen.getByLabelText("任务"),{target:{value:"1"}});await screen.findByRole("option",{name:"Heat game · r2"});fireEvent.change(screen.getByLabelText("规格版本"),{target:{value:"spec-a:2"}});fireEvent.click(screen.getByRole("button",{name:"创建 Builder 任务"}));await waitFor(()=>expect(create).toHaveBeenCalledWith(expect.objectContaining({taskId:1,specId:"spec-a",specRevision:2})));expect(await screen.findByRole("status")).toHaveTextContent("#9");});
