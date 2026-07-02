(ns tashikame.publisher
  "Publisher — the outbound surface for a tashikame verdict, injected so the
  network is a swap (MockPublisher default ‖ real app-aozora createRecord via
  `tashikame.aozora`). The graph never reaches the network directly; :commit
  calls `(publish! publisher record)` only after the FactGovernor passed AND
  the phase allows publication (autonomous by default, ADR-2606281500).

  record shape (what gets published):
    {:claim-id :rating :claim :cites :confidence :note :text (social-post body)
     :collection \"com.etzhayyim.apps.tashikame.factCheck\"}")

(def collection "com.etzhayyim.apps.tashikame.factCheck")

(defprotocol Publisher
  (publish! [p record] "publish one verdict record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri  (str "at://mock/tashikame/" (:claim-id record))
     :cid  (str "mock:" (:claim-id record))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been published."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
