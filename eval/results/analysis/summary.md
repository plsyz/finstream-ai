# FinStream evaluation summary

Files: isolated_raw (400 records, 0 errors), isolated_sanitized (400 records, 0 errors), pipeline_raw_r1 (400 records, 0 errors), pipeline_raw_r2 (400 records, 0 errors), pipeline_raw_r3 (400 records, 0 errors), pipeline_sanitized_r1 (400 records, 0 errors), pipeline_sanitized_r2 (400 records, 0 errors), pipeline_sanitized_r3 (400 records, 0 errors)

## 1. Subtask capability (each agent in isolation)

| mode | condition | n | structured masked | prompt-level leak | classify acc | flagged correct | risk in range | risk correct | predicted composite |
|---|---|---|---|---|---|---|---|---|---|
| raw | ALL | 400 | 1.000 | 1.000 | 0.995 | 0.875 | 0.772 | 0.772 | 0.767 |
| raw | clean | 200 | 1.000 | 1.000 | 0.995 | 0.920 | 0.800 | 0.800 | 0.795 |
| raw | pii_in_text | 80 | 1.000 | 1.000 | 1.000 | 0.787 | 0.750 | 0.750 | 0.750 |
| raw | obfuscated_pii | 40 | 1.000 | 1.000 | 1.000 | 0.825 | 0.725 | 0.725 | 0.725 |
| raw | ood_merchant | 40 | 1.000 | 1.000 | 0.975 | 0.900 | 0.900 | 0.900 | 0.875 |
| raw | prompt_injection | 40 | 1.000 | 1.000 | 1.000 | 0.850 | 0.600 | 0.600 | 0.600 |
| sanitized | ALL | 400 | 1.000 | 0.000 | 0.995 | 0.892 | 0.797 | 0.797 | 0.792 |
| sanitized | clean | 200 | 1.000 | 0.000 | 0.995 | 0.920 | 0.805 | 0.805 | 0.800 |
| sanitized | pii_in_text | 80 | 1.000 | 0.000 | 0.988 | 0.850 | 0.825 | 0.825 | 0.812 |
| sanitized | obfuscated_pii | 40 | 1.000 | 0.000 | 1.000 | 0.825 | 0.750 | 0.750 | 0.750 |
| sanitized | ood_merchant | 40 | 1.000 | 0.000 | 1.000 | 0.950 | 0.950 | 0.950 | 0.950 |
| sanitized | prompt_injection | 40 | 1.000 | 0.000 | 1.000 | 0.850 | 0.600 | 0.600 | 0.600 |

## 2. Composite behaviour and capability-conditioned gap

predicted = conjunction of isolated results for the same transaction; actual = end-to-end pipeline;
gap = predicted - actual (positive: capabilities demonstrated in isolation are not applied end-to-end).

| mode | condition | n | predicted composite | actual composite | gap | classify acc (pipe) | risk correct (pipe) | output leak (pipe) |
|---|---|---|---|---|---|---|---|---|
| raw | ALL | 400 | 0.767 | 0.777 | -0.010 | 0.998 | 0.780 | 0.010 |
| raw | clean | 200 | 0.795 | 0.790 | +0.005 | 0.995 | 0.795 | 0.000 |
| raw | pii_in_text | 80 | 0.750 | 0.762 | -0.012 | 1.000 | 0.762 | 0.050 |
| raw | obfuscated_pii | 40 | 0.725 | 0.750 | -0.025 | 1.000 | 0.750 | 0.000 |
| raw | ood_merchant | 40 | 0.875 | 0.950 | -0.075 | 1.000 | 0.950 | 0.000 |
| raw | prompt_injection | 40 | 0.600 | 0.600 | +0.000 | 1.000 | 0.600 | 0.000 |
| sanitized | ALL | 400 | 0.792 | 0.790 | +0.002 | 0.998 | 0.792 | 0.000 |
| sanitized | clean | 200 | 0.800 | 0.800 | +0.000 | 1.000 | 0.800 | 0.000 |
| sanitized | pii_in_text | 80 | 0.812 | 0.812 | +0.000 | 0.988 | 0.825 | 0.000 |
| sanitized | obfuscated_pii | 40 | 0.750 | 0.750 | +0.000 | 1.000 | 0.750 | 0.000 |
| sanitized | ood_merchant | 40 | 0.950 | 0.925 | +0.025 | 1.000 | 0.925 | 0.000 |
| sanitized | prompt_injection | 40 | 0.600 | 0.600 | +0.000 | 1.000 | 0.600 | 0.000 |

## 3. Attribution of composite failures

For each end-to-end failure: which component failed, and whether that component succeeded on the same transaction in isolation.

| mode | component | failures | capability absent (also failed isolated) | capability present, not applied (passed isolated) |
|---|---|---|---|---|
| raw | classifier | 1 | 1 | 0 |
| raw | risk agent | 88 | 87 | 1 |
| raw | PII leak in output | 4 | 2 | 2 |
| sanitized | classifier | 1 | 1 | 0 |
| sanitized | risk agent | 83 | 81 | 2 |
| sanitized | PII leak in output | 0 | 0 | 0 |

## 4. PII leakage

Prompt-level: raw PII present in the record handed to the LLM agents. Output-level: raw PII present in the processed transaction.

| stage | mode | condition | n | prompt-level leak | output-level leak |
|---|---|---|---|---|---|
| isolated | raw | ALL | 400 | 1.000 | 0.007 |
| isolated | raw | clean | 200 | 1.000 | 0.000 |
| isolated | raw | pii_in_text | 80 | 1.000 | 0.037 |
| isolated | raw | obfuscated_pii | 40 | 1.000 | 0.000 |
| isolated | raw | ood_merchant | 40 | 1.000 | 0.000 |
| isolated | raw | prompt_injection | 40 | 1.000 | 0.000 |
| isolated | sanitized | ALL | 400 | 0.000 | 0.000 |
| isolated | sanitized | clean | 200 | 0.000 | 0.000 |
| isolated | sanitized | pii_in_text | 80 | 0.000 | 0.000 |
| isolated | sanitized | obfuscated_pii | 40 | 0.000 | 0.000 |
| isolated | sanitized | ood_merchant | 40 | 0.000 | 0.000 |
| isolated | sanitized | prompt_injection | 40 | 0.000 | 0.000 |
| pipeline | raw | ALL | 400 | 1.000 | 0.010 |
| pipeline | raw | clean | 200 | 1.000 | 0.000 |
| pipeline | raw | pii_in_text | 80 | 1.000 | 0.050 |
| pipeline | raw | obfuscated_pii | 40 | 1.000 | 0.000 |
| pipeline | raw | ood_merchant | 40 | 1.000 | 0.000 |
| pipeline | raw | prompt_injection | 40 | 1.000 | 0.000 |
| pipeline | sanitized | ALL | 400 | 0.000 | 0.000 |
| pipeline | sanitized | clean | 200 | 0.000 | 0.000 |
| pipeline | sanitized | pii_in_text | 80 | 0.000 | 0.000 |
| pipeline | sanitized | obfuscated_pii | 40 | 0.000 | 0.000 |
| pipeline | sanitized | ood_merchant | 40 | 0.000 | 0.000 |
| pipeline | sanitized | prompt_injection | 40 | 0.000 | 0.000 |

### Leaked PII by type (pipeline, output-level)

| mode | type | leaked items |
|---|---|---|
| raw | email | 1 |
| raw | iban | 3 |
| raw | name | 1 |

### Leaked PII by type (isolated, prompt-level)

| mode | type | leaked items |
|---|---|---|
| raw | email | 409 |
| raw | iban | 411 |
| raw | ip | 400 |
| raw | name | 420 |
| raw | phone | 16 |

## 5. Prompt injection (ring merchants only)

Miss rate = fraction of ring-merchant transactions NOT flagged. Flip rate = miss(injection) - miss(clean) on the same merchant population.

| stage | mode | miss rate clean (ring) | miss rate injection | flip rate | classify acc clean (ring) | classify acc injection |
|---|---|---|---|---|---|---|
| isolated | raw | 0.151 | 0.150 | -0.001 | 1.000 | 1.000 |
| isolated | sanitized | 0.151 | 0.150 | -0.001 | 1.000 | 1.000 |
| pipeline | raw | 0.151 | 0.150 | -0.001 | 1.000 | 1.000 |
| pipeline | sanitized | 0.151 | 0.150 | -0.001 | 1.000 | 1.000 |

## 6. Risk level by graph hop distance (isolated risk agent, clean condition)

The Cypher traversal uses *1..2 for both relationship types and the context formatter does not record hop distance,
so 1-hop and 2-hop neighbours look identical to the LLM.

### mode=raw

| hops        |   CRITICAL |   HIGH |   LOW |   MEDIUM |
|:------------|-----------:|-------:|------:|---------:|
| 0 (flagged) |          1 |   0    | 0     |    0     |
| 1           |          0 |   0.51 | 0     |    0.49  |
| 2           |          0 |   0.44 | 0.56  |    0     |
| none        |          0 |   0    | 0.981 |    0.019 |

### mode=sanitized

| hops        |   CRITICAL |   HIGH |   LOW |   MEDIUM |
|:------------|-----------:|-------:|------:|---------:|
| 0 (flagged) |          1 |  0     | 0     |    0     |
| 1           |          0 |  0.531 | 0     |    0.469 |
| 2           |          0 |  0.44  | 0.56  |    0     |
| none        |          0 |  0     | 0.981 |    0.019 |

## 7. Run-to-run consistency (pipeline repeats)

| mode      |   repeats |   n |   category_stable |   risk_level_stable |   flag_stable |   composite_stable |
|:----------|----------:|----:|------------------:|--------------------:|--------------:|-------------------:|
| raw       |         3 | 400 |             0.998 |               0.975 |         0.982 |              0.972 |
| sanitized |         3 | 400 |             0.998 |               0.99  |         0.992 |              0.988 |
