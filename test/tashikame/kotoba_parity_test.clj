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

  A fragility this used to pin, now FIXED (see also the ADR/README):
  `publish-allowed` used to return `(defaults/default-phase)` — i.e. the default
  PHASE NUMBER — in the slot where the answer is a publish FLAG. That agreed
  with the cljc only because the default is 1 and phase 1 is publish-allowed;
  moving `default-phase` to 0 made the `.kotoba` answer \"do not publish\" for
  every non-zero phase while the cljc still answered \"publish\" (9 of the 10
  probes below). `publish-allowed` now derives the flag from `phase` alone —
  0 for phase 0, 1 otherwise — and no longer requires `phase-defaults` at all.
  `publish-allowed-is-independent-of-default-phase` below is what keeps the two
  quantities apart: it recompiles the project with a substituted
  `phase_defaults` source and asserts the answers do not move. Note the one
  place the two are still genuinely related — the cljc routes an out-of-range
  phase through `phases[default-phase]`, and the `.kotoba` resolves that
  fallback at author time against the current default of 1; if the CLJC
  constant ever moves, `publish-gate-kotoba-agrees-with-phase-cljc` fails on
  the out-of-range probes, which is the correct place to notice it.

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

(def ^:private defaults-ns 'kotoba.etzhayyim.tashikame.phase-defaults)

(defn- defaults-source
  "A `phase_defaults` module whose `default-phase` is `d`, shaped exactly like
  the one on disk so the substitution differs in precisely the one number under
  test. Built as text rather than by editing the file, because
  `compile-project` takes a closed namespace-symbol -> source-text map: the
  substitution is total and leaves the working tree alone."
  [d]
  (str "(ns kotoba.etzhayyim.tashikame.phase-defaults\n"
       "  (:export [default-phase]))\n\n"
       "(defn default-phase [] " d ")\n"))

(defn- publish-answers
  "`publish-allowed` over `probe-phases`, compiled through the SAME project map
  the parity test uses, with only `phase_defaults` substituted. Going through
  the project map is the point: if `publish-allowed` ever reads
  `default-phase` again, these answers move."
  [d]
  (let [ir (:kir (compiler/compile-project
                  (assoc sources defaults-ns (defaults-source d))
                  (:kotoba.project/root project)
                  target))]
    (mapv #(kir/execute ir 'publish-allowed [%]) probe-phases)))

(deftest publish-allowed-is-independent-of-default-phase
  (testing "`publish-allowed` answers a publish FLAG derived from the phase, so
            moving the default PHASE NUMBER must not move a single answer"
    (let [expected (mapv (comp host-flag->i64 phase/publish-allowed?)
                         probe-phases)]
      (is (= expected (publish-answers 1))
          "baseline: substituting the default it already has must reproduce the
           cljc — otherwise the substitution machinery itself is wrong")
      (doseq [d [0 2 -1 9223372036854775807 -9223372036854775808]]
        (is (= d (kir/execute
                  (:kir (compiler/compile-source (defaults-source d) target))
                  'default-phase []))
            (str "the substituted phase_defaults did not compile to " d
                 " — the independence assertion below would be vacuous"))
        (is (= expected (publish-answers d))
            (str "default-phase " d " changed publish-allowed's answers: the "
                 "publish FLAG has been re-entangled with the default PHASE "
                 "NUMBER"))))))

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
