(ns tashikame.run-tests
  "Test runner for com-etzhayyim-tashikame (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path: `clojure -M:dev:test`
  (cognitect test-runner). This runner: `clojure -M -m tashikame.run-tests`."
  (:require [clojure.test :refer [run-tests]]
            [tashikame.governor-contract-test]
            [tashikame.kotoba-parity-test]
            [tashikame.store-contract-test]
            [tashikame.operation-test])
  (:gen-class))

(defn -main [& _args]
  (let [res (run-tests
             'tashikame.governor-contract-test
             'tashikame.kotoba-parity-test
             'tashikame.store-contract-test
             'tashikame.operation-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))
