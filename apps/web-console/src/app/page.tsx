"use client";

import { FormEvent, useState } from "react";

import { ChangeRequestReview } from "./change-request-review";

type EvaluationResponse = {
  flagKey: string;
  valueType: "BOOLEAN" | "STRING";
  value: boolean | string;
  variant: string | null;
  reason: string;
  sourceReason: string | null;
  configurationVersion: string | null;
  matchedRuleKey: string | null;
  failedPrerequisiteKey: string | null;
  bucket: number | null;
  stale: boolean;
  error: { code: string; message: string | null };
};

type Status = "idle" | "loading" | "success" | "error";

const hierarchy = ["Acme Commerce", "Checkout Platform", "production", "checkout-v2"];

export default function Home() {
  const [flagKey, setFlagKey] = useState("checkout-v2");
  const [targetingKey, setTargetingKey] = useState("customer-1042");
  const [sdkKey, setSdkKey] = useState("");
  const [attributes, setAttributes] = useState('{"country":{"type":"STRING","value":"BR"}}');
  const [status, setStatus] = useState<Status>("idle");
  const [result, setResult] = useState<EvaluationResponse | null>(null);
  const [message, setMessage] = useState("");

  async function evaluate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setStatus("loading");
    setMessage("");
    setResult(null);

    try {
      const parsedAttributes = JSON.parse(attributes) as Record<string, unknown>;
      const response = await fetch(
        `${process.env.NEXT_PUBLIC_FLAGFORGE_API_URL ?? "http://localhost:8080"}/api/v1/evaluate/${encodeURIComponent(flagKey)}`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${sdkKey}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            type: "BOOLEAN",
            defaultValue: false,
            targetingKey,
            attributes: parsedAttributes,
          }),
        },
      );

      if (!response.ok) {
        const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
        throw new Error(problem?.detail ?? `Evaluation failed with HTTP ${response.status}`);
      }

      setResult((await response.json()) as EvaluationResponse);
      setStatus("success");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Evaluation failed");
      setStatus("error");
    }
  }

  return (
    <main className="shell">
      <aside className="sidebar" aria-label="FlagForge hierarchy">
        <div className="brand"><span className="brandMark">F</span><span>FlagForge</span></div>
        <nav aria-label="Current resource path">
          <ol className="tree">
            {hierarchy.map((item, index) => (
              <li key={item} className={index === hierarchy.length - 1 ? "active" : ""}>
                <span aria-hidden="true">{["◫", "◇", "◉", "⚑"][index]}</span>{item}
              </li>
            ))}
          </ol>
        </nav>
        <p className="sidebarHint">The console displays backend decisions. It never evaluates rules locally.</p>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <div><p className="eyebrow">Evaluation Playground</p><h1>Explain a live flag decision</h1></div>
          <span className="environment">production</span>
        </header>

        <div className="grid">
          <form className="panel" onSubmit={evaluate} aria-label="Evaluation request">
            <div className="panelHeader"><h2>Request</h2><span>BOOLEAN</span></div>
            <label>Flag key<input required value={flagKey} onChange={(event) => setFlagKey(event.target.value)} /></label>
            <label>Targeting key<input required value={targetingKey} onChange={(event) => setTargetingKey(event.target.value)} /></label>
            <label>Environment SDK key<input required type="password" autoComplete="off" value={sdkKey} onChange={(event) => setSdkKey(event.target.value)} placeholder="ff_sdk_…" /></label>
            <label>Typed attributes<textarea rows={8} value={attributes} onChange={(event) => setAttributes(event.target.value)} spellCheck={false} /></label>
            <button disabled={status === "loading"}>{status === "loading" ? "Evaluating…" : "Evaluate flag"}</button>
          </form>

          <section className="panel resultPanel" aria-live="polite" aria-busy={status === "loading"}>
            <div className="panelHeader"><h2>Decision</h2>{result && <span className={result.stale ? "warning" : "healthy"}>{result.stale ? "STALE" : "CURRENT"}</span>}</div>
            {status === "idle" && <div className="empty"><strong>No evaluation yet</strong><p>Submit a targeting context to inspect value, variant, reason and configuration version.</p></div>}
            {status === "loading" && <div className="empty"><strong>Evaluating against FlagForge…</strong><p>The browser delegates the decision to the backend API.</p></div>}
            {status === "error" && <div className="error" role="alert"><strong>Unable to evaluate</strong><p>{message}</p></div>}
            {status === "success" && result && (
              <div className="decision">
                <div className="valueCard"><span>Resolved value</span><strong>{String(result.value)}</strong><small>{result.variant ?? "fallback"}</small></div>
                <dl>
                  <div><dt>Reason</dt><dd>{result.reason}</dd></div>
                  <div><dt>Matched rule</dt><dd>{result.matchedRuleKey ?? "—"}</dd></div>
                  <div><dt>Failed prerequisite</dt><dd>{result.failedPrerequisiteKey ?? "—"}</dd></div>
                  <div><dt>Bucket</dt><dd>{result.bucket ?? "—"}</dd></div>
                  <div><dt>Configuration</dt><dd>{result.configurationVersion ?? "unavailable"}</dd></div>
                  <div><dt>Error</dt><dd>{result.error.code}</dd></div>
                </dl>
              </div>
            )}
          </section>
        </div>

        <ChangeRequestReview />
      </section>
    </main>
  );
}
