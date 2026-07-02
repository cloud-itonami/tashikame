(ns tashikame.store-contract-test
  "MemStore ≡ DatomicStore contract — the same verdict record + ledger facts
  appear regardless of backend (in-process EAVT today; kotoba-server pod
  tomorrow). tashikame's analog of tsumugu/sng store_contract_test."
  (:require [clojure.test :refer [deftest is testing]]
            [tashikame.store :as store]))

(deftest mem-and-datomic-agree-on-commit-and-ledger
  (testing "the same verdict + ledger fact land on both backends"
    (let [mem (store/seed-db)
          dat (store/datomic-store)]
      (doseq [s [mem dat]]
        (store/commit-verdict! s "c1" {:claim-id "c1" :rating :supported
                                       :claim "x" :cites ["https://a"]})
        (store/append-ledger! s {:t :committed :claim "c1" :disposition :commit}))
      (is (= :supported (get (store/verdict mem "c1") :rating)))
      (is (= :supported (get (store/verdict dat "c1") :rating)))
      (is (= 1 (count (store/ledger mem))))
      (is (= 1 (count (store/ledger dat))))
      (is (= :committed (:t (last (store/ledger mem)))))
      (is (= :committed (:t (last (store/ledger dat))))))))

(deftest ledger-is-append-only
  (testing "ledger facts accumulate by seq, never overwritten"
    (let [s (store/seed-db)]
      (store/append-ledger! s {:t :committed :claim "a"})
      (store/append-ledger! s {:t :hold      :claim "b"})
      (is (= 2 (count (store/ledger s))))
      (is (= [:committed :hold] (map :t (store/ledger s)))))))
