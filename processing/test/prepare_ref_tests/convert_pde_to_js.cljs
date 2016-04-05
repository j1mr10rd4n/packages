(ns prepare-ref-tests.convert-pde-to-js
  (:require [clojure.string :as str :refer [join replace split]]
            [goog.object :as object]
            [processing-js :as p]
            [prepare-ref-tests.util :as u]))

(enable-console-print!)

(defn entry []
  (fn []
    (println "ENTRY RUNNING>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    (let [ref-tests-js (atom [])
          ref-test-ids (object/get js/window "ref_test_ids")]
      (doseq [ref-test-id ref-test-ids] 
        (let [ref-test-obj (object/get js/window ref-test-id)
              ref-test-pde (object/get ref-test-obj "processing_code")
              sketch-source-code (u/deserialize-from-ascii ref-test-pde)
              sketch (.compile js/Processing sketch-source-code)
              sketch-source-code-js (object/get sketch "sourceCode")
              sketch-options (object/get sketch "options")
              sketch-params (object/get sketch "params")
              sketch-image-cache (object/get sketch "imageCache")
              images (into [] (keys (js->clj (object/get sketch-image-cache "images"))))]
          (swap! ref-tests-js conj {:ref-test-id ref-test-id
                                    :processing-js-code sketch-source-code-js
                                    :images images})))
      (.callPhantom js/window (u/marshal-to-string @ref-tests-js)) ;outputs to console.log
      (.exit! (object/get js/doo "runner") true))))

(defn exit []
  (fn [successful?]))
