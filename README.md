# com-etzhayyim-tashikame (確かめ)

**tashikame** — a fact-check verdict publisher. It takes a claim (from the
kawaraban news mirror or an ATProto mention), assesses it against primary
sources, and **publishes a citation-grounded verdict to app-aozora**
(collection `com.etzhayyim.apps.tashikame.factCheck`).

**DID**: `did:web:etzhayyim.github.io:com-etzhayyim-tashikame` (depth-1
self-minted `did:key` carried in the published record; RAD identity journal at
`orgs/etzhayyim/root/80-data/kotoba-rad/tashikame.identity.journal.edn`).
**Namespace**: `com.etzhayyim.apps.tashikame.*`.
**ADR**: ADR-2607022200 (superproject, R0 scaffold) + `docs/adr/0001-architecture.md` (正本).
**Status**: R0 scaffold — `mock-advisor` + `mock-publisher`; real LLM
(`langchain.model` on Murakumo) + real aozora Publisher wired at deploy.
**First-touch channel**: app-aozora (`com.atproto.repo.createRecord`).
**Cross-actor**: ingests claims from **kawaraban** (news mirror) and ATProto
mentions; published verdicts are candidate inputs to **danjo** (discrepancy)
and sit alongside **yomi** (intel assessments, own ledger).

## Overview

tashikame is the etzhayyim organism's fact-checker. Per the autonomous-
publication doctrine (ADR-2606281500, 種をまく / seed-and-grow), it publishes
verdicts **autonomously by default** — there is no per-post operator/Council
prior restraint. The safety rails are the actor's OWN seed: a self-`did:key`
(present-only) + a revocable member CACAO leash (the off-switch) + a Rider §2
catastrophe-veto scan before every emit + a no-person-targeting rule.

This is **containment + independent governor + append-only ledger**: the
intelligence node (`factllm`) is sealed into a single graph node and returns a
*proposal only*; an independent **FactGovernor** censors it; only `:commit`
writes the Store + publishes. Publication is SPEECH, not ACTUATION — tashikame
assesses, it never moves funds, grants access, or actuates anything.

## StateGraph (one claim = one run)

```
intake → advise(factllm) → govern(FactGovernor) → decide → commit | hold
```

No `interrupt-before` (autonomous). The FactGovernor's HARD violations are the
only thing that withholds publication.

| node | role |
|---|---|
| `:advise` | `factllm` (contained) — extracts claims, gathers sources (read-only), returns verdicts + citations. Proposal only. |
| `:govern` | `FactGovernor` — independent censor (separate system). |
| `:commit` | writes verdict to Store + append-only ledger; publishes to app-aozora when phase allows. |
| `:hold`   | records the rejection as a hold; no SSoT mutation, no publish. |

## FactGovernor gates

**HARD → HOLD (never publish):**
- `:no-actuation` — proposal `:effect ≠ :assessment`.
- `:uncited-conclusive` — a `:supported`/`:refuted`/`:misleading` verdict with 0 citations.
- `:malformed-citation` — a cite that is not an absolute `http(s)` URL.
- `:catastrophe-veto` — Rider §2 catastrophe-veto scan hit on verdict text.
- `:person-targeting` — doxing/harassing a private person (not public-figure claim-checking).

**SOFT → publish with a transparency tag (not a block):**
- `:low-confidence` — overall confidence below floor; the verdict still publishes, tagged.

## Phase rollout

| Phase | label | publish? |
|---|---|---|
| 0 | observe | no — governor-clean verdicts recorded only (shadow) |
| 1 | autonomous-publish (**default**, 種をまく) | yes |

There is deliberately NO "every publish needs approval" phase — that would be
per-post prior restraint, which ADR-2606281500 lifts.

The bounded numeric publish gate is also implemented as a closed, two-module
Kotoba project in `kotoba/` and verified with the released native CLI. The
existing `phase.cljc` remains the CLJ/CLJS host API because its labels, keyword
maps, and booleans are not yet representable by Kotoba's safety-first value
model. This is an intentional boundary, not an extension-only rename.

## Injected seams (each a swap, core unchanged)

- **Store** — `MemStore` ‖ `DatomicStore` (langchain.db `:db-api`) ‖ kotoba-server pod.
- **Advisor** — `mock-advisor` (deterministic) ‖ real LLM on `langchain.model` / Murakumo.
- **Publisher** — `MockPublisher` ‖ real app-aozora createRecord (`tashikame.aozora`).
- **Phase** — 0 observe → 1 autonomous-publish.

## Run

```bash
clojure -M:lint          # clj-kondo, errors fail
clojure -M:dev:test      # cognitect test-runner (canonical)
clojure -M:dev:run       # offline demo (two sample claims, mock publisher)
# Native Kotoba: kotoba check --project kotoba-project.edn --target web
```

## Related files

- `docs/adr/0001-architecture.md` — design 正本.
- `../../../90-docs/adr/2607022200-com-etzhayyim-tashikame-factcheck-aozora-actor-r0.md` — superproject ADR.
- `CLAUDE.md` — repo invariants / conventions.
