import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import Home from "./page";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

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

  it("loads an exact candidate diff and records approval", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            changeRequestId: "request-1",
            fromRevision: 3,
            candidateRevision: 4,
            candidateChecksum: "a".repeat(64),
            candidateValid: true,
            differences: [
              {
                path: "flags.checkout-v2.defaultVariant",
                type: "CHANGED",
                beforeValue: "disabled",
                afterValue: "enabled",
              },
            ],
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ state: "APPROVED" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    render(<Home />);

    fireEvent.change(screen.getByLabelText("Environment ID"), {
      target: { value: "environment-1" },
    });
    fireEvent.change(screen.getByLabelText("Change request ID"), {
      target: { value: "request-1" },
    });
    fireEvent.change(screen.getByLabelText("Review note"), {
      target: { value: "Reviewed against the release plan" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Load candidate diff" }));

    expect(await screen.findByText("flags.checkout-v2.defaultVariant")).toBeInTheDocument();
    expect(screen.getByText("disabled")).toBeInTheDocument();
    expect(screen.getByText("enabled")).toBeInTheDocument();
    expect(screen.getByText("VALID")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Approve exact candidate" }));

    expect(await screen.findByRole("status")).toHaveTextContent("Change request approved.");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      credentials: "include",
    });
  });

  it("prevents approval when the server marks the candidate stale", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          changeRequestId: "request-stale",
          fromRevision: 2,
          candidateRevision: 3,
          candidateChecksum: "b".repeat(64),
          candidateValid: false,
          differences: [],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    render(<Home />);

    fireEvent.change(screen.getByLabelText("Environment ID"), {
      target: { value: "environment-1" },
    });
    fireEvent.change(screen.getByLabelText("Change request ID"), {
      target: { value: "request-stale" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Load candidate diff" }));

    expect(await screen.findByText("STALE")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve exact candidate" })).toBeDisabled();
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
