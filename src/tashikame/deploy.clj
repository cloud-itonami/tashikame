(ns tashikame.deploy
  "Deploy entrypoint — wires a REAL Murakumo-fleet LLM (langchain.model
  OpenAI-compatible against the local Ollama, gemma-4-E4B) into the tashikame
  advisor and runs ONE claim check end-to-end.

  Publication is MockPublisher by default: the autonomous-publish rail is live
  (ADR-2606281500), but an actual aozora write needs (a) the actor's did
  registered on the PDS, (b) a member CACAO leash (LEASH env — the off-switch),
  and (c) a real Publisher wired via `tashikame.aozora`. That flip is the
  owner's — this entrypoint proves the real-LLM → governor → (mock) publish
  path against the live Murakumo model.

  Usage: clojure -M:dev -m tashikame.deploy \"<claim>\" [source-url...]
  Env:   TASHIKAME_OLLAMA_URL (default http://127.0.0.1:11434)
         TASHIKAME_OLLAMA_MODEL (default gemma-4-E4B qat)"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [langchain.model :as model]
            [langgraph.graph :as g]
            [tashikame.advisor :as advisor]
            [tashikame.aozora :as aozora]
            [tashikame.cacao :as cacao]
            [tashikame.publisher :as publisher]
            [tashikame.store :as store]
            [tashikame.operation :as op])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers])
  (:gen-class))

(def ^:private default-ollama-url
  (or (System/getenv "TASHIKAME_OLLAMA_URL") "http://127.0.0.1:11434"))

(def ^:private default-ollama-model
  (or (System/getenv "TASHIKAME_OLLAMA_MODEL")
      "hf.co/unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL"))

(defn jvm-http-fn
  "langchain.model :http-fn backed by the JDK HTTP client (no dependency)."
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

(defn ollama-chat-model
  "Build a langchain.model/openai-model against a Murakumo-fleet Ollama.
  Refuses non-Murakumo hosts (Rider §2(i))."
  ([]
   (ollama-chat-model default-ollama-url default-ollama-model))
  ([ollama-url ollama-model]
   (advisor/assert-murakumo! ollama-url)
   (model/openai-model
    {:url        (str ollama-url "/v1/chat/completions")
     :model      ollama-model
     :api-key    nil
     :http-fn    jvm-http-fn
     :json-write json/write-str
     :json-read  #(json/read-str % :key-fn keyword)})))

(defn identify-live
  "Live identify test (ADR-2607022300 identify facet): generate the actor's
  self-sovereign did:key, then createSession(self-CACAO)→JWT→createRecord a
  profile record to pds.aozora.app. Proves the app-aozora-pds auth flow.
  clojure -M:dev -m tashikame.deploy identify-live"
  []
  (let [id  (cacao/load-or-create-identity! ".tashikame/identity.edn")
        pub (aozora/aozora-publisher {:pds        "https://pds.aozora.app"
                                      :identity   id
                                      :json-write json/write-str
                                      :json-read  json/read-str})
        profile {:$type       "com.etzhayyim.apps.tashikame.profile"
                 :collection  "com.etzhayyim.apps.tashikame.profile"
                 :rkey        "self"
                 :displayName "確かめ — Fact-Check Verdict Publisher"
                 :description "tashikame (確かめ) live identify via createSession→createRecord (self-sovereign did:key)."
                 :lexicons    ["com.etzhayyim.apps.tashikame.factCheck"]}]
    (println "actor did:key :" (:did id))
    (println "createSession→createRecord profile @ pds.aozora.app, repo=" (:did id))
    (try
      (let [r (publisher/publish! pub profile)] (println "PUBLISHED:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn -main
  [& args]
  (when (= (first args) "identify-live") (identify-live) (System/exit 0))
  (let [[claim & urls] (if (seq args) args
                           ["The Great Wall of China is visible from the Moon with the naked eye."])
        chat    (ollama-chat-model)
        ;; 512 is too tight for a "thinking" model (gemma4:e4b-it-qat emits a
        ;; separate :reasoning field before :content and can burn the whole
        ;; budget there) -- verified live 2026-07-13 against yosoku.deploy.
        adv     (advisor/llm-advisor chat {:max-tokens 1024})
        s       (store/seed-db)
        pub     (publisher/mock-publisher)
        actor   (op/build s {:advisor adv :publisher pub})
        cid     "deploy-1"
        req     {:op :claim/check :claim-id cid
                 :text claim :source-urls (vec (remove nil? (or urls [])))}
        r       (g/run* actor {:request req :context {:actor-id "tashikame" :phase 1}}
                         {:thread-id cid})]
    (println "=== tashikame deploy (real LLM @ Murakumo) ===")
    (println "claim      :" claim)
    (println "disposition:" (get-in r [:state :disposition]))
    (println "verdict    :" (pr-str (get (store/verdict s cid) :rating)))
    (println "published? :" (boolean (get-in r [:state :published])) "(mock publisher)")
    (println "ledger tail:" (pr-str (last (store/ledger s))))))
