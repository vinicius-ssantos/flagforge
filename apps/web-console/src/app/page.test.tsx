import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import Home from "./page";

afterEach(() => vi.restoreAllMocks());

describe("Evaluation Playground", () => {
  it("renders hierarchy and accessible empty state", () => {
    render(<Home />);

    expect(screen.getByRole("navigation", { name: "Current resource path" })).toBeInTheDocument();
    expect(screen.getByRole("form", { name: "Evaluation request" })).toBeInTheDocument();
    expect(screen.getByText("No evaluation yet")).toBeInTheDocument();
    expect(screen.getByLabelText("Environment SDK key")).toHaveAttribute("type", "password");
  });

  it("delegates evaluation to the backend and explains the result", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          flagKey: "checkout-v2",
          valueType: "BOOLEAN",
          value: true,
          variant: "enabled",
          reason: "TARGETING_MATCH",
          sourceReason: null,
          configurationVersion: "revision-42",
          matchedRuleKey: "internal-beta",
          failedPrerequisiteKey: null,
          bucket: 18472,
          stale: false,
          error: { code: "NONE", message: null },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    render(<Home />);

    fireEvent.change(screen.getByLabelText("Environment SDK key"), {
      target: { value: "ff_sdk_test_secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Evaluate flag" }));

    await waitFor(() => expect(screen.getByText("TARGETING_MATCH")).toBeInTheDocument());
    expect(screen.getByText("internal-beta")).toBeInTheDocument();
    expect(screen.getByText("18472")).toBeInTheDocument();
    expect(screen.getByText("revision-42")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ method: "POST" });
  });

  it("shows API problem details without exposing the SDK key", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ detail: "Authentication is required to access this resource." }), {
        status: 401,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );
    render(<Home />);

    fireEvent.change(screen.getByLabelText("Environment SDK key"), {
      target: { value: "ff_sdk_private" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Evaluate flag" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Authentication is required to access this resource.",
    );
    expect(screen.queryByText("ff_sdk_private")).not.toBeInTheDocument();
  });
});
