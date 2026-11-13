import "@testing-library/jest-dom";
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "react-query";
import { BrowserRouter } from "react-router-dom";
import { App } from "@/App";

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <BrowserRouter>{children}</BrowserRouter>
    </QueryClientProvider>
  );
}

describe("App navigation", () => {
  it("renders Nav links for all P7 pages", () => {
    render(<App />, { wrapper });
    expect(screen.getByRole("link", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Findings" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Test Plans" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Experiments" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Run Trace" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Research" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Workflow" })).toBeInTheDocument();
  });

  it("defaults to Dashboard page at /", () => {
    render(<App />, { wrapper });
    expect(screen.getByRole("heading", { name: /dashboard/i })).toBeInTheDocument();
  });
});
