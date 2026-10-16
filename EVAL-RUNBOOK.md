# FinStream evaluation runbook

End-to-end procedure for producing the evaluation results in `eval/`. Everything below
runs on one machine with Docker; the LLM calls go to the Anthropic API.

> This repo and `fintech-knowledge-assistant` publish the same host ports (5433, 7474/7687,
> 11434, 8081). Run one project at a time: `docker compose down` in the other repo first.
> Volumes are project-prefixed, so stopping a project does not delete its data.

## 0. Requirements

| Item | Check |
|---|---|
| Docker Desktop with ≥ 6 GB RAM allocated | `docker info \| grep -i memory` |
| JDK 17+, Maven 3.9+ (or `./mvnw`) | `java -version`, `mvn -version` |
| Python 3.10+ | `python3 --version` |
| `ANTHROPIC_API_KEY` in `.env` | `cat .env` |

Budget (Claude Haiku 4.5, 400 transactions, 2 modes, isolated + pipeline):
~3,200 chat calls ≈ 4–6 M tokens ≈ **under USD 10**. Wall time ≈ 45–90 min with `--workers 4`.

## 1. Infrastructure

```bash
cd finstream-ai
docker compose up -d
docker compose ps                                   # all 5 containers "running"
docker exec finstream-ollama ollama pull nomic-embed-text
docker exec finstream-ollama ollama list            # nomic-embed-text listed
```

Health checks:

```bash
docker exec finstream-postgres pg_isready -U finstream_user
curl -s http://localhost:7474 | head -c 100          # Neo4j browser responds
curl -s http://localhost:11434/api/tags              # Ollama lists nomic-embed-text
docker exec finstream-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

If Kafka keeps restarting on Apple Silicon, add `platform: linux/amd64` under the `kafka`
and `zookeeper` services in `docker-compose.yml` and re-run `docker compose up -d`.

## 2. Application (eval endpoints on)

```bash
cp .env.example .env            # once; set ANTHROPIC_API_KEY
export $(cat .env | xargs)
export FINSTREAM_EVAL_ENABLED=true
./mvnw spring-boot:run
```

Wait for `Started FinStreamAiApplication`, then in another terminal:

```bash
curl -s http://localhost:8081/actuator/health            # {"status":"UP"...}
curl -s -X POST http://localhost:8081/api/eval/agents/sanitize \
  -H 'Content-Type: application/json' \
  -d '{"transactionId":"T1","accountHolder":"Jane Smith","accountNumber":"DE89370400440532013000","email":"jane@example.com","merchantName":"X","merchantId":"M","merchantCategory":"5734","amount":1,"currency":"EUR","country":"Germany","ipAddress":"192.168.1.1","description":"test"}'
```

A `404` on `/api/eval/...` means `FINSTREAM_EVAL_ENABLED` was not exported before starting the app.

`finstream.pipeline.sanitize-before-llm` stays `false` (the original behaviour); the harness
selects raw vs. sanitized hand-off per request with `?mode=`.

## 3. Data and seeding

```bash
cd eval
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python generate_dataset.py          # data/transactions.jsonl, graph_seed.json, vector_seed.json
python run_eval.py --seed-stores    # clears the finstream Neo4j DB, seeds 31 merchants, 62 vector docs
```

Verify the graph in the Neo4j browser (http://localhost:7474, `neo4j` / `finstream_password`):

```cypher
MATCH (m:Merchant) RETURN count(m);                       // 31
MATCH ()-[r]->() RETURN type(r), count(r);               // SHARES_IP_WITH 5, SHARES_ADDRESS_WITH 3, PREVIOUSLY_FLAGGED_IN 4
```

and the vector store:

```bash
docker exec finstream-postgres psql -U finstream_user -d finstream_vectorstore -c "select count(*) from vector_store;"   # 62
```

## 4. Smoke test, then full run

```bash
python run_eval.py --limit 10 --stages isolated,pipeline --modes raw,sanitized
python analyze.py                              # tables render, no errors
rm -rf results                                 # discard the smoke run
python run_eval.py --stages isolated,pipeline --modes raw,sanitized --workers 4
python run_eval.py --stages pipeline --modes raw,sanitized --repeats 3 --workers 4   # consistency (optional, adds ~1,600 calls)
python analyze.py
```

If the run is interrupted, re-run the same command: finished transactions are skipped.
Check `results/analysis/summary.md` header for the error count per file; re-run to retry
errored items only after deleting those lines (`grep -v '"error"' file > tmp && mv tmp file`).

## 5. What to look at first

* `results/analysis/summary.md` §1: prompt-level leak in `raw` mode on the `clean` condition — expected
  to be ~1.0 because the raw record (name, IBAN, email, IP) is handed to the LLM agents unchanged.
* §2 gap table and §3 attribution: how many end-to-end failures are "capability present, not applied".
* §4 leak by type, and `obfuscated_pii` in `sanitized` mode: the scrubber's limits.
* §5 injection flip rate; §6 hop collapse; §7 consistency.
* Figures in `results/analysis/figures/`.

## 6. Shut down

```bash
# Ctrl-C the Spring Boot app
docker compose down          # keeps volumes; add -v to wipe seeded data
```
