(ns run-ref-tests.launcher
  (:require [clojure.string :as str :refer [replace]]
            [goog.object :as object]
            [goog.dom :as dom]))

(enable-console-print!)


(defn replaceSketchSourceCode
  [sketch testName]
  (let [testId (object/get js/window "test_paths_and_ids")
        testFunction (object/get js/window (str ((js->clj testId) testName) "_f"))]
    (object/set sketch "sourceCode" testFunction))
  sketch)


(defn parseEvent [e]
  (let [target (object/get e "target")]
    (into [target] (mapv #(object/get target %) 
                         ["tagName" "id" "className"]))))


(defn test-suite-summary []
  (let [ref-tests-window (object/get js/window "ref-tests-window")
        testCount (-> ref-tests-window (object/get "selectedTests") (object/get "length"))
        passedCount (object/get ref-tests-window "passedCount")
        failedCount (object/get ref-tests-window "failedCount")
        knownFailedCount (object/get ref-tests-window "knownFailedCount")]
  (str "Run: " testCount ", passed: " passedCount ", failed: " failedCount ", knownFailed: " knownFailedCount )))


(defn listen-for-test-finish [e] 
  (let [[target _ id className] (parseEvent e)]
    (if (and (re-find #"\d+" id)
             (re-find #"(passed|failed|knownFailure)" className))
        (println (dom/getRawTextContent (dom/getElementByClass "title" target))))))


(defn listen-for-test-start-suite-end [e]
  (let [[target tagName id _] (parseEvent e)
        parentElement (object/get target "parentElement")]
    ; cancas element with id <test-name>-original inserted at start of test
    (if (= "CANVAS" tagName)
      (if-let [[_ test-name] (re-find #"(.*\.pde)-original" id)]
        (do
          (println "Starting test" test-name)
          (.addEventListener parentElement
                             "DOMSubtreeModified" 
                             listen-for-test-finish))))
    ; 3 divs each with single h3 child are inserted when suite ends
    (if (and (= "DIV" tagName)
             (= 1 (object/get (dom/getElementsByTagNameAndClass "h3" nil target) "length")))
      (let [h3s (dom/getElementsByTagNameAndClass "h3" nil parentElement)]
        (if (= 3 (object/get h3s "length"))
          (do
            (println "SUITE FINISHED!!")
            ;(println (-> js/window (object/get "ref-tests-window") (object/get "document") (object/get "body") (object/get "outerHTML")))
            (println (test-suite-summary))
            ((object/get js/window "exit_runner") true))))))) 


(defn start-tests [ref-tests-window]
  (println "READYTOTEST")
  (let [domHelper (dom/DomHelper. (object/get ref-tests-window "document"))
        results (.getElement domHelper "results")
        test-type-select (.getElement domHelper "test-type")
        test-start (.getElement domHelper "testStart")]
    (.addEventListener results "DOMNodeInserted" listen-for-test-start-suite-end)
    (object/set test-type-select "value" "2D")
    (.onchange test-type-select)
    (.click test-start)))
    

(defn entry []
  (fn []
    (let [location (object/get js/location "href")
          re-doo-index #"doo-index\.html"
          ref-test-index "index.html"
          ref-test-index-location (replace location re-doo-index ref-test-index)]
      (println "Opening:" ref-test-index-location)
      (try
        (let [ref-tests-window (.open js/window ref-test-index-location "ref-tests-index-window")]
          (object/set js/window "ref-tests-window" ref-tests-window)
          (object/set js/window "replaceSketchSourceCode" replaceSketchSourceCode))
        (catch :default e
          (println "Couldn't open ref-tests-window" e)
          e)))

    ;window.open won't load new URL until this block finishes
    (.setTimeout js/window start-tests 10 (-> js/window (object/get "ref-tests-window")))))


(defn exit []
  (fn [successful?]))
