(set-env!
 :source-paths #{"test"}
 ;:resource-paths #{"resources"}
 :dependencies '[[cljsjs/boot-cljsjs "0.5.0"  :scope "test"]
                 [doo "0.1.8-SNAPSHOT" :scope "test"]
                 [crisptrutski/boot-cljs-test "0.2.3-SNAPSHOT" :scope "test"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]
                 [danlentz/clj-uuid "0.1.6"]
                 [adzerk/boot-cljs "1.7.228-2" :scope "test"]]) 

(require '[cljsjs.boot-cljsjs.packaging :refer :all]
         '[boot.core :as boot]
         '[doo.core :as doo]
         '[crisptrutski.boot-cljs-test.utils :as cljs-test-utils]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clj-uuid :as uuid]
         '[adzerk.boot-cljs :refer [cljs]]
         '[prepare-ref-tests.util :as util]
         '[run-ref-tests.epsilon-overrides])

(def +lib-version+ "1.4.16")
(def +version+ (str +lib-version+ "-0"))

(task-options!
  pom  {:project     'cljsjs/processing
        :version     +version+
        :description "Javascript port of the Processing visual programming language"
        :url         "http://processingjs.org"
        :scm         {:url "https://github.com/cljsjs/packages"}
        :license     {"MIT" "http://opensource.org/licenses/MIT"}})
 
(deftask package []
  (comp
   (download :url      (str "https://github.com/processing-js/processing-js/archive/v" +lib-version+ ".zip")
             :checksum "62815eedfe60c6f44672795972702482"
             :unzip    true)
   (sift :move {#"^processing-js-([\d\.]*)/processing\.js"      "cljsjs/processing/development/processing.inc.js"
                #"^processing-js-([\d\.]*)/processing\.min\.js" "cljsjs/processing/production/processing.min.inc.js"})
   (sift :include #{#"^cljsjs"})
   (deps-cljs :name "cljsjs.processing")))

(defn fs-metadata 
  ([fileset key value]
   (let [metadata (fs-metadata fileset)
         metadata' (assoc-in metadata [key] value)]
     (with-meta fileset {:metadata metadata'}) ))
  ([fileset key]
    (-> fileset meta :metadata key))
  ([fileset] 
    (fs-metadata fileset identity)))

(defn- copy-dir-contents [ref-tests-dir-path output-dir-path]
  (doseq [f (filter #(not (= (.getPath %) 
                             ref-tests-dir-path)) 
                    (file-seq (java.io.File. ref-tests-dir-path)))]
    (let [source-path (.getPath f)
          dest-path (str/replace source-path (re-pattern ref-tests-dir-path) output-dir-path)
          dest-file (io/file dest-path)]
      (if (.isDirectory f)
        (.mkdir dest-file)
        (io/copy f dest-file)))))

(defn- compiler-opts-for-convert []
    {:optimizations :none
     :verbose true
     :foreign-libs [{:file "deps-src/processing-js-1.4.16/processing.js" 
                     :provides ["processing-js"]}]})

(defn- find-compilation-output-path [fileset namespace]
  (some->> (boot/output-files fileset)
           (filter (comp #{namespace} :path))
           (sort-by :time)
           (last)
           (boot/tmp-file)
           (.getPath)))

(deftask convert-tests-pde-to-js []
  (boot/with-pre-wrap fileset
    (if-let [compilation-output-path (find-compilation-output-path fileset "prepare_ref_tests/convert_pde.js")]
      (let [compilation-output-dir (-> compilation-output-path io/file .getParentFile)
            tmp-main (boot/tmp-dir!)]

        ; copy the ref test index and resources into the folder that the phantom page can find
        (copy-dir-contents "deps-src/processing-js-1.4.16/test/ref" (-> compilation-output-dir .getPath))

        (let [js-env :phantom
              cljs (merge (compiler-opts-for-convert)
                          {:output-to compilation-output-path,
                           :output-dir (str/replace compilation-output-path #".js\z" ".out")})
              opts {:exec-dir compilation-output-dir :debug true}
              {:keys [out exit] :as result} (doo.core/run-script js-env cljs opts)]

          (let [ascii-serialized-tests (util/unmarshal-from-string out)
                converted-tests (map (fn [{:keys [test-name processing-js-code]}] 
                                      {:test-name test-name 
                                       :test-js (util/deserialize-from-ascii processing-js-code)
                                       :test-id (str "var" ((str/split (str (uuid/v1)) #"-") 0))})
                               ascii-serialized-tests)]

            (doseq [{:keys [test-name test-js test-id]} converted-tests]
              (let [js-file (doto (io/file tmp-main (str "prov/" test-name ".js")) io/make-parents)
                    my-fn (str/replace test-js #"(?s)\((function\(\$p\)\s\{.*\})\)" (str "prov." test-id "." test-id "_f = $1"))]
                (spit js-file
                      (str/join "\n" [(str "goog.provide('prov." test-id "');") my-fn]))))


            ; write a js dictionary of test var ids against paths
            (let [test-paths-and-ids (str/join ", " 
                                               (map (fn [{:keys [test-name test-id]}] 
                                                      (str "\"" test-name  "\": " (str "'" test-id "'")))
                                                    converted-tests))]
              (spit (io/file tmp-main "test-paths-and-ids.js")
                    (str "var test_paths_and_ids = {" test-paths-and-ids "};")))

            (-> (fs-metadata fileset :ref-test-js-files-and-ids converted-tests)
                (boot/add-resource tmp-main) 
                boot/commit!)))))))


(defn add-run-ref-tests-ns! [fileset tmp-main suite-ns]
  (let [out-main (cljs-test-utils/ns->cljs-path suite-ns)
        out-file (doto (io/file tmp-main out-main) io/make-parents)
        ref-test-js-files-and-ids (fs-metadata fileset :ref-test-js-files-and-ids)
        ns-spec `(~'ns ~suite-ns
                  (:require [run-ref-tests.launcher :as ~'launcher]
                            [goog.object :as ~'object]
                            [doo.runner :as ~'runner]
                            [~'test-paths-and-vars]
                  ~@(mapv #(vector (symbol (str "prov." (:test-id %)))) 
                          ref-test-js-files-and-ids)
                  ))
        run-exp `(do 
                     ~@(map #(let [vr (symbol (:test-id %))
                                   pvr (symbol (str "prov." vr))
                                   f (str vr "_f")
                                   x (symbol (str pvr "/" f))]
                              `(object/set js/window ~f ~x) 
                              )
                            ref-test-js-files-and-ids)
                     ; need to export exit fn so advanced compilation retains it
                     ~'(object/set js/window "exit_runner" runner/exit!) 
                     (~'runner/set-exit-point! (~'launcher/exit))
                     (~'runner/set-entry-point! (~'launcher/entry)))]
    (info "Writing %s...\n " out-main)
    (println 
        (->> [ns-spec run-exp]
             (map #(with-out-str (clojure.pprint/pprint %)))
             (str/join "\n" )))
    (spit out-file 
        (->> [ns-spec run-exp]
             (map #(with-out-str (clojure.pprint/pprint %)))
             (str/join "\n" )))))

(deftask prep-run-ref-test-scripts []
  (let [out-file "run-ref-tests.js"
        out-id (str/replace out-file #"\.js$" "") 
        suite-ns 'ref-tests.run-tests
        tmp-main (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (boot/empty-dir! tmp-main)
      (info "Writing %s...\n" (str out-id ".cljs.edn"))
      (println (pr-str {:require [suite-ns]}))
      (spit (doto (io/file tmp-main (str out-id ".cljs.edn")) io/make-parents)
            (pr-str {:require [suite-ns]}))
      (add-run-ref-tests-ns! fileset tmp-main suite-ns)
      (-> fileset (boot/add-source tmp-main) boot/commit!))))


(defn- compiler-opts-run [fileset]
  (let [foreign-libs [{:file "test-paths-and-ids.js"
                       :provides ["test-paths-and-vars"]}]
        libs (mapv #(str "prov/" (:test-name %) ".js" )
                   (fs-metadata fileset :ref-test-js-files-and-ids))]
    {:main "ref-tests.run-tests"
     :optimizations :advanced
     :foreign-libs foreign-libs
     :libs libs
     :verbose true
     :externs ["resources/cljsjs/processing/common/processing.ext.js"]}))

; have to wrap cljs becuase the compiler-opts we want to pass won't be known
; until after the previous tasks have run
(deftask wrap-cljs-run []
  (merge-env! :source-paths #{"src" "test"})
  (fn middleware [next-handler]
    (fn handler [fileset]
      (let [compiler-opts (compiler-opts-run fileset)
            cljs-handler (cljs :ids #{"run-ref-tests"}
                               :compiler-options compiler-opts)
            fileset' (atom nil)
            dummy-handler (fn [compiled-fileset] (reset! fileset' compiled-fileset))]
        (println "COMPILER OPTS:" compiler-opts)
        ((cljs-handler dummy-handler) fileset)
        (next-handler @fileset')))))

(defn- replace-processing-js-link [s]
  (str/replace s 
               #"src=\"\/processing\.min\.js\"" 
               "src=\"processing.min.js\""))

(defn- add-sketch-source-swap-function [s]
  (str/replace s
               #"(s = Processing\.compile\(test.code\);)"
               "$1\ns = opener.replaceSketchSourceCode(s, test.name);"))

(deftask run-compiled-ref-tests []
  (boot/with-pre-wrap fileset
    (if-let [js-output-file-path (find-compilation-output-path fileset "run-ref-tests.js")]
      (let [exec-dir (.getParentFile (java.io.File. js-output-file-path))
            exec-dir-path (.getPath exec-dir)
            ref-tests-dir-path "deps-src/processing-js-1.4.16/test/ref"
            ref-tests-dir (java.io.File. ref-tests-dir-path)]
        ; copy the ref test index and resources into the folder that the phantom page can find
        (copy-dir-contents "deps-src/processing-js-1.4.16/test/ref" exec-dir-path)

        ; copy the processing js file - this is kept as an external library
        (io/copy (io/file "deps-src/processing-js-1.4.16/processing.min.js") (io/file (str exec-dir-path "/processing.min.js")))

        ; munge the processing link in index.html
        ; add function that swaps test source from pde for the javascript-converted version
        (let [ref-test-index-path (str exec-dir-path "/index.html")
              ref-test-index-content (slurp (io/file ref-test-index-path))
              munged-content ((comp add-sketch-source-swap-function 
                                    replace-processing-js-link)
                              ref-test-index-content)]
          (spit ref-test-index-path munged-content))


        ; munge the epsilonOverrides in the tests
        (let [ref-test-list-path (str exec-dir-path "/tests.js")
              munged-lines (atom [])]
          (with-open [rdr (io/reader ref-test-list-path)]
            (doseq [line (line-seq rdr)]
              (if-let [[_ path] (re-find #"path: \"(.*?)\"" line)]
                (if-let [epsilon-override (run-ref-tests.epsilon-overrides/for-test path)]
                  (swap! munged-lines conj (str/replace line 
                                                        #"\](?:,\sepsilonOverride:\s\d\.\d+)?\s}" 
                                                        (str "], epsilonOverride: " epsilon-override " }")))
                  (swap! munged-lines conj line))
                  (swap! munged-lines conj line))))
          (spit ref-test-list-path (str/join "\n" @munged-lines)))

        ; go!
        (let [js-env :phantom
              ;js-env :firefox
              cljs (merge (compiler-opts-run fileset)
                          {:output-to js-output-file-path,
                           :output-dir (str/replace js-output-file-path #".js\z" ".out")})
              opts {:exec-dir exec-dir :debug true}
              {:keys [out exit] :as result} (doo.core/run-script js-env cljs opts)])
          
          ))

      fileset))


(deftask doit-wrapped []
  (merge-env! :source-paths #{"test"})
  (set-env! :resource-paths #{"deps-src/processing-js-1.4.16/test/ref/"})
  (comp
    (cljs :ids #{"prepare_ref_tests/convert_pde"}
          :compiler-options (compiler-opts-for-convert))
    (convert-tests-pde-to-js)
    (prep-run-ref-test-scripts)
    (wrap-cljs-run)
    (run-compiled-ref-tests))) 
