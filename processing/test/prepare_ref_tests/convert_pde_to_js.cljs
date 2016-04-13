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
    ;outputs to console.log - hacky but alternative would be to set up an http
    ;server to accept posts - doo doesn't give us access to phantom's file-io api
    (.callPhantom js/window (u/marshal-to-string @ref-tests-js)) 
    (.exit! (object/get js/doo "runner") true)))

(defn- convert-test-callback [test-file-name compiled-tests-div]
  (fn [e]
    (swap! test-count dec)
    (let [target (object/get e "target")
          isSuccess (.isSuccess target)
          text (.getResponseText target)]
      (if (and isSuccess text)
        (let [compiled-test-function (object/get (.compile js/Processing text) "sourceCode")
              result-div (dom/createDom "div" (clj->js {:id test-file-name}) compiled-test-function)]
          (dom/appendChild compiled-tests-div result-div))))
    (if (= 0 @test-count)
      (collect-results-and-exit compiled-tests-div))))

(defn- absolute-uri [file-name]
  (let [location (-> js/window (object/get "location") (object/get "href"))]
    (str/replace location
                 #"doo-index.html" 
                 file-name)))

(defn- convert-tests-callback [compiled-tests-div]
  (fn [e]
    (let [target (object/get e "target")
          isSuccess (.isSuccess target)
          text (.getResponseText target)]
      (if (and isSuccess text)
        (do
          (js/eval text)
          (let [ref-test-filenames (map #(:path %) 
                                          (js->clj js/tests :keywordize-keys true))]
            (reset! test-count (count ref-test-filenames))
            (doseq [test-filename ref-test-filenames] 
              (.send goog.net.XhrIo
                     (absolute-uri test-filename) 
                     (convert-test-callback test-filename compiled-tests-div)))))))))

(defn entry []
  (fn []
    (let [body (-> js/window (object/get "document") (object/get "body"))
          compiled-tests-div (dom/createDom "div" (clj->js {:id "convert-tests"}))]
      (dom/appendChild body compiled-tests-div)
      (.send goog.net.XhrIo 
             (absolute-uri "tests.js") 
             (convert-tests-callback compiled-tests-div)))))

(defn exit []
  (fn [successful?]))
