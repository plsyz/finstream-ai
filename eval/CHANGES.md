# Changes introduced for the evaluation

Modified
- `src/main/java/com/finstream/agents/SanitizationAgent.java` — free-text scrubber (`scrubFreeText`) and `sanitizeForLlm(RawTransaction)`
- `src/main/java/com/finstream/service/PipelineOrchestrator.java` — `finstream.pipeline.sanitize-before-llm` switch; `prepareLlmInput`; sync overload with explicit mode
- `src/main/resources/application.yaml` — `finstream.pipeline.sanitize-before-llm`, `finstream.eval.enabled`
- `README.md` — Evaluation section
- `.gitignore` — eval/results, eval/.venv

Added
- `src/main/java/com/finstream/api/EvalSupportController.java` — `/api/eval/**` (isolated agents, pipeline with mode, graph + vector seeding); only with `finstream.eval.enabled=true`
- `eval/` — dataset generator, runner, analysis, README
- `EVAL-RUNBOOK.md`

Suggested commit sequence (one branch, e.g. `eval/capability-conditioned`)
1. `Add eval-support endpoints and store seeding for per-agent evaluation`
2. `Add sanitize-before-llm hand-off mode with free-text PII scrubbing`
3. `Add capability-conditioned evaluation harness (dataset, runner, analysis)`
4. `Add evaluation runbook and README section`
5. after the run: `Add evaluation results and figures` (commit `results/analysis/` only, not the raw jsonl if large)
