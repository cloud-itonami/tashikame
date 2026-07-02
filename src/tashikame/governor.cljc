(ns tashikame.governor
  "FactGovernor — the independent censor that earns a factllm proposal the
  right to publish. SEPARATE system from factllm (defense in depth: never
  trust the generator to have been the only gate). Mirrors tsumugu.policy's
  shape (HARD → HOLD, no override; SOFT → recorded warning, still publishes).

  Doctrine: publication is AUTONOMOUS by default (ADR-2606281500, 種をまく /
  seed-and-grow). The FactGovernor is NOT an external operator/Council prior
  restraint — it is tashikame's OWN seed rail (the off-switch is the revocable
  member CACAO leash, not a per-post approval). So:

    HARD violations → HOLD (the verdict is recorded as a hold, NEVER published)
    SOFT (low conf) → publish, with a :low-confidence transparency tag

  HARD (never publish):
    :no-actuation        proposal :effect ≠ :assessment (tashikame only assesses)
    :uncited-conclusive  a verdict rated :supported/:refuted/:misleading with 0 cites
    :malformed-citation  a cite that is not an absolute http(s) URL
    :catastrophe-veto    Rider §2 catastrophe-veto scan hits on verdict text
    :person-targeting    verdict doxes/harasses a private person (not public-figure
                         claim-checking)
  SOFT (publish with tag):
    :low-confidence      overall confidence < floor"
  (:require [clojure.string :as str]))

(def confidence-floor 0.4)

(def conclusive-ratings #{:supported :refuted :misleading})

(def ^:private catastrophe-denylist
  "Rider §2 catastrophe-veto surface — illustrative denylist for R0. Production
  wires `etzhayyim_organism.sensors.charter_rider.scan` (the canonical §2
  scanner). The markers below drive the contract test."
  ["<CAT>" "<CSAM>" "<FORCE>" "<SURVEIL>"])

(defn- valid-cite? [s] (and (string? s) (re-find #"^https?://" s)))

(defn- verdict-blob [verdicts ks]
  (->> verdicts (mapcat #(map % ks)) (filter string?) (str/join " ")))

(defn- catastrophe? [verdicts]
  (let [blob (verdict-blob verdicts [:claim :note])]
    (some #(str/includes? blob %) catastrophe-denylist)))

(defn- person-targeting? [verdicts]
  ;; R0 heuristic: a verdict note that explicitly targets a named private
  ;; person with harassment. Public-figure claim-checking (rating a public
  ;; claim) is NOT targeting. The marker drives the test; production uses a
  ;; richer NLP pass.
  (str/includes? (verdict-blob verdicts [:note]) "<DOXING>"))

(defn check
  "Censors a factllm proposal. Returns {:ok? :violations [hard] :warnings [soft]
  :confidence c}. :ok? is true iff there are no HARD violations."
  [_request _context proposal]
  (let [effect   (:effect proposal)
        verdicts (:verdicts proposal)
        conf     (:confidence proposal 0.0)
        hard (cond-> []
               (not= :assessment effect)
               (conj {:rule :no-actuation
                      :detail "tashikame only assesses; :effect must be :assessment"})
               (catastrophe? verdicts)
               (conj {:rule :catastrophe-veto
                      :detail "Rider §2 catastrophe-veto scan hit — never published"})
               (person-targeting? verdicts)
               (conj {:rule :person-targeting
                      :detail "verdict targets a private person — not public-figure claim-checking"})
               (some #(and (conclusive-ratings (:rating %)) (empty? (:cites %))) verdicts)
               (conj {:rule :uncited-conclusive
                      :detail "a conclusive rating needs ≥1 citation"})
               (some #(some (complement valid-cite?) (:cites %)) verdicts)
               (conj {:rule :malformed-citation
                      :detail "every citation must be an absolute http(s) URL"}))
        soft (cond-> []
               (< conf confidence-floor)
               (conj {:rule :low-confidence
                      :detail (str "confidence " conf " < floor " confidence-floor)}))]
    {:ok? (empty? hard) :violations hard :warnings soft :confidence conf}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t :governor-hold :op (:op request) :claim (:claim-id request)
   :actor (:actor-id context) :disposition :hold
   :basis (mapv :rule (:violations verdict)) :violations (:violations verdict)})

(defn verdict->disposition
  "Map a FactGovernor verdict to a base disposition. HARD → :hold, else :commit."
  [verdict]
  (if (:ok? verdict) :commit :hold))
