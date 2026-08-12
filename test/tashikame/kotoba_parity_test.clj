(ns tashikame.kotoba-parity-test
  "Binds the `kotoba/` modules to their CLJ/CLJS twins in `tashikame.phase`.

  Why this file exists
  ====================
  `kotoba/publish_gate.kotoba` and `kotoba/phase_defaults.kotoba` restate two
  things that `src/tashikame/phase.cljc` already decides — `publish-allowed?`
  and `default-phase`. The README calls that an intentional boundary, but until
  this file nothing in the repo executed the `.kotoba` side at all: the only
  consumer was `scripts/test-kotoba-project.mjs`, which imports a compiled JS
  artifact that is not in the tree, produced by a native CLI invoked from
  `.github/workflows/kotoba.yml` — and GitHub Actions is disabled for this repo
  (ADR-2607300900; the workflow file is inert). So the duplicate could drift
  with nobody notified. These tests compile the real `.kotoba` sources in
  process and run both sides over the same inputs.

  The dialect mismatch is explicit, not glossed
  =============================================
  The `.kotoba` here is the untyped legacy dialect: `publish-allowed` takes and
  returns i64, so its `publish?` answer is the integer 0 or 1, never a boolean,
  and `default-phase` is a bare i64. `tashikame.phase` answers with a real
  boolean. Parity therefore requires an explicit projection — `host-flag->i64`
  below — and that projection is part of what is being asserted. Do not delete
  it and compare raw values; the two sides do not have the same value model.

  A known fragility this pins (see also the ADR/README):
  `publish-allowed` returns `(defaults/default-phase)` — i.e. the default PHASE
  NUMBER — in the slot where the answer is a publish FLAG. Today that is 1 and
  phase 1 is publish-allowed, so the two sides agree. Change `default-phase` to
  0 and the `.kotoba` starts answering \"do not publish\" for phase 1 while the
  cljc still answers \"publish\". These tests are what makes that visible.

  Namespaces
  ==========
  The interpreter namespace tracks the compiler pin: newer pins (this one)
  moved KIR execution out to `io.github.kotoba-lang/kotoba-kir`, so it is
  `kotoba.kir`; older pins carried it in-tree as `kotoba.compiler.ir`.

  `publish_gate.kotoba` `(:require ...)`s `phase_defaults`, and
  `compile-source` rejects a `:require` clause outright
  (`:kotoba.error/namespace-export-clause`) — a single source text is a single
  module by definition. Multi-module admission is `compile-project`, which
  takes a closed namespace-symbol -> source-text map and a root, with no
  ambient lookup. `phase_defaults` has no requires, so it compiles either way;
  it is read through the project map here so both tests see the same sources."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as kir]
            [tashikame.phase :as phase]))

;; The web/JS profile — the one `.github/workflows/kotoba.yml` compiles with
;; (`--target web`), so this gate admits the same source under the same profile
;; the (inert) release path uses.
(def ^:private target :js-kotoba-v1)

(def ^:private project
  (edn/read-string (slurp "kotoba-project.edn")))

(def ^:private sources
  "Namespace symbol -> source text, read through kotoba-project.edn rather than
  hardcoded, so renaming or adding a module cannot silently drop it from this
  gate."
  (into {} (map (fn [[nspace path]] [nspace (slurp path)]))
        (:kotoba.project/modules project)))

(defn- host-flag->i64
  "The projection between the two value models. `tashikame.phase` answers with
  a boolean; the legacy `.kotoba` dialect has no boolean type and answers 0/1."
  [flag]
  (if flag 1 0))

;; Phase 0 and 1 are the two defined phases; the rest exercise the
;; out-of-range fallback, which both sides are supposed to route to the
;; default phase. -1 and the large magnitudes are there because i64 wrapping
;; and Clojure long arithmetic are the two value models being compared.
(def ^:private probe-phases
  [0 1 2 3 -1 -2 7 42 9223372036854775807 -9223372036854775808])

(deftest publish-gate-kotoba-agrees-with-phase-cljc
  (testing "the closed two-module project compiles and exports publish-allowed"
    (let [compiled (compiler/compile-project
                    sources
                    (:kotoba.project/root project)
                    target)
          ir (:kir compiled)]
      (is (= ['publish-allowed] (vec (:exports ir))))
      (testing "and answers identically to tashikame.phase/publish-allowed?"
        (doseq [p probe-phases]
          (is (= (host-flag->i64 (phase/publish-allowed? p))
                 (kir/execute ir 'publish-allowed [p]))
              (str "phase " p ": .kotoba publish-allowed and .cljc "
                   "publish-allowed? disagree")))))))

(deftest phase-defaults-kotoba-agrees-with-phase-cljc
  (testing "default-phase is the same number on both sides"
    (let [ir (:kir (compiler/compile-source
                    (get sources 'kotoba.etzhayyim.tashikame.phase-defaults)
                    target))]
      (is (= ['default-phase] (vec (:exports ir))))
      (is (= phase/default-phase (kir/execute ir 'default-phase []))))))

(deftest negative-eval-fixture-is-actually-rejected
  (testing "kotoba/negative_eval.kotoba is a NEGATIVE fixture — it exists to be
            refused, and is deliberately absent from kotoba-project.edn"
    (is (not (contains? (:kotoba.project/modules project)
                        'kotoba.etzhayyim.tashikame.negative-eval))
        "negative_eval must stay out of the compilable project")
    (let [thrown (try
                   (compiler/compile-source
                    (slurp "kotoba/negative_eval.kotoba") target)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "the compiler ACCEPTED a source containing `eval` — the safety
           property this fixture is supposed to demonstrate is gone")
      (is (= :kotoba.error/ambient-forbidden
             (:kotoba.error/code (ex-data thrown)))
          "rejected, but not for the reason the fixture is about (metaprogramming)"))))
