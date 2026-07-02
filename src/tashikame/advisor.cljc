(ns tashikame.advisor
  "factllm — the *contained intelligence node* for tashikame. It takes a claim
  (text + optional source URLs, from the kawaraban news mirror or an ATProto
  mention) and returns a PROPOSAL: a set of assessed verdicts (supported /
  refuted / misleading / unverifiable), each with citations and a confidence.
  It NEVER returns a committed record and NEVER decides publication — the
  FactGovernor censors every proposal downstream, and only :commit writes the
  SSoT + publishes. Mirrors the `Advisor` protocol shape used by
  tsumugu.mangallm / sng.synthllm.

  Sealed by construction: the default `mock-advisor` is deterministic (no
  non-deterministic free-write). The real advisor wires `langchain.model`
  against the Murakumo fleet (DEFAULT-PREFERRED per Rider v3.3 §2(i)) with a
  read-only web gather step (no-server-key read-only, autonomously allowed per
  ADR-2606072802) — still proposal-only, still governor-censored.

  Proposal shape:
    {:summary    str
     :rationale  str
     :verdicts   [{:claim str :rating kw :cites [url…] :confidence 0..1 :note str}]
     :effect     :assessment   ; tashikame only ever assesses, never actuates
     :confidence 0..1}"
  (:require [clojure.string :as str]))

(defprotocol Advisor
  (-assess [advisor store request] "store + request → proposal map"))

(defn- valid-url? [s] (and (string? s) (re-find #"^https?://" s)))

(defn- assess* [{:keys [text source-urls]}]
  (let [cites (vec (filter valid-url? (or source-urls [])))]
    (cond
      (or (nil? text) (str/blank? text))
      {:summary "empty claim" :rationale "no claim text" :verdicts []
       :effect :noop :confidence 0.0}

      (seq cites)
      {:summary (str "claim assessed: " (count cites) " source(s) cited")
       :rationale "cited claim → supported (mock heuristic)"
       :verdicts [{:claim text :rating :supported :cites cites
                   :confidence 0.8 :note "mock advisor: cited claim"}]
       :effect :assessment :confidence 0.8}

      :else
      {:summary "claim unverifiable: no citations supplied"
       :rationale "uncited claim → unverifiable (mock heuristic)"
       :verdicts [{:claim text :rating :unverifiable :cites []
                   :confidence 0.3 :note "mock advisor: no citations"}]
       :effect :assessment :confidence 0.3})))

(defn mock-advisor
  "The deterministic advisor (default everywhere — no non-deterministic LLM
  free-write). Real-LLM wiring is a swap via `langchain.model` on Murakumo."
  []
  (reify Advisor (-assess [_ _store req] (assess* req))))

(defn trace
  "Decision-grounded audit record (evaluation appeals, publish audits)."
  [request proposal]
  {:t          :factllm-proposal
   :op         (:op request)
   :claim-id   (:claim-id request)
   :summary    (:summary proposal)
   :verdicts   (:verdicts proposal)
   :confidence (:confidence proposal)})
