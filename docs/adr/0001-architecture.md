# ADR-0001: tashikame (確かめ) — fact-check verdict publisher architecture

**Status**: R0 scaffold (2026-07-02)
**Deciders**: Jun Kawasaki
**Superproject ADR**: `90-docs/adr/2607022200-com-etzhayyim-tashikame-factcheck-aozora-actor-r0.md`

## Context

The etzhayyim organism had no single actor that **fact-checks a claim and
publishes the verdict**. Adjacent actors exist but none closes the loop:

- **kawaraban** — news MEDIUM, mirrors news, but by charter (`G1
  mirror-not-adjudicator`) cannot render a truth verdict.
- **danjo** — public-accountability oversight; detects discrepancies in
  government data, but does not adjudicate or publish verdicts to a feed.
- **ake** — community-edit membrane for KG/profile correction; not claim
  verdicts, and its live publish is Council-gated.
- **yomi** — news-intelligence; publishes intel assessments to its OWN ledger,
  not to app-aozora.

Owner request (2026-07-02): design + integrate an actor that **fact-checks
social posts / news and posts the verdict to app-aozora**.

## Decision

tashikame — the workspace-actor pattern's fact-check instance (same shape as
robotaxi-actor / gftd-talent-actor / ai-gftd-itonami / com-etzhayyim-kyoninka /
sng): containment + independent governor + append-only ledger.

1. **Containment + independent governor.** `factllm` (the intelligence node) is
   sealed into one graph node (`:advise`) and returns a *proposal only*
   (`:verdicts` + citations + confidence, `:effect :assessment`). An
   independent **FactGovernor** censors it; only `:commit` writes the Store +
   publishes. Invariant: *tashikame never publishes a verdict the FactGovernor
   rejects.*

2. **langgraph-clj StateGraph, 1 run = 1 claim check.** No unbounded inner
   loop. No `interrupt-before` — see (4).

3. **Autonomous publication (ADR-2606281500, 種をまく).** Publication is the
   actor's own SPEECH, autonomous by default — **no per-post operator/Council
   prior restraint**. The off-switch is the revocable member CACAO leash
   (`:leash` on the createRecord), not a per-post approval. PUBLICATION ≠
   ACTUATION: tashikame only ever `:effect :assessment`; it never moves funds,
   grants access, or actuates. The FactGovernor's HARD violations are the only
   thing that withholds publication.

4. **FactGovernor gates.**
   - HARD → HOLD (recorded, never published): `:no-actuation`,
     `:uncited-conclusive`, `:malformed-citation`, `:catastrophe-veto`
     (Rider §2 scan), `:person-targeting` (doxing a private person, not
     public-figure claim-checking).
   - SOFT → publish with tag: `:low-confidence` (transparency, not a block).

5. **Injected seams.** Store (`MemStore` ‖ `DatomicStore` ‖ kotoba-server) /
   Advisor (`mock` ‖ real `langchain.model` on Murakumo, with a read-only web
   gather step — autonomously allowed per ADR-2606072802) / Publisher
   (`MockPublisher` ‖ real app-aozora createRecord) / Phase (0 observe → 1
   autonomous-publish). Core is invariant across all swaps.

6. **Store is `:db-api` driven.** Talks to its backend only through the
   langchain.db `{:q :transact! :db :pull :entid}` map. `langchain.db/api`
   (in-process EAVT) and `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC)
   both implement it → the same `DatomicStore` runs on either. A
   `MemStore ≡ DatomicStore` contract test guards this.

7. **Self-sovereign identity.** `tashikame.cacao` (ported from `tsumugu.cacao`)
   generates + persists the actor's own Ed25519 key; the publish mints a
   depth-1 CACAO (`tashikame.aozora`). Private key in `.tashikame/identity.edn`
   (gitignored) — never committed.

8. **`.cljc` portable.** Core (operation/governor/advisor/publisher/phase/
   store/sim) is `.cljc` (JVM/SCI/cljs/WASM); `.clj` only for JVM-only I/O
   (cacao, aozora).

## Consequences

- (+) A claim can be fact-checked and the verdict published without per-post
  human gating, while every published verdict is governor-clean,
  citation-grounded, and append-only audited.
- (+) `factllm` is upgrade/swap-able (mock → Murakumo LLM) without touching the
  publication guarantee (governor + ledger).
- (−) R0 `mock-advisor` is heuristic; real assessment needs the LLM + read-only
  web gather wired at deploy. R0 governor is a structural cite/catastrophe
  check; the catastrophe-veto denylist is illustrative until the canonical
  `etzhayyim_organism.sensors.charter_rider.scan` is wired.
- (−) `:person-targeting` is an R0 heuristic; production needs a richer NLP pass
  to distinguish public-figure claim-checking from private-person doxing.

## Alternatives considered

- **Council-gate every verdict** (ake G8 style) — rejected: contradicts
  ADR-2606281500 autonomous publication for SPEECH; the leash is the off-switch.
- **Publish via kotoba-server store only** (tsumugu style) — viable, but owner
  asked specifically for an app-aozora feed post; the injected Publisher lets
  either surface be wired without a core change.
- **A single combined news/fact actor with kouhou** — rejected: keep
  fact-checking (adjudicative speech) and public-info curation (faithful
  summarization) as separate actors with separate governors, per the
  one-actor-one-role charter.
