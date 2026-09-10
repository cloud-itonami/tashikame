(ns tashikame.lexicon-contract-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(def contract
  (edn/read-string
   (slurp "contracts/lexicons/com.etzhayyim.apps.tashikame.factCheck.edn")))

(deftest fact-check-contract-matches-publisher
  (testing "canonical identity and record type"
    (is (= "com.etzhayyim.apps.tashikame.factCheck" (:id contract)))
    (is (= "record" (get-in contract [:defs :main :type]))))
  (testing "publisher record fields are represented on the wire"
    (let [required (set (get-in contract [:defs :main :record :required]))
          properties (set (keys (get-in contract [:defs :main :record :properties])))]
      (is (= #{"claimId" "actor" "rating" "claim" "cites" "confidence" "text"}
             required))
      (is (contains? properties :note))))
  (testing "governor rating vocabulary is closed"
    (is (= #{"supported" "refuted" "misleading" "unverifiable"}
           (set (get-in contract [:defs :main :record :properties :rating :enum]))))))
