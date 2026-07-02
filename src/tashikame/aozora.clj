(ns tashikame.aozora
  "Real app-aozora Publisher for tashikame — creates a record in the
  com.etzhayyim.apps.tashikame.factCheck collection on an aozora PDS via the
  AT Protocol com.atproto.repo.createRecord XRPC, authenticated by a depth-1
  self-minted CACAO (the actor's own did:key + a revocable member CACAO leash
  = the off-switch, ADR-2606281500). Ported shape from tsumugu.kotoba (CACAO
  mint + JDK http) and ossekai's app.bsky.feed.post publish path.

  I/O is injected: an http-fn (default JDK java.net.http, no dependency) and a
  JSON pair passed by the caller, so this namespace stays dependency-free.
  Publication is the actor's own SPEECH (ADR-2606281500) — NOT actuation."
  (:require [clojure.string :as str]
            [tashikame.cacao :as cacao]
            [tashikame.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util UUID]))

(def default-pds "https://pds.etzhayyim.com")

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn aozora-publisher
  "Returns a `tashikame.publisher/Publisher` that creates factCheck records on
  the aozora PDS. opts:
    :pds         PDS base URL (default default-pds)
    :identity    {:private-key :did …} from cacao/load-or-create-identity!
    :leash       a member CACAO b64 (the revocable off-switch); nil → record
                 attributed to the actor's own did:key (depth-1 self-mint)
    :json-write  :json-read  injected JSON fns (e.g. clojure.data.json)
    :http-fn     optional override (default jvm-http-fn)"
  [{:keys [pds identity leash json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required (e.g. clojure.data.json/write-str)")
  (assert json-read  ":json-read fn is required (e.g. clojure.data.json/read-str)")
  (reify publisher/Publisher
    (publish! [_ record]
      (let [url   (str pds "/xrpc/com.atproto.repo.createRecord")
            now   (str (Instant/now))
            cacao (cacao/mint identity
                              {:cap :cap/transact :scope publisher/collection}
                              {:aud pds :nonce (str (UUID/randomUUID))
                               :issued-at now
                               :expiry (str (.plusSeconds (Instant/now) 3600))})
            body  (json-write
                   {:repo       (:did identity)
                    :collection publisher/collection
                    :record     (-> (select-keys record
                                                [:claim-id :rating :claim :cites
                                                 :confidence :note :text])
                                    (assoc :createdAt now
                                           :actor (:did identity)
                                           :leash leash))})
            {:keys [status body]} (http-fn {:url url :method :post
                                            :headers {"Content-Type" "application/json"
                                                      "Authorization" (str "Bearer " cacao)}
                                            :body body})
            resp  (json-read body)]
        (when-not (= 200 status)
          (throw (ex-info "aozora createRecord failed"
                          {:status status :body body})))
        {:uri (:uri resp) :cid (:cid resp)}))))
