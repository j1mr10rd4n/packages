(ns prepare-ref-tests.util
  (:require [clojure.string :as str :refer [join split]]))

(defn serialize-to-ascii [s]
  (let [byte-arr #?(:clj (.getBytes s)
                    :cljs (map (fn [c] (.charCodeAt c)) (.split s "")))]
  (join "X" byte-arr)))

(defn deserialize-from-ascii [s]
  (join (map (fn[b] (char #?(:clj (Integer. b)
                             :cljs (int b))))
             (split s #"X"))))

(defn marshal-to-string [ref-tests]
  (str "|START|"
       (join "|||||"
             (map (fn [{:keys [test-name processing-js-code]}] 
                    (join "|||" [test-name (serialize-to-ascii processing-js-code)]))
                  ref-tests))
       "|END|"))

(defn unmarshal-from-string [s]
  (let [ref-tests-js-string ((re-find #"\|START\|(.*)\|END\|" s) 1)
        ref-tests-js (str/split ref-tests-js-string #"\|\|\|\|\|")
        something (atom [])]
    (doseq [ref-test-js ref-tests-js]
      (let [[test-name processing-js-code] (str/split ref-test-js #"\|\|\|")]
        (swap! something conj {:test-name test-name
                               :processing-js-code processing-js-code}))
          )
    @something))