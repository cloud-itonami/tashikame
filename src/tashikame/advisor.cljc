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
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

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

;; ───────────────────────── real-LLM advisor (Murakumo fleet) ─────────────────────────
;; Sealed just like the mock: it returns a PROPOSAL only — the FactGovernor
;; still censors every verdict. The model is an INJECTED langchain.model/
;; ChatModel (OpenAI-compatible Ollama / LiteLLM, or Anthropic). Inference is
;; DEFAULT-PREFERRED on the Murakumo fleet (Rider v3.3 §2(i)); the deploy
;; entrypoint builds the chat-model against the local Ollama (gemma-4-E4B) and
;; injects it here. Per ADR-2606072802, a read-only web gather is autonomously
;; allowed (no operator gate) — the caller may pre-fetch source text into the
;; request before assessing.

(def allowed-infer-hosts
  "Murakumo-fleet inference hosts only (Rider v3.3 §2(i)). No opaque/lock-in
  commercial GPU by default. The com-junkawasaki tailnet entries (added
  2026-07-13) are the same physical fleet nodes as the localhost/LAN
  entries above, reachable via Tailscale instead — confirmed live and
  cross-checked against the fleet's own registry
  (https://api.murakumo.cloud/nodes)."
  #{"127.0.0.1:11434" "localhost:11434"
    "127.0.0.1:4000"  "localhost:4000"
    "192.168.1.70:4000"
    ;; com-junkawasaki tailnet Murakumo-fleet Ollama nodes (verified live
    ;; 2026-07-13 against https://api.murakumo.cloud/nodes)
    "100.98.142.59:11434"   ; dan
    "100.66.28.79:11434"    ; zebulun
    "100.102.78.81:11434"   ; levi
    "100.75.169.8:11434"    ; benjamin
    "100.89.204.30:11434"   ; issachar
    "100.82.123.35:11434"   ; joseph
    "100.101.27.85:11434"   ; naphtali
    "100.81.66.86:11434"})  ; simeon

(defn- host-port [url]
  (when (string? url) (second (re-find #"(?i)^[a-z]+://([^/]+)" url))))

(defn assert-murakumo!
  "Throw if `ollama-url` is not a Murakumo-fleet inference host."
  [ollama-url]
  (let [hp (host-port ollama-url)]
    (when-not (contains? allowed-infer-hosts hp)
      (throw (ex-info (str "inference host " hp " is not Murakumo-fleet (Rider §2(i))")
                      {:host hp})))))

(def tashikame-system-prompt
  "You are tashikame (確かめ), a fact-checker. Assess the user's claim against
the provided source URLs (and your own knowledge). Be conservative: if you
cannot verify, say :unverifiable. Respond with ONLY a single-line EDN map,
no prose, no code fences:
  {:rating <supported|refuted|misleading|unverifiable> :cites [\"https://...\"] :confidence <0.0-1.0> :note \"one short sentence\"}
Cite ONLY real, reachable URLs. An uncited conclusive rating is worthless.")

(defn- build-prompt [{:keys [text source-urls]}]
  (str "Claim: " text "\n\n"
       "Source URLs provided: " (pr-str (vec (or source-urls []))) "\n\n"
       "Return ONLY the EDN map now."))

(def ^:private valid-ratings #{:supported :refuted :misleading :unverifiable})

(defn- coerce-rating [r]
  (let [k (cond (keyword? r) r
                (string? r)  (keyword (str/lower-case r))
                (symbol? r)  (keyword (str/lower-case (name r)))
                :else        nil)]
    (if (valid-ratings k) k :unverifiable)))

(defn parse-verdict-edn
  "Defensively parse the LLM's EDN verdict map. Any parse failure → a safe
  :unverifiable verdict (the FactGovernor then gates it; the system never
  breaks on a malformed model output)."
  [content]
  (let [s (-> (str content)
              (str/replace #"(?s)```[a-zA-Z]*" "")
              (str/replace "```" ""))]
    (try
      (let [m (some-> (re-find #"(?s)\{.*\}" s) edn/read-string)
            conf (:confidence m)]
        {:rating     (coerce-rating (:rating m))
         :cites      (vec (filter string? (:cites m)))
         :confidence (if (number? conf) (max 0.0 (min 1.0 (double conf))) 0.3)
         :note       (str (or (:note m) ""))})
      (catch #?(:clj Throwable :cljs :default) _
        {:rating :unverifiable :cites [] :confidence 0.2
         :note "LLM output unparseable"}))))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel (OpenAI-compatible Ollama /
   LiteLLM, or Anthropic). Sealed: returns a PROPOSAL only; the FactGovernor
   still censors. gen-opts → model/-generate opts (e.g. {:max-tokens 512})."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-assess [_ _store request]
       (let [content (:content
                      (model/-generate chat-model
                        [{:role :system :content tashikame-system-prompt}
                         {:role :user   :content (build-prompt request)}]
                        gen-opts)
                      {})
             v      (parse-verdict-edn content)]
         {:summary    (str "factllm verdict: " (name (:rating v)))
          :rationale  "LLM assessment (Murakumo); governor-censored downstream"
          :verdicts   [(assoc v :claim (:text request))]
          :effect     :assessment
          :confidence (:confidence v)})))))
