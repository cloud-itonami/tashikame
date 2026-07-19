#!/usr/bin/env bb
(ns gen-lexicons
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def source "contracts/lexicons/com.etzhayyim.apps.tashikame.factCheck.edn")
(def target "lexicons/com/etzhayyim/apps/tashikame/factCheck.json")

(defn generated []
  (str (json/generate-string (edn/read-string (slurp source)) {:pretty true}) "\n"))

(defn -main [& args]
  (let [body (generated)]
    (if (some #{"--check"} args)
      (when (or (not (fs/exists? target)) (not= body (slurp target)))
        (binding [*out* *err*] (println "stale generated lexicon:" target))
        (System/exit 1))
      (do (fs/create-dirs (fs/parent target))
          (spit target body)
          (println "generated" target)))))

(apply -main *command-line-args*)
