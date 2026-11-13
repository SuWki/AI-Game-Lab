import { NavLink, Route, Routes } from "react-router-dom";
import { DashboardPage } from "@/pages/DashboardPage";
import { FindingsPage } from "@/pages/FindingsPage";
import { TestPlanPage } from "@/pages/TestPlanPage";
import { ExperimentCenterPage } from "@/pages/ExperimentCenterPage";
import { RunTracePage } from "@/pages/RunTracePage";
import { ConfigDiffPage } from "@/pages/ConfigDiffPage";
import { ConfigRegressionPage } from "@/pages/ConfigRegressionPage";
import { ResearchPage } from "@/pages/ResearchPage";
import { WorkflowPage } from "@/pages/WorkflowPage";
import { LocalTestsPage } from "@/pages/LocalTestsPage";
import { LocalChangesPage } from "@/pages/LocalChangesPage";
import { ProductionsPage } from "@/pages/ProductionsPage";

function Nav() {
  const link = ({ isActive }: { isActive: boolean }) => ({
    color: isActive ? "#1e40af" : "#374151",
    fontWeight: isActive ? 700 : 400,
    textDecoration: "none",
    marginRight: 16,
  });
  return (
    <nav style={{ padding: "12px 24px", borderBottom: "1px solid #e5e7eb", display: "flex", flexWrap:"wrap", alignItems: "center", gap: 16 }}>
      <strong style={{ marginRight: 24 }}>AI Game Lab · 中台</strong>
      <NavLink to="/" style={link}>Dashboard</NavLink>
      <NavLink to="/findings" style={link}>Findings</NavLink>
      <NavLink to="/test-plans" style={link}>Test Plans</NavLink>
      <NavLink to="/experiments" style={link}>Experiments</NavLink>
      <NavLink to="/runs" style={link}>Run Trace</NavLink>
      <NavLink to="/config-diff" style={link}>Config & Diff</NavLink>
      <NavLink to="/regression" style={link}>Regression</NavLink>
      <NavLink to="/research" style={link}>Research</NavLink>
      <NavLink to="/workflow" style={link}>Workflow</NavLink>
      <NavLink to="/local-tests" style={link}>本地测试</NavLink>
      <NavLink to="/productions" style={link}>游戏生产</NavLink>
    </nav>
  );
}

const navStyle = {
  padding: "12px 24px",
  fontFamily: "system-ui, sans-serif",
  color: "#111827",
};

export function App() {
  return (
    <div style={navStyle}>
      <Nav />
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/findings" element={<FindingsPage />} />
        <Route path="/findings/:id" element={<FindingsPage />} />
        <Route path="/test-plans" element={<TestPlanPage />} />
        <Route path="/test-plans/:id" element={<TestPlanPage />} />
        <Route path="/experiments" element={<ExperimentCenterPage />} />
        <Route path="/runs" element={<RunTracePage />} />
        <Route path="/config-diff" element={<ConfigDiffPage />} />
        <Route path="/regression" element={<ConfigRegressionPage />} />
        <Route path="/research" element={<ResearchPage />} />
        <Route path="/workflow" element={<WorkflowPage />} />
        <Route path="/local-tests" element={<LocalTestsPage />} />
        <Route path="/local-tests/changes" element={<LocalChangesPage />} />
        <Route path="/productions" element={<ProductionsPage />} />
      </Routes>
    </div>
  );
}
