"use client";

import { FormEvent, useState } from "react";

type DifferenceType = "ADDED" | "REMOVED" | "CHANGED";

type CandidateDifference = {
  path: string;
  type: DifferenceType;
  beforeValue: string | null;
  afterValue: string | null;
};

type CandidateDiff = {
  changeRequestId: string;
  fromRevision: number | null;
  candidateRevision: number;
  candidateChecksum: string;
  candidateValid: boolean;
  differences: CandidateDifference[];
};

type RequestState = "idle" | "loading" | "ready" | "error" | "acting";

const apiBase = process.env.NEXT_PUBLIC_FLAGFORGE_API_URL ?? "http://localhost:8080";

export function ChangeRequestReview() {
  const [environmentId, setEnvironmentId] = useState("");
  const [changeRequestId, setChangeRequestId] = useState("");
  const [decisionNote, setDecisionNote] = useState("");
  const [state, setState] = useState<RequestState>("idle");
  const [diff, setDiff] = useState<CandidateDiff | null>(null);
  const [message, setMessage] = useState("");

  async function loadDiff(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState("loading");
    setMessage("");
    setDiff(null);

    try {
      const response = await fetch(
        `${apiBase}/api/v1/environments/${encodeURIComponent(environmentId)}/change-requests/${encodeURIComponent(changeRequestId)}/diff`,
        {
          credentials: "include",
          headers: { Accept: "application/json" },
        },
      );
      if (!response.ok) {
        throw new Error(await problemDetail(response));
      }
      setDiff((await response.json()) as CandidateDiff);
      setState("ready");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Unable to load change request diff");
      setState("error");
    }
  }

  async function decide(action: "approve" | "reject") {
    setState("acting");
    setMessage("");
    try {
      const response = await fetch(
        `${apiBase}/api/v1/environments/${encodeURIComponent(environmentId)}/change-requests/${encodeURIComponent(changeRequestId)}/${action}`,
        {
          method: "POST",
          credentials: "include",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ note: decisionNote }),
        },
      );
      if (!response.ok) {
        throw new Error(await problemDetail(response));
      }
      setMessage(action === "approve" ? "Change request approved." : "Change request rejected.");
      setState("ready");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Unable to record review decision");
      setState("error");
    }
  }

  return (
    <section className="reviewWorkspace" aria-labelledby="change-request-review-title">
      <header className="reviewHeader">
        <div>
          <p className="eyebrow">Protected publication</p>
          <h2 id="change-request-review-title">Review an exact configuration candidate</h2>
        </div>
        <span className="environment">approval workflow</span>
      </header>

      <div className="reviewGrid">
        <form className="panel" onSubmit={loadDiff} aria-label="Change request lookup">
          <div className="panelHeader"><h2>Request</h2><span>CONTROL PLANE</span></div>
          <label>
            Environment ID
            <input required value={environmentId} onChange={(event) => setEnvironmentId(event.target.value)} />
          </label>
          <label>
            Change request ID
            <input required value={changeRequestId} onChange={(event) => setChangeRequestId(event.target.value)} />
          </label>
          <label>
            Review note
            <textarea rows={5} value={decisionNote} onChange={(event) => setDecisionNote(event.target.value)} />
          </label>
          <button disabled={state === "loading" || state === "acting"}>
            {state === "loading" ? "Loading diff…" : "Load candidate diff"}
          </button>
        </form>

        <section className="panel diffPanel" aria-live="polite" aria-busy={state === "loading" || state === "acting"}>
          <div className="panelHeader">
            <h2>Configuration diff</h2>
            {diff && (
              <span className={diff.candidateValid ? "healthy" : "warning"}>
                {diff.candidateValid ? "VALID" : "STALE"}
              </span>
            )}
          </div>

          {state === "idle" && <ReviewEmpty />}
          {state === "loading" && <div className="empty"><strong>Loading exact candidate…</strong><p>The console requests the server-side diff and never reconstructs approval state locally.</p></div>}
          {state === "error" && <div className="error" role="alert"><strong>Unable to review</strong><p>{message}</p></div>}
          {diff && state !== "loading" && (
            <div className="diffContent">
              <dl className="diffMeta">
                <div><dt>From revision</dt><dd>{diff.fromRevision ?? "none"}</dd></div>
                <div><dt>Candidate revision</dt><dd>{diff.candidateRevision}</dd></div>
                <div><dt>Checksum</dt><dd><code>{diff.candidateChecksum}</code></dd></div>
              </dl>

              {diff.differences.length === 0 ? (
                <p className="noChanges">No configuration changes were detected.</p>
              ) : (
                <ol className="diffList" aria-label="Configuration differences">
                  {diff.differences.map((difference) => (
                    <li key={`${difference.type}:${difference.path}`}>
                      <span className={`diffType diff${difference.type}`}>{difference.type}</span>
                      <code>{difference.path}</code>
                      <div className="diffValues">
                        <span><small>Before</small>{difference.beforeValue ?? "—"}</span>
                        <span><small>After</small>{difference.afterValue ?? "—"}</span>
                      </div>
                    </li>
                  ))}
                </ol>
              )}

              <div className="reviewActions">
                <button type="button" disabled={!diff.candidateValid || state === "acting"} onClick={() => void decide("approve")}>Approve exact candidate</button>
                <button type="button" className="dangerButton" disabled={state === "acting"} onClick={() => void decide("reject")}>Reject request</button>
              </div>
              {message && <p className="reviewMessage" role="status">{message}</p>}
            </div>
          )}
        </section>
      </div>
    </section>
  );
}

function ReviewEmpty() {
  return (
    <div className="empty">
      <strong>No candidate loaded</strong>
      <p>Enter an environment and change request ID to inspect the immutable review evidence.</p>
    </div>
  );
}

async function problemDetail(response: Response): Promise<string> {
  const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
  return problem?.detail ?? `Request failed with HTTP ${response.status}`;
}
