(ns run-ref-tests.run-tests
  (:require [clojure.string :as str :refer [join replace split]]
            [goog.object :as object]
            [goog.events :as events]
            [goog.dom :as dom]
            [cljs.core.async :refer [put! chan <! close!]]
            [test-functions]
            )
  (:require-macros [cljs.core.async.macros :refer [go]]))

; see http://stackoverflow.com/questions/23616019/why-arent-nodelist-htmlcollection-seqable
(extend-type js/NodeList
  ISeqable
  (-seq [array] (array-seq array 0)))

(enable-console-print!)

(defonce click-events-channel (chan))

(defn listen [chan el type]
  (events/listen el type
    (fn [e] (put! chan e))))

(defonce corrected-epsilon-values
  {"text-font-fromfile.pde" 0.23
   "arc-fill-crisp.pde" 0.10})

(defn epsilon-override [test original-value]
  (or (corrected-epsilon-values test)
      original-value))

(defn entry []
  (fn []
    (let [body (object/get (object/get js/window "document") "body")
          head (object/get (object/get js/window "document") "head")
          processing-script (dom/createDom "script")
          tests-script (dom/createDom "script")]
      (dom/appendChild head processing-script)
      (object/set processing-script "type" "text/javascript")
      (object/set processing-script "src" "processing.min.js")
      (dom/appendChild head tests-script)
      (object/set tests-script "onload" #(.log js/console "WHEEEE!"))
      (object/set tests-script "type" "text/javascript")
      (object/set tests-script "src" "tests.js")
      (println (object/get head "outerHTML")))
      

    ;(let [ref-tests (map #(js->clj % :keywordize-keys true) js/tests)
          ;test-paths-and-ids (js->clj js/test_paths_and_ids)
          ;filtered-tests (filter #(contains? test-paths-and-ids (:path %)) ref-tests)
          ;test-index (atom 0)
          ;test-count (count filtered-tests)]
      ;(js/prepareResults test-count)
      ;(doseq [{:keys [path knownFailureTicket epsilonOverride]} filtered-tests]
        ;(let [test-var-id (test-paths-and-ids path)
              ;known-failure? (not (nil? knownFailureTicket))
              ;ref-test-from-pde (js->clj (object/get js/window test-var-id) :keywordize-keys true)
              ;test-function (ref-test-from-pde :testFunction)
              ;width (ref-test-from-pde :width)
              ;height (ref-test-from-pde :height)
              ;pixels (ref-test-from-pde :pixels)
              ;is-3d? (ref-test-from-pde :is3D)]
          ;(js/runTest (clj->js {:index @test-index
                                ;:id test-var-id 
                                ;:name path
                                ;:testFunction test-function
                                ;:pixels pixels
                                ;:width width
                                ;:height height
                                ;:knownFailure known-failure?
                                ;:epsilonOverride (epsilon-override path epsilonOverride)
                                ;:is3D is-3d?}))
          ;(swap! test-index inc))))

    ;(doseq [result (dom/getElementsByClass "result")]
      ;(listen click-events-channel result "click"))

    ;(let [tests-running (atom true)]
      ;(go
        ;(while @tests-running 
          ;(let [click-event (<! click-events-channel)]
            ;(println "GOTCLICK!!!! " click-event)
            ;(if (js/stillRunningTests)
              ;(println "STILL GOT TESTS RUNNING!")
              ;(do
                ;(println "Run: " (object/get js/window "tl") ", Passed: " (object/get js/window "passedCount") ", Failed: " (object/get js/window "failedCount") ", Failed-3D: " (object/get js/window "failedCount3D") ", Expected-Failures: " (object/get js/window "knownFailedCount"))
                ;(println (object/get (object/get js/document "head") "innerHTML"))
                ;(println (object/get (object/get js/document "body") "innerHTML"))
                ;(reset! tests-running false)
                ;;(.exit! (object/get js/doo "runner") true)
                ;))))))

    ))

(defn exit []
  (fn [successful?]))
