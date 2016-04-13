(ns prepare-ref-tests.convert-pde-to-js
  (:require [clojure.string :as str :refer [join replace split]]
            [goog.object :as object]
            [goog.dom :as dom]
            [goog.net.XhrIo]
            [processing-js :as p]
            [prepare-ref-tests.util :as u]))

(enable-console-print!)

(extend-type js/HTMLCollection
  ISeqable
  (-seq [array] (array-seq array 0)))

(defonce test-count (atom 0))

(defn collect-results-and-exit [compiled-tests-div]
  (let [ref-tests-js (atom [])]
    (doseq [div (dom/getChildren compiled-tests-div)]
      (swap! ref-tests-js conj {:test-name (object/get div "id")
                               :processing-js-code (object/get div "innerText")})) 
    (.callPhantom js/window (u/marshal-to-string @ref-tests-js)) ;outputs to console.log
    (.exit! (object/get js/doo "runner") true)))


(defn- conv-callback [test-file-name compiled-tests-div]
  (fn [e]
    (swap! test-count dec)
    (let [target (object/get e "target")
          isSuccess (.isSuccess target)
          text (.getResponseText target)]
      (if (and isSuccess text)
        (let [compiled-test-function (object/get (.compile js/Processing text) "sourceCode")
              result-div (dom/createDom "div" (clj->js {:id test-file-name}) compiled-test-function)]
          (dom/appendChild compiled-tests-div result-div)        
        
        )))
    (if (= 0 @test-count)
      (collect-results-and-exit compiled-tests-div)
    )))


(defn entry []
  (fn []
    (println "ENTRY RUNNING>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    (let [ref-tests-js (atom [])
          ref-test-ids (object/get js/window "ref_test_ids")
          location (-> js/window (object/get "location") (object/get "href"))
          body (-> js/window (object/get "document") (object/get "body"))
          compiled-tests-div (dom/createDom "div" (clj->js {:id "compiled-tests"}))]
      (reset! test-count (count ref-test-ids))
      (dom/appendChild body compiled-tests-div)
      (doseq [ref-test-id ref-test-ids] 
        (let [ref-test-obj (object/get js/window ref-test-id)
              ref-test-pde (object/get ref-test-obj "processing_code")
              sketch-source-code (u/deserialize-from-ascii ref-test-pde)
              sketch (.compile js/Processing sketch-source-code)
              sketch-source-code-js (object/get sketch "sourceCode")
              sketch-options (object/get sketch "options")
              sketch-params (object/get sketch "params")
              sketch-image-cache (object/get sketch "imageCache")
              images (into [] (keys (js->clj (object/get sketch-image-cache "images"))))
              test-file-name (object/get ref-test-obj "test_file")
              my-xhr (.send goog.net.XhrIo (str/replace location #"doo-index.html" test-file-name) (conv-callback test-file-name compiled-tests-div))]
          ;(swap! ref-tests-js conj {:ref-test-id ref-test-id
                                    ;:processing-js-code sketch-source-code-js
                                    ;:images images})
                                    ))
      ;(.callPhantom js/window (u/marshal-to-string @ref-tests-js)) ;outputs to console.log
      )))

(defn exit []
  (fn [successful?]))
