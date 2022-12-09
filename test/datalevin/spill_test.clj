(ns datalevin.spill-test
  (:require
   [datalevin.lmdb :as l]
   [datalevin.spill :as sp]
   [datalevin.interpret :as i]
   [datalevin.util :as u]
   [datalevin.core :as dc]
   [datalevin.constants :as c]
   [datalevin.datom :as d]
   [clojure.test :refer [deftest testing is]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.clojure-test :as test]
   [clojure.test.check.properties :as prop])
  (:import
   [clojure.lang ISeq IPersistentVector]
   [datalevin.spill SpillableVector]))

(if (u/graal?)
  (require 'datalevin.binding.graal)
  (require 'datalevin.binding.java))

(sp/uninstall-memory-updater)

(deftest before-spill-test
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 0)

    (is (vector? vs))
    (is (nil? (seq vs)))
    (is (= "[]" (.toString vs)))
    (is (= [] vs))
    (is (nil? (get vs 0)))
    (is (nil? (peek vs)))
    (is (nil? (first vs)))
    (is (nil? (second vs)))
    (is (nil? (last vs)))
    (is (= 0 (.length vs)))
    (is (= 0 (count vs)))
    (is (not (contains? vs 0)))
    (is (thrown? Exception (nth vs 0)))
    (is (= vs []))
    (is (= vs '()))
    (is (not= vs {}))
    (is (not= vs 1))
    (is (not= vs [1]))
    (is (= [] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [] (subvec vs 0)))
    (is (= [] (into [] vs)))
    (is (thrown? Exception (pop vs)))

    (assoc vs 0 0)
    (is (= [0] vs))
    (is (= 0 (get vs 0)))
    (is (= 0 (peek vs)))
    (is (= 0 (first vs)))
    (is (nil? (second vs)))
    (is (= 0 (last vs)))
    (is (= 1 (.length vs)))
    (is (= 1 (count vs)))
    (is (contains? vs 0))
    (is (= 0 (nth vs 0)))
    (is (thrown? Exception (nth vs 1)))
    (is (= vs [0]))
    (is (= vs '(0)))
    (is (not= vs [0 :end]))
    (is (not= vs 1))
    (is (= [1] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [0] (subvec vs 0)))
    (is (= [0] (into [] vs)))
    (is (= [] (pop vs)))

    (conj vs 0)
    (conj vs 1)
    (conj vs 2)
    (is (= [0 1 2] vs))
    (is (= 1 (get vs 1)))
    (is (= 2 (peek vs)))
    (is (= 0 (first vs)))
    (is (= 1 (second vs)))
    (is (= 2 (last vs)))
    (is (= 3 (.length vs)))
    (is (= 3 (count vs)))
    (is (contains? vs 2))
    (is (= 0 (nth vs 0)))
    (is (= 1 (nth vs 1)))
    (is (= vs [0 1 2]))
    (is (= vs '(0 1 2)))
    (is (thrown? Exception (nth vs 5)))
    (is (not= vs [0 1 :end]))
    (is (not= vs 1))
    (is (= [1 2 3] (map inc vs)))
    (is (= 3 (reduce + vs)))
    (is (= [1] (subvec vs 1 2)))
    (is (= [0 1 2] (into [] vs)))
    (is (= [0 1] (pop vs)))))

(deftest spill=in-middle-test
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 0)

    (conj vs 0)
    (is (= [0] vs))
    (is (= 0 (get vs 0)))
    (is (= 0 (peek vs)))
    (is (= 0 (first vs)))
    (is (nil? (second vs)))
    (is (= 0 (last vs)))
    (is (= 1 (.length vs)))
    (is (= 1 (count vs)))
    (is (contains? vs 0))
    (is (= 0 (nth vs 0)))
    (is (thrown? Exception (nth vs 1)))
    (is (= vs [0]))
    (is (= vs '(0)))
    (is (not= vs [0 :end]))
    (is (not= vs 1))
    (is (= [1] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [0] (subvec vs 0)))
    (is (= [0] (into [] vs)))
    (is (= [0] vs))
    (is (= [] (pop vs)))

    (vreset! sp/memory-pressure 99)

    (conj vs 0)
    (conj vs 1)
    (conj vs 2)
    (is (= [0 1 2] vs))
    (is (= 1 (get vs 1)))
    (is (= 2 (peek vs)))
    (is (= 0 (first vs)))
    (is (= 1 (second vs)))
    (is (= 2 (last vs)))
    (is (= 3 (.length vs)))
    (is (= 3 (count vs)))
    (is (contains? vs 2))
    (is (= 0 (nth vs 0)))
    (is (= 1 (nth vs 1)))
    (is (= vs [0 1 2]))
    (is (= vs '(0 1 2)))
    (is (thrown? Exception (nth vs 5)))
    (is (not= vs [0 1 :end]))
    (is (not= vs 1))
    (is (= [1 2 3] (map inc vs)))
    (is (= 3 (reduce + vs)))
    (is (= [1] (subvec vs 1 2)))
    (is (= [0 1 2] (into [] vs)))
    (is (= [0 1] (pop vs)))))

(deftest spill=at-start-test
  (let [^SpillableVector vs (sp/new-spillable-vector)]
    (vreset! sp/memory-pressure 99)

    (conj vs 0)
    (is (not (l/closed-kv? @(.-disk vs))))
    (is (= [0] vs))
    (is (= 0 (get vs 0)))
    (is (= 0 (peek vs)))
    (is (= 0 (first vs)))
    (is (nil? (second vs)))
    (is (= 0 (last vs)))
    (is (= 1 (.length vs)))
    (is (= 1 (count vs)))
    (is (contains? vs 0))
    (is (= 0 (nth vs 0)))
    (is (thrown? Exception (nth vs 1)))
    (is (= vs [0]))
    (is (= vs '(0)))
    (is (not= vs [0 :end]))
    (is (not= vs 1))
    (is (= [1] (map inc vs)))
    (is (= 0 (reduce + vs)))
    (is (= [0] (subvec vs 0)))
    (is (= [0] (into [] vs)))
    (is (= [0] vs))
    (is (= [] (pop vs)))

    (conj vs 0)
    (conj vs 1)
    (conj vs 2)
    (is (= [0 1 2] vs))
    (is (= 1 (get vs 1)))
    (is (= 2 (peek vs)))
    (is (= 0 (first vs)))
    (is (= 1 (second vs)))
    (is (= 2 (last vs)))
    (is (= 3 (.length vs)))
    (is (= 3 (count vs)))
    (is (contains? vs 2))
    (is (= 0 (nth vs 0)))
    (is (= 1 (nth vs 1)))
    (is (= vs [0 1 2]))
    (is (= vs '(0 1 2)))
    (is (thrown? Exception (nth vs 5)))
    (is (not= vs [0 1 :end]))
    (is (not= vs 1))
    (is (= [1 2 3] (map inc vs)))
    (is (= 3 (reduce + vs)))
    (is (= [1] (subvec vs 1 2)))
    (is (= [0 1 2] (into [] vs)))
    (is (= [0 1] (pop vs)))
    (is (= @(.total vs)
           (+ ^long (sp/memory-count vs) ^long (sp/disk-count vs))))))
