(set-env!
 :source-paths #{"test"}
 :resource-paths #{"resources"}
 :dependencies '[[cljsjs/boot-cljsjs "0.5.0"  :scope "test"]
                 [doo "0.1.7-SNAPSHOT" :scope "test"]
                 [crisptrutski/boot-cljs-test "0.2.2-SNAPSHOT" :scope "test"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374" :scope "test"]
                 [danlentz/clj-uuid "0.1.6" :scope "test"]
                 [adzerk/boot-cljs "1.7.228-1"]
                 [me.raynes/fs "1.4.6" :scope "test"]]) 

(require '[cljsjs.boot-cljsjs.packaging :refer :all]
         '[boot.core :as boot]
         '[doo.core :as doo]
         '[crisptrutski.boot-cljs-test.utils :as cljs-test-utils]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clj-uuid :as uuid]
         '[adzerk.boot-cljs :refer [cljs]]
         '[me.raynes.fs :as fs] 
         '[convert-ref-tests.util :as util]
         '[run-ref-tests.epsilon-overrides]
         '[run-ref-tests.known-failures])

(def +lib-version+ "1.4.16")
(def +version+ (str +lib-version+ "-0"))

(task-options!
  pom  {:project     'cljsjs/processing
        :version     +version+
        :description "Javascript port of the Processing visual programming language"
        :url         "http://processingjs.org"
        :scm         {:url "https://github.com/cljsjs/packages"}
        :license     {"MIT" "http://opensource.org/licenses/MIT"}})


(defonce download-cache-path "download-cache")
(defonce processing-filename (str "processing-js-" +lib-version+))
(defonce processing-file-cache-path (str download-cache-path "/" processing-filename))

(deftask download-and-cache []
  ; avoid unnecessary repeat downloads
  (comp
    (boot/with-pass-thru fileset
      (if (not (fs/exists? processing-file-cache-path))
        (((download :url      (str "https://github.com/processing-js/processing-js/archive/v" +lib-version+ ".zip")
                    :checksum "62815eedfe60c6f44672795972702482"
                    :unzip    true)
          (fn [my-fileset]
            (let [download-dir-path (->> my-fileset
                                         boot/input-files
                                         (boot/by-re [(re-pattern (str "^" processing-filename))])
                                         first
                                         :dir
                                         .getPath)
                  downloaded-path (str download-dir-path "/" processing-filename)]
               (fs/copy-dir (io/file downloaded-path) (io/file processing-file-cache-path))))) 
         fileset)))
    (boot/with-pre-wrap fileset 
      (let [tmp-dir (boot/tmp-dir!)]
        (fs/copy-dir (io/file processing-file-cache-path) tmp-dir)
        (boot/commit! (boot/add-resource fileset tmp-dir))))))

(deftask package []
  (comp
    (download-and-cache)
    (sift :move {#"^processing-js-([\d\.]*)/processing\.js"      "cljsjs/processing/development/processing.inc.js"
                #"^processing-js-([\d\.]*)/processing\.min\.js" "cljsjs/processing/production/processing.min.inc.js"})
    (sift :include #{#"^cljsjs"})))

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
     :foreign-libs [{:file (str processing-filename "/" "processing.js")
                     :provides ["processing-js"]}]})

(defn- find-compilation-output-path [fileset namespace]
  (some->> (boot/output-files fileset)
           (filter (comp #{namespace} :path))
           (sort-by :time)
           (last)
           (boot/tmp-file)
           (.getPath)))

(defonce convert-ref-test-edn-filename "convert_ref_tests/convert_pde")
(defonce convert-ref-test-ns-filename "convert_ref_tests/convert_pde.js")

(defn- converted-ref-test-filename [test-id]
  (str "converted/" test-id ".js"))

(defn- converted-ref-test-function-name [test-id]
  (str test-id "_f"))

(defn- converted-ref-test-fq-name [test-id]
  (str "converted." test-id))

(defn- converted-ref-test-ns-and-function [test-id]
  (str (converted-ref-test-fq-name test-id) "." (converted-ref-test-function-name test-id)))

(defn- goog-provide-string [test-id]
  (str "goog.provide('" (converted-ref-test-fq-name test-id) "');"))

(defn- converted-ref-test-ns [test]
  (converted-ref-test-fq-name (:test-id test)))

(defn- converted-ref-test-global-var [test]
  (converted-ref-test-function-name (:test-id test)))

(defn- converted-ref-test-function [test]
  (str (converted-ref-test-ns test) "/" (converted-ref-test-global-var test)))

(deftask convert-tests-pde-to-js []
  (boot/with-pre-wrap fileset
    (if-let [compilation-output-path (find-compilation-output-path fileset convert-ref-test-ns-filename)]
      (let [compilation-output-dir (-> compilation-output-path io/file .getParentFile)
            exec-dir-path (-> compilation-output-dir io/file .getParentFile .getPath)
            tmp-main (boot/tmp-dir!)]

        (copy-dir-contents (str exec-dir-path "/" processing-filename "/test/ref") (.getPath compilation-output-dir))

        (let [js-env :phantom
              cljs (merge (compiler-opts-for-convert)
                          {:output-to compilation-output-path,
                           :output-dir (str/replace compilation-output-path #".js\z" ".out")})
              opts {:exec-dir compilation-output-dir}
              {:keys [out exit] :as result} (doo.core/run-script js-env cljs opts)]

          (let [ascii-serialized-tests (util/unmarshal-from-string out)
                converted-tests (map (fn [{:keys [test-name processing-js-code]}] 
                                      {:test-name test-name 
                                       :test-js (util/deserialize-from-ascii processing-js-code)
                                       :test-id (str "var" ((str/split (str (uuid/v1)) #"-") 0))})
                               ascii-serialized-tests)]

            (doseq [{:keys [test-name test-js test-id]} converted-tests]
              (let [js-file (doto (io/file tmp-main (converted-ref-test-filename test-id)) io/make-parents)
                    my-fn (str/replace test-js 
                                       #"(?s)\((function\(\$p\)\s\{.*\})\)" 
                                       (str (converted-ref-test-ns-and-function test-id) " = $1"))]
                (spit js-file
                      (str/join "\n" [(goog-provide-string test-id) my-fn]))))


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

(defn write-doo-wrapper [fileset tmp-main suite-ns]
  (let [out-main (cljs-test-utils/ns->cljs-path suite-ns)
        out-file (doto (io/file tmp-main out-main) io/make-parents)
        ref-test-js-files-and-ids (fs-metadata fileset :ref-test-js-files-and-ids)
        ns-spec `(~'ns ~suite-ns
                  (:require [run-ref-tests.launcher :as ~'launcher]
                            [goog.object :as ~'object]
                            [doo.runner :as ~'runner]
                            [~'test-paths-and-vars]
                  ~@(mapv #(vector (symbol (converted-ref-test-ns %))) 
                          ref-test-js-files-and-ids)))
        run-exp `(do 
                     ~@(map #(let [f (converted-ref-test-global-var %)
                                   x (symbol (converted-ref-test-function %))]
                              `(object/set js/window ~f ~x))
                            ref-test-js-files-and-ids)
                     ; need to export exit fn so advanced compilation retains it
                     ~'(object/set js/window "exit_runner" runner/exit!) 
                     (~'runner/set-exit-point! (~'launcher/exit))
                     (~'runner/set-entry-point! (~'launcher/entry)))]
    (spit out-file 
        (->> [ns-spec run-exp]
             (map #(with-out-str (clojure.pprint/pprint %)))
             (str/join "\n" )))))

(deftask prep-run-ref-test-scripts []
  (let [out-file "run-ref-tests.js"
        out-id (str/replace out-file #"\.js$" "") 
        suite-ns 'run-ref-tests.doo-wrapper
        tmp-main (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (boot/empty-dir! tmp-main)
      (spit (doto (io/file tmp-main (str out-id ".cljs.edn")) io/make-parents)
            (pr-str {:require [suite-ns]}))
      (write-doo-wrapper fileset tmp-main suite-ns)
      (-> fileset (boot/add-source tmp-main) boot/commit!))))


(defn- compiler-opts-run [fileset]
  (let [foreign-libs [{:file "test-paths-and-ids.js"
                       :provides ["test-paths-and-vars"]}]
        libs (mapv #(converted-ref-test-filename (:test-id %))
                   (fs-metadata fileset :ref-test-js-files-and-ids))]
    {:main "run-ref-tests.doo-wrapper"
     :optimizations :advanced
     :foreign-libs foreign-libs
     :libs libs
     :externs ["resources/cljsjs/processing/common/processing.ext.js"]}))

; have to wrap cljs becuase the compiler-opts we want to pass won't be known
; until after the previous tasks have run
(deftask wrap-cljs-run []
  (fn middleware [next-handler]
    (fn handler [fileset]
      (let [compiler-opts (compiler-opts-run fileset)
            cljs-handler (cljs :ids #{"run-ref-tests"}
                               :compiler-options compiler-opts)
            fileset' (atom nil)
            dummy-handler (fn [compiled-fileset] (reset! fileset' compiled-fileset))]
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
            exec-dir-path (.getPath exec-dir)]

        ; copy the ref test index and resources into the folder that the phantom page can find
        (copy-dir-contents (str exec-dir-path "/" processing-filename "/test/ref") exec-dir-path)

        ; copy the processing js file - this is kept as an external library
        (io/copy (boot/tmp-file (first (boot/by-re [#"processing\.min\.js$"] (boot/input-files fileset)))) (io/file (str exec-dir-path "/processing.min.js")))

        ; munge the processing link in index.html
        ; add function that swaps test source from pde for the javascript-converted version
        (let [ref-test-index-path (str exec-dir-path "/index.html")
              ref-test-index-content (slurp (io/file ref-test-index-path))
              munged-content ((comp add-sketch-source-swap-function 
                                    replace-processing-js-link)
                              ref-test-index-content)]
          (spit ref-test-index-path munged-content))


        ; munge the epsilonOverrides and knownFailures in the list of tests
        (let [ref-test-list-path (str exec-dir-path "/tests.js")
              munged-lines (atom [])]
          (with-open [rdr (io/reader ref-test-list-path)]
            (doseq [line (line-seq rdr)]
              (let [munged-line (atom line)]
                (if-let [[_ path] (re-find #"path: \"(.*?)\"" line)]
                  (do
                    (if-let [epsilon-override (run-ref-tests.epsilon-overrides/for-test path)]
                      (reset! munged-line (str/replace @munged-line 
                                                       #"\](?:,\sepsilonOverride:\s\d\.\d+)?\s}" 
                                                       (str "], epsilonOverride: " epsilon-override " }"))))
                    
                    (if (contains? run-ref-tests.known-failures/steve path)
                      (reset! munged-line (str/replace @munged-line 
                                                        #"^(.*)" 
                                                        "//$1")))))
                (swap! munged-lines conj @munged-line))))
          (spit ref-test-list-path (str/join "\n" @munged-lines)))

        ; go!
        (let [js-env :phantom
              ;js-env :firefox
              cljs (merge (compiler-opts-run fileset)
                          {:output-to js-output-file-path,
                           :output-dir (str/replace js-output-file-path #".js\z" ".out")})
              opts {:exec-dir exec-dir}
              {:keys [out exit] :as result} (doo.core/run-script js-env cljs opts)])))
      fileset))


(deftask test-externs []
  (comp
    (download-and-cache)
    (cljs :ids #{convert-ref-test-edn-filename}
          :compiler-options (compiler-opts-for-convert))
    (convert-tests-pde-to-js)
    (prep-run-ref-test-scripts)
    (wrap-cljs-run)
    (run-compiled-ref-tests))) 
