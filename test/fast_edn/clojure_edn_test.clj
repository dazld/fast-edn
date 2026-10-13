(ns fast-edn.clojure-edn-test
  "fast-edn reads every input the way clojure.edn reads it: the same value,
   with the same types and metadata, or an error where clojure.edn errors.
   Error messages may differ."
  (:require
   [clojure.edn]
   [clojure.test :refer [deftest is]]
   [fast-edn.core :as edn]))

(defn- kind [x]
  (cond
    (nil? x)    :nil
    (map? x)    :map
    (set? x)    :set
    (vector? x) :vector
    (seq? x)    :seq
    :else       (class x)))

(defn- same?
  "Structural equality that also requires equal kinds and classes at every
   node, equal metadata, and identical NaN instances."
  [a b]
  (and (= (kind a) (kind b))
       (= (meta a) (meta b))
       (cond
         (map? a)
         (and (= (count a) (count b))
              (every? (fn [[k v]]
                        (when-let [[kb vb] (find b k)]
                          (and (same? k kb) (same? v vb))))
                      a))

         (set? a)
         (and (= (count a) (count b))
              (every? #(and (contains? b %) (same? % (get b %))) a))

         (coll? a)
         (and (= (count a) (count b))
              (every? true? (map same? a b)))

         (and (instance? Double a) (Double/isNaN a))
         (identical? a b)

         :else
         (= a b))))

(defn- outcome [read s]
  (try
    [:ok (read {:eof ::eof} s)]
    (catch Exception _
      [:error])))

(defn- difference
  "nil when both readers agree on `s`, otherwise both outcomes."
  [s]
  (let [[ta va :as a] (outcome clojure.edn/read-string s)
        [tb vb :as b] (outcome edn/read-string s)]
    (when-not (and (= ta tb)
                   (or (= :error ta) (same? va vb)))
      {:input s :clojure-edn a :fast-edn b})))

(defn- test-literals
  "Every string literal in the example-based suite."
  []
  (->> (slurp "test/fast_edn/test.clj")
       (re-seq #"\"((?:[^\"\\]|\\.)*)\"")
       (keep #(try (read-string (str "\"" (second %) "\"")) (catch Exception _ nil)))
       distinct))

(def ^:private alphabet
  (vec (concat "0123456789"
               ":/@`~'#^-+.eExXrRN Mabz,;()[]{}\"\\"
               "uu0178ff"
               ;; no-break space, em space, ideographic space, Arabic-Indic
               ;; three, line separator, fullwidth one
               (map char [0xa0 0x2003 0x3000 0x663 0x2028 0xff11]))))

(defn- fuzz-inputs [^java.util.Random rnd n]
  (let [token (fn []
                (apply str (repeatedly (inc (.nextInt rnd 8))
                                       #(nth alphabet (.nextInt rnd (count alphabet))))))]
    (for [_ (range n)
          :let [t (token)]
          wrapped [t (str "[" t "]") (str "{:k " t "}") (str "\"" t "\"")]]
      wrapped)))

(deftest same-as-clojure-edn-on-examples-test
  (let [literals (test-literals)]
    (is (< 400 (count literals)))
    (is (= [] (into [] (keep difference) literals)))))

(deftest same-as-clojure-edn-on-fuzz-test
  (is (= [] (into [] (comp (keep difference) (take 20))
                  (fuzz-inputs (java.util.Random. 42) 250000)))))

(deftest symbolic-values-are-clojure-edn-instances-test
  (is (identical? (clojure.edn/read-string "##NaN") (edn/read-string "##NaN")))
  (is (= (clojure.edn/read-string "{:a ##NaN}") (edn/read-string "{:a ##NaN}"))))

(deftest instants-before-the-gregorian-cutover-test
  (doseq [s ["#inst \"0015-05-06\"" "#inst \"1110-12-25T00:00:00Z\"" "#inst \"1582-10-04\""
             "#inst \"1582-10-15T00:00:00+05:00\"" "#inst \"1582-10-16\""]]
    (is (= (clojure.edn/read-string s) (edn/read-string s)) s)))

(defn- rand-nth* [^java.util.Random rnd coll]
  (nth coll (.nextInt rnd (count coll))))

(defn- instant-inputs [^java.util.Random rnd n]
  (let [two   (fn [hi] (format "%02d" (.nextInt rnd hi)))
        parts (fn []
                [(format "%04d" (.nextInt rnd 10000)) "-" (two 14) "-" (two 33) "T"
                 (two 26) ":" (two 61) ":" (two 62)
                 (rand-nth* rnd ["" "." (str "." (apply str (repeatedly (inc (.nextInt rnd 12)) #(.nextInt rnd 10))))])
                 (rand-nth* rnd ["" "Z" (str "+" (two 25) ":" (two 61)) (str "-" (two 25) ":" (two 61))])])]
    (for [_ (range n)
          :let [ps (parts)
                s  (apply str (take (inc (.nextInt rnd (count ps))) (if (< (.nextInt rnd 4) 3) ps (doto (java.util.ArrayList. ^java.util.Collection ps) (java.util.Collections/shuffle rnd)))))]]
      (str "#inst \"" s "\""))))

(deftest same-as-clojure-edn-on-instants-test
  (is (= [] (into [] (comp (keep difference) (take 20))
                  (instant-inputs (java.util.Random. 7) 300000)))))
