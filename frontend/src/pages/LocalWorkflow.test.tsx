import "@testing-library/jest-dom";
import {beforeEach,describe,it,expect,vi} from "vitest";
import {render,screen,fireEvent,waitFor} from "@testing-library/react";
import {QueryClient,QueryClientProvider} from "react-query";
import {MemoryRouter} from "react-router-dom";
import {api,type LocalAssessment,type LocalTestRun,type LocalChange} from "@/api";
import {LocalEvidencePanel} from "./LocalEvidencePanel";
import {LocalChangesPage} from "./LocalChangesPage";
const sha="a".repeat(64);
const run:LocalTestRun={id:"test-a",target:{id:"game",name:"Game",workingDirectory:"/games/a",command:["node","test.mjs"],timeoutSeconds:30,resultPath:"result.json",versionFiles:["source"]},status:"FAILED",startedAt:"now",finishedAt:"now",exitCode:1,output:"",evidence:{},error:null};
const assessment:LocalAssessment={localTestRunId:run.id,decision:"FAIL",failedChecks:1,totalChecks:1,binding:{versioned:true,version:"local-123",source:{"source/config.txt":sha}}};
const proposal:LocalChange={id:"proposal-a",status:"PENDING",baseRunId:run.id,title:"Fix config",edits:[{path:"source/config.txt",beforeSha256:sha,beforeText:"bad",afterText:"good"}],workspace:null,validationRunId:null,error:null};
function show(node:React.ReactNode){const qc=new QueryClient({defaultOptions:{queries:{retry:false,cacheTime:0}}});return render(<QueryClientProvider client={qc}><MemoryRouter>{node}</MemoryRouter></QueryClientProvider>);}
beforeEach(()=>{vi.restoreAllMocks();vi.spyOn(api,"localAssessment").mockResolvedValue(assessment);});
describe("Versioned local workflow",()=>{
 it("imports complete evidence into the existing finding workflow",async()=>{
  const fn=vi.spyOn(api,"importLocalChecks").mockResolvedValue({...assessment.binding,findingIds:[9],taskId:2,testPlanId:3});show(<LocalEvidencePanel run={run}/>);
  fireEvent.click(await screen.findByRole("button",{name:"导入测试计划与 Finding"}));
  await waitFor(()=>expect(fn).toHaveBeenCalledWith(run.id));expect(await screen.findByRole("status")).toHaveTextContent("已导入");
 });
 it("builds a reviewable proposal without approving it",async()=>{
  vi.spyOn(api,"localSource").mockResolvedValue({path:"source/config.txt",sha256:sha,text:"bad"});
  const create=vi.spyOn(api,"createLocalChange").mockResolvedValue(proposal);const decide=vi.spyOn(api,"decideLocalChange");show(<LocalEvidencePanel run={run}/>);
  const select=await screen.findByLabelText("源文件");fireEvent.change(select,{target:{value:"source/config.txt"}});fireEvent.click(screen.getByRole("button",{name:"读取源文件"}));
  fireEvent.change(await screen.findByLabelText("提案标题"),{target:{value:"Fix config"}});fireEvent.change(screen.getByLabelText("修改后"),{target:{value:"good"}});
  fireEvent.click(screen.getByRole("button",{name:"生成修改提案"}));
  await waitFor(()=>expect(create).toHaveBeenCalledWith({baseRunId:run.id,title:"Fix config",edits:[{path:"source/config.txt",beforeSha256:sha,afterText:"good"}]}));expect(decide).not.toHaveBeenCalled();
 });
 it("requires an explicit approval action after displaying before and after",async()=>{
  vi.spyOn(api,"localChanges").mockResolvedValue([proposal]);const decide=vi.spyOn(api,"decideLocalChange").mockResolvedValue({...proposal,status:"VALIDATING"});show(<LocalChangesPage/>);
  expect(await screen.findByText("bad")).toBeInTheDocument();expect(screen.getByText("good")).toBeInTheDocument();expect(decide).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"批准并隔离验证"}));await waitFor(()=>expect(decide).toHaveBeenCalledWith(proposal.id,"approve"));
 });
 it("does not offer finding import or a patch editor for incomplete evidence",async()=>{
  vi.spyOn(api,"localAssessment").mockResolvedValue({...assessment,decision:"INCONCLUSIVE",reason:"Missing coverage"});show(<LocalEvidencePanel run={run}/>);
  expect(await screen.findByRole("button",{name:"导入测试计划与 Finding"})).toBeDisabled();expect(screen.queryByRole("button",{name:"读取源文件"})).not.toBeInTheDocument();
 });
});
