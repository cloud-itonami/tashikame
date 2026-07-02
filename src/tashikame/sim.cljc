(ns tashikame.sim
  "Offline demo: drive two sample claims (one cited, one uncited) through the
  tashikame actor on a MemStore + mock advisor + mock publisher (no network).
  `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [tashikame.operation :as op]
            [tashikame.store :as store]
            [tashikame.advisor :as advisor]
            [tashikame.publisher :as publisher])
  (:gen-class))

(defn -main [& _args]
  (let [s   (store/seed-db)
        pub (publisher/mock-publisher)
        a   (op/build s {:advisor (advisor/mock-advisor) :publisher pub})]
    (doseq [claim [{:op :claim/check :claim-id "c1"
                    :text "ある条例が可決されたという噂"
                    :source-urls ["https://example.gov/press/123"]
                    :origin :kawaraban}
                   {:op :claim/check :claim-id "c2"
                    :text "出典のない噂話"
                    :source-urls [] :origin :mention}]]
      (let [r (g/run* a {:request claim :context {:actor-id "tashikame" :phase 1}}
                      {:thread-id (:claim-id claim)})]
        (println (get-in r [:state :disposition]) "←" (:claim-id claim)
                 "published?" (some? (get-in r [:state :published])))))
    (println "--- would-be published records ---")
    (doseq [p @(:a pub)] (println (:claim-id p) "→" (name (:rating p)) "|" (:text p)))
    (println "--- ledger ---")
    (doseq [f (store/ledger s)] (prn f))))
