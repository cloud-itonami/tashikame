(ns tashikame.operation
  "OperationActor — one claim check = one supervised actor run, expressed as a
  langgraph-clj StateGraph. factllm (the contained intelligence node) is sealed
  into :advise; its proposal is ALWAYS routed through the FactGovernor (:govern)
  before anything commits to the SSoT or publishes to app-aozora. Mirrors the
  containment + independent-governor + append-only-ledger topology
  (tsumugu.operation / sng.synthesis).

  Everything the actor depends on is injected (each a swap, not a rewrite):
    - the Store     (MemStore | DatomicStore | kotoba-server)  — `store` arg
    - the Advisor   (mock factllm | real-LLM on Murakumo)      — :advisor opt
    - the Publisher (Mock | real app-aozora createRecord)      — :publisher opt
    - the Phase     (0 observe → 1 autonomous-publish)         — :phase in ctx

  One run = intake → advise → govern → decide → commit | hold. NO unbounded
  inner loop; NO interrupt-before — publication is autonomous by default
  (ADR-2606281500). The FactGovernor's HARD violations are the only thing that
  withholds publication: a held verdict is recorded as a hold, never published."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [tashikame.advisor :as advisor]
            [tashikame.governor :as governor]
            [tashikame.phase :as phase]
            [tashikame.publisher :as publisher]
            [tashikame.store :as store]))

(defn- post-body [v]
  (str "【確かめ】" (name (:rating v)) " — " (:claim v)
       (when (seq (:cites v))
         (str " （出典: " (str/join " " (:cites v)) "）"))))

(defn- verdict-record [request context v]
  {:claim-id   (:claim-id request)
   :actor      (:actor-id context)
   :rating     (:rating v)
   :claim      (:claim v)
   :cites      (:cites v)
   :confidence (:confidence v)
   :note       (:note v)
   :collection publisher/collection
   :text       (post-body v)})

(defn build
  "Compiles the tashikame OperationActor graph bound to `store`. opts:
    :advisor      — a `tashikame.advisor/Advisor` (default: mock-advisor)
    :publisher    — a `tashikame.publisher/Publisher` (default: mock-publisher)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor publisher checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    publisher    (publisher/mock-publisher)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id / phase
         :proposal    {:default nil}
         :verdict     {:default nil}   ; FactGovernor result
         :disposition {:default nil}   ; :commit | :hold
         :record      {:default nil}   ; the verdict to commit/publish
         :published   {:default nil}   ; {:uri :cid} when published
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; factllm (contained intelligence) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-assess advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; FactGovernor — independent censor (separate system than factllm).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal)}))

      ;; Decide: HARD violation → :hold; else :commit (autonomous publish).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (case (governor/verdict->disposition verdict)
            :hold
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}
            :commit
            {:disposition :commit
             :record (assoc (verdict-record request context (first (:verdicts proposal)))
                            :warnings (:warnings verdict))})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger, and (when
      ;; the phase allows) publishes to app-aozora. Autonomous by default.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (let [ph       (:phase context phase/default-phase)
                publish? (and (phase/publish-allowed? ph)
                              (= :assessment (:effect proposal)))
                pub      (when publish? (publisher/publish! publisher record))
                f        {:t           :committed
                          :op          (:op request)
                          :actor       (:actor-id context)
                          :claim       (:claim-id request)
                          :disposition :commit
                          :published?  publish?
                          :pub         pub
                          :warnings    (:warnings record)
                          :verdicts    (:verdicts proposal)}]
            (store/commit-verdict! store (:claim-id request) (dissoc record :warnings))
            (store/append-ledger! store f)
            {:published pub :audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation, no publish.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(= :governor-hold (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit :hold)))
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph {:checkpointer checkpointer})))
