(ns tashikame.store
  "SSoT for the tashikame (確かめ / fact-check) actor — the append-only verdict
  ledger behind a `Store` protocol so the backend is a swap, not a rewrite
  (MemStore default ‖ DatomicStore via langchain.db, itself swappable to real
  Datomic Local / kotoba-server pod e.g. kotobase.net).

  Domain: a claim (text + optional source URLs, ingested from the kawaraban
  news mirror or an ATProto mention) → an assessed verdict (supported /
  refuted / misleading / unverifiable, each citation-grounded) → a published
  fact-check record on app-aozora (collection com.etzhayyim.apps.tashikame.
  factCheck). The append-only ledger is the publication provenance — every
  verdict is an immutable fact, never overwritten; a held (FactGovernor-
  rejected) verdict is recorded as a hold, never published.

  The store talks to its backend ONLY through the langchain.db `:db-api` map
  {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process EAVT) and
  `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC) both implement it, so
  the same `DatomicStore` record runs on either by construction."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (verdict [s id] "the committed verdict record for a claim-id, or nil")
  (all-verdicts [s])
  (ledger [s])
  (commit-verdict! [s id payload] "commit one assessed verdict record")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (verdict [_ id] (get-in @a [:verdicts id]))
  (all-verdicts [_] (sort-by :claim-id (vals (:verdicts @a))))
  (ledger [_] (:ledger @a))
  (commit-verdict! [s id payload] (swap! a assoc-in [:verdicts id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore."
  []
  (->MemStore (atom {:verdicts {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────

(def ^:private schema
  {:tashikame.verdict/id {:db/unique :db.unique/identity}
   :tashikame.ledger/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT) and
;; langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g. kotobase.net) both
;; implement it, so the same record runs on either by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (verdict [this id]
    (dec* (q* this '[:find ?p . :in $ ?id :where
                     [?e :tashikame.verdict/id ?id]
                     [?e :tashikame.verdict/payload ?p]]
               id)))
  (all-verdicts [this]
    (->> (q* this '[:find [?id ...] :where [?e :tashikame.verdict/id ?id]])
         (map #(verdict this %)) (sort-by :claim-id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where
                    [?e :tashikame.ledger/seq ?s] [?e :tashikame.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-verdict! [s id payload]
    (tx* s [{:tashikame.verdict/id id :tashikame.verdict/payload (enc payload)}]) s)
  (append-ledger! [s fact]
    (tx* s [{:tashikame.ledger/seq (count (ledger s)) :tashikame.ledger/fact (enc fact)}]) fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (verifiable
  offline, no network). For the kotoba-server pod (kotobase.net), bind the
  same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see docs/adr/0001-architecture.md)."
  []
  (->DatomicStore d/api (d/create-conn schema)))
