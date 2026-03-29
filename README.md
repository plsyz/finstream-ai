# FinStream AI

An event-driven, multi-agent pipeline that ingests financial transactions via Apache Kafka, processes them through three specialized AI agents — PII sanitization, semantic classification via vector RAG, and graph-based fraud detection via Neo4j GraphRAG — and outputs fully enriched, compliance-ready transactions in real time.

### Key Highlights

- **Multi-agent architecture** — three autonomous agents, each with a single responsibility, chained sequentially through a Kafka-driven orchestrator
- **Dual RAG pattern** — combines vector similarity search (pgvector) for transaction classification with knowledge graph traversal (Neo4j) for fraud detection in a single pipeline
- **Production resilience** — Resilience4j circuit breakers and retry policies on the fraud detection agent; graceful degradation to manual review when infrastructure is degraded
- **Privacy-first design** — PII is masked deterministically before any data reaches external LLM services or downstream consumers
- **Event-driven throughput** — Kafka-partitioned ingestion with async processing; supports both real-time streaming and synchronous request-response modes

### Tech at a Glance

`Spring Boot 3.5` · `Spring AI` · `Apache Kafka` · `PostgreSQL + pgvector` · `Neo4j 5` · `Ollama` · `Claude API` · `Resilience4j` · `Docker Compose`

---

## System Overview

```
                         POST /api/transactions
                                  |
                                  v
                    +-------------------------+
                    |  Kafka: raw-transactions |
                    +-------------------------+
                                  |
                                  v
          +------------------------------------------------+
          |         PIPELINE ORCHESTRATOR                   |
          |         (@KafkaListener, 3 partitions)          |
          |                                                |
          |  +------------------------------------------+  |
          |  | AGENT 1: PII Sanitizer                   |  |
          |  | Deterministic regex masking — no LLM     |  |
          |  | Name, account, email, IP -> masked        |  |
          |  | Latency: ~1ms                            |  |
          |  +------------------------------------------+  |
          |                    |                            |
          |                    v                            |
          |  +------------------------------------------+  |
          |  | AGENT 2: Semantic Classifier              |  |
          |  | pgvector similarity search + LLM          |  |
          |  | -> spending category + confidence score   |  |
          |  +------------------------------------------+  |
          |                    |                            |
          |                    v                            |
          |  +------------------------------------------+  |
          |  | AGENT 3: Graph Anomaly Detector           |  |
          |  | Neo4j multi-hop Cypher traversal           |  |
          |  | + LLM risk interpretation                  |  |
          |  | @CircuitBreaker + @Retry protection        |  |
          |  | -> risk level + evidence-based explanation  |  |
          |  +------------------------------------------+  |
          |                    |                            |
          +------------------------------------------------+
                                  |
                                  v
                  +-----------------------------+
                  | Kafka: processed-transactions |
                  +-----------------------------+
```

---

## Architecture

```
+-----------------------------------------------------------------------+
|                     Spring Boot Application                           |
|                                                                       |
|  TransactionController                                                |
|       |                                                               |
|       v                                                               |
|  KafkaTemplate -----> [raw-transactions] -----> PipelineOrchestrator  |
|                                                      |                |
|                                          +-----------+-----------+    |
|                                          |           |           |    |
|                                    Sanitizer   Classifier   Anomaly   |
|                                    (Regex)    (pgvector    (Neo4j     |
|                                                + LLM)      + LLM)    |
|                                          |           |           |    |
|                                          +-----------+-----------+    |
|                                                      |                |
|                                                      v                |
|                                        [processed-transactions]       |
+-----------------------------------------------------------------------+
         |                |               |              |
  +------------+  +-------------+  +-----------+  +------------+
  | PostgreSQL |  | Neo4j 5     |  | Kafka     |  | LLM API    |
  | + pgvector |  | (Fraud      |  | (Broker)  |  | (Chat)     |
  | Port 5433  |  |  Graph)     |  | Port 9092 |  +------------+
  +------------+  | Port 7687   |  +-----------+  +------------+
                  +-------------+                 | Ollama     |
                                                  | (Embeddings)|
                                                  | Port 11434 |
                                                  +------------+
```

## Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Framework | Spring Boot 3.5 | REST API, dependency injection, configuration |
| AI Framework | Spring AI 1.1.2 | ChatClient, VectorStore, embedding integration |
| Chat Model | Anthropic Claude Haiku | LLM inference for classification and risk assessment |
| Embedding Model | nomic-embed-text (Ollama) | 768-dimensional vectors for semantic similarity |
| Message Broker | Apache Kafka | Asynchronous transaction ingestion and egress |
| Vector Store | PostgreSQL + pgvector | Semantic similarity search for transaction classification |
| Knowledge Graph | Neo4j 5 Community | Merchant fraud subgraph queries via multi-hop Cypher |
| Resilience | Resilience4j | Circuit breaker + retry on the anomaly detection agent |
| Observability | Spring Boot Actuator | Health checks, metrics, circuit breaker state |

---

## Agent Details

### Agent 1: PII Sanitizer
| Property | Value |
|----------|-------|
| Type | Deterministic (pure Java) |
| Purpose | Mask personally identifiable information before data leaves the pipeline |
| Fields | Account holder name, account number, email, IP address |
| Latency | ~1ms per transaction |
| LLM calls | None — regex patterns for speed and predictability |

### Agent 2: Semantic Classifier (pgvector RAG)
| Property | Value |
|----------|-------|
| Type | AI-powered (VectorStore + LLM) |
| Purpose | Classify each transaction into a semantic spending category |
| Flow | Build search query from merchant/description -> retrieve top-5 similar docs from pgvector -> LLM classifies with confidence score |
| Categories | Software, Meals & Entertainment, Travel, Professional Services, Office Supplies, Financial Services, Healthcare, Retail, Utilities, Subscriptions, Other |

### Agent 3: Graph Anomaly Detector (Neo4j GraphRAG)
| Property | Value |
|----------|-------|
| Type | AI-powered (Knowledge Graph + LLM) with circuit breaker |
| Purpose | Detect fraud risk through multi-hop relationship analysis |
| Neo4j Query | 2-hop Cypher traversal across `SHARES_IP_WITH`, `SHARES_ADDRESS_WITH`, and `PREVIOUSLY_FLAGGED_IN` relationships |
| LLM Prompt | Structured system prompt with explicit interpretation rules for each graph signal type |
| Resilience | `@CircuitBreaker` (50% failure threshold, 30s open state) + `@Retry` (3 attempts, 2s backoff) |
| Fallback | On infrastructure failure, flags the transaction as MEDIUM risk for manual review |

**Risk signal hierarchy:**

| Signal | Severity | Meaning |
|--------|----------|---------|
| Direct prior fraud cases | CRITICAL | Merchant itself was involved in historical fraud |
| Shared IP with flagged entity | STRONG | Possible shell company or proxy operation |
| Shared address with flagged entity | MODERATE-STRONG | Co-location in a potential fraud ring |
| Network neighbor has fraud cases | MODERATE | Guilt by association — requires investigation |
| No connections found | LOW | No graph-based risk signals — assess on transaction characteristics |

---

## Knowledge Graph Schema

```
(:Merchant)-[:SHARES_IP_WITH]->(:Merchant)
(:Merchant)-[:SHARES_ADDRESS_WITH]->(:Merchant)
(:Merchant)-[:PREVIOUSLY_FLAGGED_IN]->(:FraudCase)
```

The `MerchantRiskQueryService` executes a multi-hop Cypher traversal that returns the merchant's full risk subgraph — all connected merchants (up to 2 hops across IP and address relationships) and their linked fraud cases. This subgraph is formatted into structured text and injected into the LLM prompt for risk interpretation.

### Sample Fraud Network (Seeded for Testing)

```
                    CLEAN                           FRAUD RING
            +------------------+        +--------------------------------+
            | TechCorp         |        | QuickCash Exchange (FLAGGED)   |
            | Amazon EU        |        |   -> EUROPOL Op. Goldwash      |
            | Bistro Parisien  |        |   -> SHARES_IP -> GlobalDigital|
            | Iberia Airlines  |        |   -> SHARES_ADDR -> MedTrade   |
            +------------------+        +--------------------------------+
                                                      |
                                        +--------------------------------+
                                        | MedTrade Supplies              |
                                        |   -> SHARES_IP -> LuxeGoods   |
                                        +--------------------------------+
                                                      |
                                        +--------------------------------+
                                        | LuxeGoods Outlet (FLAGGED)     |
                                        |   -> Card-testing ring (4000+  |
                                        |      micro-transactions)       |
                                        |   -> Account takeover (EUR180K)|
                                        +--------------------------------+
```

---

## Validated Test Results

All tests executed against the live system with real LLM inference and Neo4j graph traversal.

### Test 1: Clean Merchant — TechCorp Solutions
**Scenario:** EUR 299 JetBrains IntelliJ subscription from a clean, isolated merchant.

| Field | Result |
|-------|--------|
| PII Masking | `S***** W*****`, `DE89**************3000`, `s***@***.de`, `172.***.***.55` |
| Classification | **Software** (confidence: 0.98) |
| Risk Level | **LOW** |
| Flagged | No |
| Reason | _"No graph-based risk signals; transaction characteristics consistent with legitimate software subscription"_ |

### Test 2: Directly Flagged Merchant — QuickCash Exchange
**Scenario:** EUR 4,850 crypto exchange (EUR to USDT) at a merchant flagged in EUROPOL Operation Goldwash.

| Field | Result |
|-------|--------|
| PII Masking | `D***** V*****`, `CY17********************7600`, `d***@***.me`, `45.***.***.100` |
| Classification | **Financial Services** (confidence: 0.85) |
| Risk Level | **CRITICAL** |
| Flagged | Yes |
| Reason | _"Merchant directly flagged as money laundering front in unresolved EUROPOL Op. Goldwash (2025) with EUR 2.3M seized; transaction involves high-risk jurisdiction (Cyprus) and crypto conversion consistent with layering activity."_ |

### Test 3: Shared-IP Shell Company — GlobalDigital Services
**Scenario:** EUR 12,000 IT consulting retainer from a merchant that shares an IP address with the flagged QuickCash Exchange. GlobalDigital itself is not flagged.

| Field | Result |
|-------|--------|
| PII Masking | `A***** M***`, `EE38************5685`, `a***@***.ee`, `45.***.***.100` |
| Classification | **Professional Services** (confidence: 0.95) |
| Risk Level | **HIGH** |
| Flagged | Yes |
| Reason | _"Merchant shares IP address with QuickCash Exchange, flagged as money laundering front in EUROPOL Op. Goldwash (EUR 2.3M seized). SHARES_IP connection to fraud-linked entity is a strong fraud signal indicating potential shell company or proxy relationship."_ |

### Test 4: 2-Hop Fraud Ring — MedTrade Supplies
**Scenario:** EUR 8,750 medical equipment order from a merchant connected to TWO flagged entities — shares physical address with QuickCash Exchange and shares IP with LuxeGoods Outlet.

| Field | Result |
|-------|--------|
| PII Masking | `C***** P***********`, `CY82********************7890`, `c***@***.cy`, `78.***.***.12` |
| Classification | **Healthcare** (confidence: 0.92) |
| Risk Level | **HIGH** |
| Flagged | Yes |
| Reason | _"Merchant shares IP address with LuxeGoods Outlet (flagged for chargebacks >15% and card-testing patterns) and physical address with QuickCash Exchange (flagged as money laundering front in EUROPOL Op. Goldwash 2025); strong indicators of fraud ring co-location."_ |

### Test 5: High-Value Transaction — Amazon EU
**Scenario:** EUR 15,000 AWS infrastructure annual commitment at a clean, well-known merchant.

| Field | Result |
|-------|--------|
| PII Masking | `K*********** H*******`, `DE62**************9312`, `k***@***.com`, `138.***.***.21` |
| Classification | **Software** (confidence: 0.95) |
| Risk Level | **LOW** |
| Flagged | No |
| Reason | _"Merchant has no graph-based risk signals; transaction characteristics consistent with legitimate enterprise AWS commitment."_ |

### Test 6: Micro-Transaction Card Testing — LuxeGoods Outlet
**Scenario:** EUR 0.99 purchase at a merchant with two prior fraud cases — a card-testing ring (4,000+ micro-transactions of EUR 0.01-1.00) and an account takeover case (EUR 180K).

| Field | Result |
|-------|--------|
| PII Masking | `M**** F********`, `ES66****************7891`, `m***@***.com`, `83.***.***.199` |
| Classification | **Retail** (confidence: 0.95) |
| Risk Level | **CRITICAL** |
| Flagged | Yes |
| Reason | _"Merchant directly flagged for chargebacks exceeding 15% and card-testing pattern; transaction amount (0.99 EUR) matches micro-transaction profile from resolved FRAUD-2025-0089 card-testing ring case (4000+ transactions EUR 0.01-1.00); unresolved HIGH-severity account takeover case (FRAUD-2026-0003, EUR 180K) linked to this merchant."_ |

### Test 7: Async Burst — 5 Concurrent Transactions via Kafka
**Scenario:** 5 transactions fired concurrently into Kafka's `raw-transactions` topic.

| Metric | Result |
|--------|--------|
| Transactions sent | 5 |
| All accepted (HTTP 202) | Yes |
| All arrived in `processed-transactions` topic | Yes (verified via `kafka-console-consumer`) |
| Consumer lag | 0 across all 3 partitions |

### Test 8: Circuit Breaker Health
| Metric | Result |
|--------|--------|
| State | CLOSED (healthy) |
| Buffered calls | 10 |
| Failed calls | 0 |
| Failure rate | 0.0% |
| Slow calls | 0 |
| Not-permitted calls | 0 |

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java JDK | 17+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker + Compose | Latest | `docker --version` |

## Quick Start

### 1. Start Infrastructure

```bash
docker compose up -d
docker exec finstream-ollama ollama pull nomic-embed-text
```

This starts five services:
- **Zookeeper** (port 2181) — Kafka coordination
- **Kafka** (port 9092) — message broker for the transaction pipeline
- **PostgreSQL + pgvector** (port 5433) — vector store for semantic search
- **Ollama** (port 11434) — embedding model for vectorization
- **Neo4j** (ports 7474/7687) — merchant fraud knowledge graph

### 2. Configure

Copy the example environment file and add your API key:

```bash
cp .env.example .env
# Edit .env and set ANTHROPIC_API_KEY
```

### 3. Seed the Fraud Graph (Optional)

To populate Neo4j with the sample merchant fraud network used in testing, open the Neo4j browser at [http://localhost:7474](http://localhost:7474) (credentials: `neo4j` / `finstream_password`) and run the Cypher statements from the [Knowledge Graph Schema](#sample-fraud-network-seeded-for-testing) section above, or execute them via `cypher-shell`.

### 4. Run

```bash
export $(cat .env | xargs) && ./mvnw spring-boot:run
```

### 5. Try the Pipeline

```bash
# Quick demo — generates and processes a sample transaction through all 3 agents
curl -X POST http://localhost:8081/api/transactions/demo
```

```bash
# Synchronous — send a transaction, get the fully processed result back
curl -X POST http://localhost:8081/api/transactions/process \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-001",
    "accountHolder": "Jane Smith",
    "accountNumber": "DE89370400440532013000",
    "email": "jane.smith@example.com",
    "merchantName": "TechCorp Solutions",
    "merchantId": "MERCHANT-TC-001",
    "merchantCategory": "5734",
    "amount": 149.99,
    "currency": "EUR",
    "country": "Germany",
    "ipAddress": "192.168.1.42",
    "description": "Annual software license renewal",
    "timestamp": "2026-03-29T10:00:00Z"
  }'
```

```bash
# Asynchronous — publishes to Kafka, returns immediately with HTTP 202
curl -X POST http://localhost:8081/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-002",
    "accountHolder": "Hans Mueller",
    "accountNumber": "DE44500105175407324931",
    "email": "hans@company.de",
    "merchantName": "CloudServices GmbH",
    "merchantId": "MERCHANT-CS-002",
    "merchantCategory": "7372",
    "amount": 2499.00,
    "currency": "EUR",
    "country": "Germany",
    "ipAddress": "10.0.0.55",
    "description": "Cloud infrastructure monthly billing",
    "timestamp": "2026-03-29T11:30:00Z"
  }'
```

### 6. Observability

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/circuitbreakers
```

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/transactions` | Async: publish to Kafka `raw-transactions`, returns HTTP 202 |
| POST | `/api/transactions/process` | Sync: process through all 3 agents, return enriched result |
| POST | `/api/transactions/demo` | Demo: generate and process a sample transaction |
| GET | `/actuator/health` | Application health check |
| GET | `/actuator/circuitbreakers` | Circuit breaker state for the anomaly detector |
| GET | `/actuator/metrics` | Application metrics |

### Request Schema: RawTransaction

```json
{
  "transactionId": "string (required)",
  "accountHolder": "string",
  "accountNumber": "string",
  "email": "string",
  "merchantName": "string",
  "merchantId": "string",
  "merchantCategory": "string (MCC code)",
  "amount": "number (>= 0, required)",
  "currency": "string",
  "country": "string",
  "ipAddress": "string",
  "description": "string",
  "timestamp": "ISO-8601 datetime"
}
```

### Response Schema: ProcessedTransaction

```json
{
  "transactionId": "string",
  "maskedAccountHolder": "string",
  "maskedAccountNumber": "string",
  "maskedEmail": "string",
  "merchantName": "string",
  "merchantId": "string",
  "amount": "number",
  "currency": "string",
  "country": "string",
  "maskedIpAddress": "string",
  "semanticCategory": "string",
  "classificationConfidence": "number (0.0-1.0)",
  "riskLevel": "LOW | MEDIUM | HIGH | CRITICAL",
  "riskReason": "string (evidence-based explanation)",
  "flaggedForReview": "boolean",
  "originalTimestamp": "ISO-8601 datetime",
  "processedTimestamp": "ISO-8601 datetime"
}
```

---

## Project Structure

```
finstream-ai/
├── docker-compose.yml                          # Zookeeper + Kafka + PostgreSQL + Neo4j + Ollama
├── pom.xml                                     # Spring Boot 3.5 + Spring AI + Kafka + Resilience4j
├── .env.example                                # Environment variable template
├── src/main/java/com/finstream/
│   ├── FinStreamAiApplication.java             # Application entry point
│   ├── api/
│   │   └── TransactionController.java          # REST endpoints (async + sync + demo)
│   ├── domain/
│   │   ├── RawTransaction.java                 # Input record — raw transaction with PII
│   │   ├── ProcessedTransaction.java           # Output record — masked, classified, risk-assessed
│   │   ├── MerchantNode.java                   # Neo4j @Node — merchant with fraud relationships
│   │   └── FraudCaseNode.java                  # Neo4j @Node — historical fraud cases
│   ├── infrastructure/
│   │   ├── KafkaConfig.java                    # Topics, producer/consumer factories, listener container
│   │   ├── VectorStoreConfig.java              # ChatClient bean for pipeline agents
│   │   ├── Neo4jConfig.java                    # Transaction manager for JPA + Neo4j coexistence
│   │   ├── GraphRepository.java                # Spring Data Neo4j repository for merchant CRUD
│   │   └── MerchantRiskQueryService.java       # Multi-hop Cypher subgraph query via Neo4jClient
│   ├── agents/
│   │   ├── SanitizationAgent.java              # Agent 1: PII masking (regex, no LLM)
│   │   ├── SemanticClassifierAgent.java        # Agent 2: Transaction classification (pgvector RAG + LLM)
│   │   └── GraphAnomalyAgent.java              # Agent 3: Fraud detection (Neo4j GraphRAG + LLM + circuit breaker)
│   └── service/
│       └── PipelineOrchestrator.java           # Kafka consumer — chains all 3 agents sequentially
└── src/main/resources/
    └── application.yaml                        # Kafka, Neo4j, pgvector, LLM, Resilience4j configuration
```

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Pipeline transport | Apache Kafka | Durable, partitioned, handles backpressure; enables replay and dead-letter routing |
| Agent execution | Sequential chain | Each agent depends on the previous stage — sanitization must precede classification |
| PII masking approach | Pure regex | Deterministic, sub-millisecond, zero API cost — LLM would add latency with no benefit |
| Transaction classification | pgvector RAG + LLM | Embedding-based similarity captures semantic context beyond raw MCC codes |
| Fraud detection | Neo4j subgraph + LLM | Multi-hop graph traversal reveals hidden entity relationships that flat data cannot |
| Subgraph query execution | Neo4jClient (not repository) | Spring Data Neo4j repositories cannot auto-map multi-column Cypher projections |
| Circuit breaker | Resilience4j on Agent 3 | Neo4j or LLM outage must not crash the pipeline — graceful degradation to manual review |
| Fallback behavior | Flag for manual review | Conservative: never auto-clear a transaction when risk assessment infrastructure is degraded |
| API modes | Both sync and async | Async via Kafka for production throughput; sync for testing and integrations needing immediate results |
| Domain model | Java Records | Immutable, compact, self-documenting value objects with compile-time validation |
| Embedding strategy | Local Ollama container | 768-dim nomic-embed-text runs in Docker — no external API dependency for vectorization |
| JPA + Neo4j coexistence | Explicit transaction manager | Required when both Spring Data JPA and Spring Data Neo4j share the same application context |

---

## Future Work

- [ ] Seed Neo4j fraud graph via `ApplicationRunner` on startup (idempotent MERGE)
- [ ] Kafka consumer for `processed-transactions` writing to a PostgreSQL audit table
- [ ] Testcontainers integration tests for the full pipeline
- [ ] Dead-letter topic for failed transactions with retry scheduling
- [ ] Prometheus + Grafana dashboards for pipeline throughput, latency percentiles, and error rates
- [ ] WebSocket endpoint for real-time pipeline status streaming
- [ ] Rate limiting and API key authentication on ingestion endpoints
- [ ] Batch ingestion endpoint for CSV/JSON file uploads

## License

MIT
