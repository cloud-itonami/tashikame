(ns tashikame.operation-test
  "End-to-end operation behavior — the autonomous-publication doctrine as tests:
  low-confidence still publishes (with a transparency tag), not blocked; an
  empty claim no-ops cleanly."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [tashikame.store :as store]
            [tashikame.advisor :as advisor]
            [tashikame.publisher :as publisher]
            [tashikame.operation :as op]))

(defn- low-advisor []
  (reify advisor/Advisor
    (-assess [_ _ _] {:effect :assessment :confidence 0.1
                      :verdicts [{:claim "x" :rating :unverifiable :cites []
                                  :confidence 0.1 :note ""}]
                      :summary "" :rationale ""})))

(deftest low-confidence-still-publishes-with-tag
  (testing "low confidence does NOT block publication (autonomous); it is tagged"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:advisor (low-advisor) :publisher pub})
          r (g/run* a {:request {:op :claim/check :claim-id "l1" :text "x" :source-urls []}
                       :context {:actor-id "tashikame" :phase 1}} {:thread-id "l1"})]
      (is (= :commit (get-in r [:state :disposition])) "low confidence → still publishes")
      (is (= 1 (count @(:a pub))))
      (is (some #(= :low-confidence (:rule %))
                (-> (store/ledger s) last :warnings))))))

(deftest empty-claim-noops
  (testing "an empty claim → :noop effect → governor :no-actuation → hold"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:publisher pub})
          r (g/run* a {:request {:op :claim/check :claim-id "e1" :text "" :source-urls []}
                       :context {:actor-id "tashikame" :phase 1}} {:thread-id "e1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (zero? (count @(:a pub)))))))
