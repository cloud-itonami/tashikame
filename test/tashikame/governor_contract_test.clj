(ns tashikame.governor-contract-test
  "The fact-check publication contract as executable tests. Invariant:
  tashikame NEVER publishes a verdict the FactGovernor rejects; every published
  verdict is citation-grounded; catastrophe-veto / person-targeting /
  uncited-conclusive / no-actuation proposals are held (recorded as a hold,
  never published). Publication is autonomous by default (ADR-2606281500)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [tashikame.store :as store]
            [tashikame.advisor :as advisor]
            [tashikame.publisher :as publisher]
            [tashikame.operation :as op]))

(defn- fresh []
  (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))]
    [s pub (op/build s {:publisher pub})]))

(defn- run [actor claim-id text urls]
  (g/run* actor
          {:request {:op :claim/check :claim-id claim-id :text text :source-urls urls}
           :context {:actor-id "tashikame" :phase 1}}
          {:thread-id claim-id}))

(defn- basis [s] (-> (store/ledger s) last :basis))

(defn- published-count [pub] (count @(:a pub)))

(defn- with-advisor [s pub adv]
  (op/build s {:advisor adv :publisher pub}))

(defn- bad-advisor [rating cites note]
  (reify advisor/Advisor
    (-assess [_ _ _] {:effect :assessment :confidence 0.9
                      :verdicts [{:claim "x" :rating rating :cites cites
                                  :confidence 0.9 :note note}]
                      :summary "" :rationale ""})))

(deftest cited-clean-verdict-publishes
  (testing "a cited, governor-clean verdict commits + publishes autonomously"
    (let [[s pub actor] (fresh)
          r (run actor "c1" "可決された条例" ["https://example.gov/press/1"])]
      (is (= :commit (get-in r [:state :disposition])))
      (is (= :supported (get (store/verdict s "c1") :rating)))
      (is (= 1 (published-count pub))))))

(deftest uncited-conclusive-is-held
  (testing "a :refuted verdict with 0 cites → HOLD (:uncited-conclusive)"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor :refuted [] ""))
          r (g/run* a2 {:request {:op :claim/check :claim-id "u1" :text "x" :source-urls []}
                        :context {:actor-id "tashikame" :phase 1}} {:thread-id "u1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:uncited-conclusive} (basis s)))
      (is (zero? (published-count pub)) "never published")
      (is (nil? (store/verdict s "u1")) "nothing recorded on hold"))))

(deftest catastrophe-veto-is-held
  (testing "a verdict hitting the Rider §2 catastrophe-veto → HOLD"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor :supported ["https://a"] "<CAT>"))
          r (g/run* a2 {:request {:op :claim/check :claim-id "v1" :text "x"
                                  :source-urls ["https://a"]}
                        :context {:actor-id "tashikame" :phase 1}} {:thread-id "v1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:catastrophe-veto} (basis s)))
      (is (zero? (published-count pub))))))

(deftest person-targeting-is-held
  (testing "a verdict that doxes a private person → HOLD (:person-targeting)"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor :supported ["https://a"] "<DOXING>"))
          r (g/run* a2 {:request {:op :claim/check :claim-id "d1" :text "x"
                                  :source-urls ["https://a"]}
                        :context {:actor-id "tashikame" :phase 1}} {:thread-id "d1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:person-targeting} (basis s))))))

(deftest no-actuation-is-held
  (testing "a proposal that tries to actuate (not assess) → HOLD (:no-actuation)"
    (let [[s pub _] (fresh)
          bad (reify advisor/Advisor
                (-assess [_ _ _] {:effect :grant-attestation :confidence 0.9
                                  :verdicts [] :summary "" :rationale ""}))
          a2 (with-advisor s pub bad)
          r (g/run* a2 {:request {:op :claim/check :claim-id "n1" :text "x"
                                  :source-urls ["https://a"]}
                        :context {:actor-id "tashikame" :phase 1}} {:thread-id "n1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:no-actuation} (basis s))))))

(deftest phase0-records-but-does-not-publish
  (testing "phase 0 (observe): a clean verdict is recorded but NOT published"
    (let [[s pub actor] (fresh)
          r (g/run* actor {:request {:op :claim/check :claim-id "p0" :text "可決"
                                     :source-urls ["https://a"]}
                           :context {:actor-id "tashikame" :phase 0}} {:thread-id "p0"})]
      (is (= :commit (get-in r [:state :disposition])) "verdict recorded")
      (is (= :supported (get (store/verdict s "p0") :rating)))
      (is (zero? (published-count pub)) "phase 0 → shadow, no publish"))))
