# com-etzhayyim-tashikame

tashikame (確かめ) — fact-check verdict publisher. See `README.md` for the core
contract and full-repo `../../../CLAUDE.md` "Actors" section for the pattern
this follows (containment + independent governor + append-only ledger).
Superproject decision record:
`../../../90-docs/adr/2607022200-com-etzhayyim-tashikame-factcheck-aozora-actor-r0.md`.
Design 正本: `docs/adr/0001-architecture.md`.

## Invariant

tashikame NEVER publishes a verdict the FactGovernor rejects. Every published
verdict is citation-grounded (a conclusive rating needs ≥1 reachable source).
Catastrophe-veto (Rider §2) / person-targeting / uncited-conclusive /
no-actuation proposals are HELD — recorded as a hold in the append-only ledger,
never published. Only `:commit` writes the Store + publishes; every
commit/hold is an immutable ledger fact. Publication is AUTONOMOUS by default
(ADR-2606281500, 種をまく) — there is no per-post operator/Council prior
restraint; the off-switch is the revocable member CACAO leash, not a per-post
approval. Low-confidence verdicts still publish, tagged `:low-confidence`
(transparency, not a block).

## Conventions

- `.cljc` for anything portable (operation/governor/advisor/publisher/phase/
  store/sim) — `.clj` only for JVM-only I/O (cacao, aozora).
- `tashikame.cacao` is a faithful port of `tsumugu.cacao` (self-sovereign
  Ed25519 identity + CACAO mint); `tashikame.aozora` is the real app-aozora
  Publisher over com.atproto.repo.createRecord.
- The actor's own Ed25519 identity lives in `.tashikame/identity.edn`
  (gitignored) — NEVER commit a private key.
- `clojure -M:lint` (clj-kondo, errors fail) / `clojure -M:dev:test`.
