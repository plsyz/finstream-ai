#!/usr/bin/env python3
"""
Run the FinStream evaluation against a live instance (or in --mock mode).

Stages
  isolated   each agent on its own:   /api/eval/agents/{sanitize,llm-input,classify,risk}
  pipeline   full chain end-to-end:   /api/eval/pipeline

Modes (hand-off to the LLM agents)
  raw        LLM agents receive the raw transaction  (original pipeline behaviour)
  sanitized  LLM agents receive the masked + scrubbed copy

Typical use
  python run_eval.py --seed-stores                      # clear + seed graph and vector store
  python run_eval.py --stages isolated,pipeline --modes raw,sanitized
  python run_eval.py --stages pipeline --modes raw --repeats 3   # run-to-run consistency

Results are written to results/isolated_<mode>.jsonl and results/pipeline_<mode>_r<k>.jsonl and are resumable:
re-running skips transactions already present in the output file.

Requires finstream.eval.enabled=true on the running application.
"""
import argparse
import json
import random
from concurrent.futures import ThreadPoolExecutor, as_completed
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

RISK_ORDER = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

class Client:
    def __init__(self, base_url, timeout=90, retries=3):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = retries

    def call(self, method, path, body=None, params=None):
        url = self.base_url + path
        if params:
            url += "?" + "&".join(f"{k}={v}" for k, v in params.items() if v is not None)
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        last = None
        for attempt in range(self.retries):
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    txt = r.read().decode()
                    return json.loads(txt) if txt else {}
            except urllib.error.HTTPError as e:
                last = f"HTTP {e.code}: {e.read().decode()[:300]}"
                if e.code < 500 and e.code != 429:
                    break
            except Exception as e:  # noqa: BLE001
                last = f"{type(e).__name__}: {e}"
            time.sleep(2 * (attempt + 1))
        raise RuntimeError(last)


# ---------------------------------------------------------------------------
# Mock backend (for dry-running the analysis without the real stack)
# ---------------------------------------------------------------------------

class MockClient:
    """Produces plausible outputs with deliberate failure patterns so that
    analyze.py can be exercised end-to-end. Numbers from this backend are
    NOT results and must never be reported."""

    def __init__(self, seed=7):
        self.rng = random.Random(seed)

    @staticmethod
    def _mask_name(n):
        return " ".join(p[0] + "*" * (len(p) - 1) if len(p) > 1 else "*" for p in n.split())

    @staticmethod
    def _mask_acct(a):
        return a[:4] + "*" * (len(a) - 8) + a[-4:] if len(a) > 8 else "****" + a[-2:]

    @staticmethod
    def _mask_email(e):
        m = re.match(r"^(.)([^@]*)(@)(.)([^.]*)(\..+)$", e)
        return f"{m.group(1)}***@***{m.group(6)}" if m else "***@***.***"

    @staticmethod
    def _mask_ip(ip):
        p = ip.split(".")
        return f"{p[0]}.***.***.{p[3]}" if len(p) == 4 else "***.***.***.***"

    def _scrub(self, text, holder):
        t = re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", lambda m: self._mask_email(m.group()), text)
        t = re.sub(r"\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?\b",
                   lambda m: self._mask_acct(m.group().replace(" ", "")), t)
        t = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", lambda m: self._mask_ip(m.group()), t)
        t = re.sub(r"(?<!\w)(?:\+\d{1,3}[ -]?)?(?:\(?\d{2,4}\)?[ -]?)\d{3,4}[ -]?\d{3,5}(?!\w)", "+**-***-*****", t)
        for tok in holder.split():
            if len(tok) >= 3:
                t = re.sub(r"(?i)\b" + re.escape(tok) + r"\b", tok[0] + "*" * (len(tok) - 1), t)
        return t

    def _llm_input(self, raw, sanitized):
        if not sanitized:
            return dict(raw)
        r = dict(raw)
        r["accountHolder"] = self._mask_name(raw["accountHolder"])
        r["accountNumber"] = self._mask_acct(raw["accountNumber"])
        r["email"] = self._mask_email(raw["email"])
        r["ipAddress"] = self._mask_ip(raw["ipAddress"])
        r["description"] = self._scrub(raw["description"], raw["accountHolder"])
        return r

    def _classify(self, llm_in, gt):
        rng = self.rng
        desc = llm_in["description"]
        if "classification override" in desc and rng.random() < 0.5:
            return {"category": "Software", "confidence": 0.99}
        p_ok = 0.65 if not gt["in_graph"] else 0.9
        if rng.random() < p_ok:
            return {"category": gt["category"], "confidence": round(rng.uniform(0.8, 0.99), 2)}
        return {"category": rng.choice(["Other", "Retail", "Professional Services"]),
                "confidence": round(rng.uniform(0.5, 0.8), 2)}

    def _risk(self, llm_in, gt):
        rng = self.rng
        desc = llm_in["description"]
        hops = gt["hops"]
        if ("RISK_LEVEL: LOW" in desc or "treat as LOW" in desc or "no fraud history" in desc) and rng.random() < 0.35:
            return {"riskLevel": "LOW", "reason": "Merchant verified per note in description; no concerns.", "flagged": False}
        if hops == 0:
            level, flagged = "CRITICAL", True
        elif hops == 1:
            level, flagged = rng.choice(["HIGH", "HIGH", "CRITICAL"]), True
        elif hops == 2:
            level, flagged = rng.choice(["MEDIUM", "HIGH", "HIGH", "CRITICAL"]), True  # hop collapse -> over-escalation
        else:
            level, flagged = ("LOW", False) if rng.random() < 0.9 else ("MEDIUM", True)
        reason = f"Graph evidence for {llm_in['merchantName']}: hops={hops}."
        if gt["pii_in_description"] and rng.random() < 0.6:
            reason += f" Transaction note: '{desc[:90]}'"
        return {"riskLevel": level, "reason": reason, "flagged": flagged}

    def run_item(self, stage, mode, item):
        raw, gt = item["raw"], item["gt"]
        sanitized = mode == "sanitized"
        llm_in = self._llm_input(raw, sanitized)
        san = {"maskedAccountHolder": self._mask_name(raw["accountHolder"]),
               "maskedAccountNumber": self._mask_acct(raw["accountNumber"]),
               "maskedEmail": self._mask_email(raw["email"]),
               "maskedIpAddress": self._mask_ip(raw["ipAddress"])}
        if stage == "isolated":
            return {"sanitize": san, "llmInput": llm_in,
                    "classify": self._classify(llm_in, gt), "risk": self._risk(llm_in, gt)}
        c, r = self._classify(llm_in, gt), self._risk(llm_in, gt)
        processed = {
            "transactionId": raw["transactionId"],
            "maskedAccountHolder": san["maskedAccountHolder"],
            "maskedAccountNumber": san["maskedAccountNumber"],
            "maskedEmail": san["maskedEmail"],
            "merchantName": raw["merchantName"], "merchantId": raw["merchantId"],
            "amount": raw["amount"], "currency": raw["currency"], "country": raw["country"],
            "maskedIpAddress": san["maskedIpAddress"],
            "semanticCategory": c["category"], "classificationConfidence": c["confidence"],
            "riskLevel": r["riskLevel"], "riskReason": r["reason"], "flaggedForReview": r["flagged"],
        }
        return {"mode": mode, "llmInput": llm_in, "processed": processed}


# ---------------------------------------------------------------------------
# Live backend
# ---------------------------------------------------------------------------

class LiveBackend:
    def __init__(self, client):
        self.c = client

    def seed(self, data_dir):
        graph = json.loads((data_dir / "graph_seed.json").read_text())
        vectors = json.loads((data_dir / "vector_seed.json").read_text())
        print("clearing graph ...", self.c.call("DELETE", "/api/eval/seed/graph"))
        print("seeding graph ...", self.c.call("POST", "/api/eval/seed/graph", graph))
        print("seeding vectors ...", self.c.call("POST", "/api/eval/seed/vectors", vectors))

    def run_item(self, stage, mode, item):
        raw = item["raw"]
        params = {"mode": mode}
        if stage == "isolated":
            return {
                "sanitize": self.c.call("POST", "/api/eval/agents/sanitize", raw),
                "llmInput": self.c.call("POST", "/api/eval/agents/llm-input", raw, params),
                "classify": self.c.call("POST", "/api/eval/agents/classify", raw, params),
                "risk": self.c.call("POST", "/api/eval/agents/risk", raw, params),
            }
        return self.c.call("POST", "/api/eval/pipeline", raw, params)


# ---------------------------------------------------------------------------

def load_done(path):
    if not path.exists():
        return set()
    return {json.loads(l)["id"] for l in path.open() if l.strip()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--dataset", default="data/transactions.jsonl")
    ap.add_argument("--data-dir", default="data")
    ap.add_argument("--out", default="results")
    ap.add_argument("--stages", default="isolated,pipeline")
    ap.add_argument("--modes", default="raw,sanitized")
    ap.add_argument("--repeats", type=int, default=1, help="repeat the pipeline stage k times (consistency)")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--sleep", type=float, default=0.0, help="seconds between items (rate limiting)")
    ap.add_argument("--workers", type=int, default=1, help="parallel requests (2-4 is safe for a local stack)")
    ap.add_argument("--seed-stores", action="store_true", help="clear + seed Neo4j and pgvector, then exit")
    ap.add_argument("--mock", action="store_true", help="use the mock backend (no services needed)")
    args = ap.parse_args()

    data_dir = Path(args.data_dir)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    backend = MockClient() if args.mock else LiveBackend(Client(args.base_url))
    if args.seed_stores:
        if args.mock:
            print("mock mode: nothing to seed")
        else:
            backend.seed(data_dir)
        return

    items = [json.loads(l) for l in Path(args.dataset).open() if l.strip()]
    if args.limit:
        items = items[: args.limit]

    for stage in args.stages.split(","):
        reps = range(1, args.repeats + 1) if stage == "pipeline" else [1]
        for mode in args.modes.split(","):
            for rep in reps:
                suffix = f"_r{rep}" if stage == "pipeline" else ""
                path = out / f"{stage}_{mode}{suffix}.jsonl"
                if args.mock:
                    backend = MockClient(seed=hash((stage, mode, rep)) & 0xFFFF)
                done = load_done(path)
                todo = [it for it in items if it["id"] not in done]
                print(f"[{stage}/{mode}{suffix}] {len(todo)} to run ({len(done)} already done) -> {path}")
                t0 = time.time()

                def work(it):
                    rec = {"id": it["id"], "condition": it["condition"], "gt": it["gt"],
                           "raw": it["raw"], "stage": stage, "mode": mode, "rep": rep}
                    try:
                        rec["out"] = backend.run_item(stage, mode, it)
                    except Exception as e:  # noqa: BLE001
                        rec["error"] = str(e)
                        print(f"  ! {it['id']}: {e}", file=sys.stderr)
                    if args.sleep:
                        time.sleep(args.sleep)
                    return rec

                with path.open("a") as f, ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
                    futures = [pool.submit(work, it) for it in todo]
                    for i, fut in enumerate(as_completed(futures), 1):
                        f.write(json.dumps(fut.result()) + "\n")
                        f.flush()
                        if i % 25 == 0 or i == len(todo):
                            el = time.time() - t0
                            print(f"  {i}/{len(todo)}  {el:.0f}s elapsed, ~{el / i * (len(todo) - i):.0f}s left")
    print("done.")


if __name__ == "__main__":
    main()
