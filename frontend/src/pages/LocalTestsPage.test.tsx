import "@testing-library/jest-dom";
import { beforeEach,describe,it,expect,vi } from "vitest";
import { render,screen,fireEvent,waitFor } from "@testing-library/react";
import { QueryClient,QueryClientProvider } from "react-query";
import { api, type LocalTestRun } from "@/api";
import { LocalTestsPage } from "./LocalTestsPage";
import { MemoryRouter } from "react-router-dom";
const target={id:"game-a",name:"Example game",workingDirectory:"/games/a",command:["node","test.mjs"],timeoutSeconds:30,resultPath:"result.json"};
const run:LocalTestRun={id:"run-1",target,status:"PASSED",startedAt:"2026-11-12",finishedAt:"2026-11-12",exitCode:0,output:"actual test output",evidence:{status:"PASS",checks:3},error:null};
function show(){
 const client=new QueryClient({defaultOptions:{queries:{retry:false,cacheTime:0}}});
 return render(<QueryClientProvider client={client}><MemoryRouter><LocalTestsPage/></MemoryRouter></QueryClientProvider>);
}
beforeEach(()=>{
 vi.restoreAllMocks();
 vi.spyOn(api,"localTestTargets").mockResolvedValue([target]);
 vi.spyOn(api,"localTestRuns").mockResolvedValue([]);
 vi.spyOn(api,"localTestRun").mockResolvedValue(run);
});
describe("Local game tests",()=>{
 it("starts registered target and displays actual evidence",async()=>{
  const start=vi.spyOn(api,"startLocalTest").mockResolvedValue(run);show();
  fireEvent.click(await screen.findByRole("button",{name:"运行测试"}));
  await waitFor(()=>expect(start).toHaveBeenCalledWith("game-a"));
  expect(await screen.findByText("actual test output")).toBeInTheDocument();
  expect(screen.getByText(/"checks": 3/)).toBeInTheDocument();
 });
 it("shows start failure instead of creating a successful result",async()=>{
  vi.spyOn(api,"startLocalTest").mockRejectedValue(new Error("queue full"));show();
  fireEvent.click(await screen.findByRole("button",{name:"运行测试"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("queue full");
  expect(screen.queryByText(/任务详情/)).not.toBeInTheDocument();
 });
 it("cancels a running task",async()=>{
  vi.spyOn(api,"localTestRuns").mockResolvedValue([{...run,status:"RUNNING"}]);
  const cancel=vi.spyOn(api,"cancelLocalTest").mockResolvedValue({...run,status:"CANCELLED"});show();
  fireEvent.click(await screen.findByRole("button",{name:"取消"}));
  await waitFor(()=>expect(cancel).toHaveBeenCalledWith("run-1"));
 });
 it("explains empty target registration",async()=>{
  vi.spyOn(api,"localTestTargets").mockResolvedValue([]);show();
  expect(await screen.findByText(/尚未注册目标/)).toBeInTheDocument();
 });
});
