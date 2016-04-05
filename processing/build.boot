(set-env!
 :source-paths #{"src"}
 ;:resource-paths #{"resources"}
 :dependencies '[[cljsjs/boot-cljsjs "0.5.0"  :scope "test"]
                 [doo "0.1.8-SNAPSHOT" :scope "test"]
                 [crisptrutski/boot-cljs-test "0.2.3-SNAPSHOT" :scope "test"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]
                 [danlentz/clj-uuid "0.1.6"]
                 [adzerk/boot-cljs "1.7.228-1" :scope "test"]]) 

(require '[cljsjs.boot-cljsjs.packaging :refer :all]
         '[boot.core :as boot]
         '[doo.core :as doo]
         '[crisptrutski.boot-cljs-test :refer [prep-cljs-tests test-cljs exit!]]
         '[crisptrutski.boot-cljs-test.utils :as cljs-test-utils]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clj-uuid :as uuid]
         '[adzerk.boot-cljs :refer [cljs]]
         '[prepare-ref-tests.util :as util]
         )

(def +lib-version+ "1.4.16")
(def +version+ (str +lib-version+ "-0"))

(task-options!
 pom  {:project     'cljsjs/processing
       :version     +version+
       :description "Javascript port of the Processing visual programming language"
       :url         "http://processingjs.org"
       :scm         {:url "https://github.com/cljsjs/packages"}
       :license     {"MIT" "http://opensource.org/licenses/MIT"}}
)

(deftask package []
  (comp
   (download :url      (str "https://github.com/processing-js/processing-js/archive/v" +lib-version+ ".zip")
             :checksum "62815eedfe60c6f44672795972702482"
             :unzip    true)
   (sift :move {#"^processing-js-([\d\.]*)/processing\.js"      "cljsjs/processing/development/processing.inc.js"
                #"^processing-js-([\d\.]*)/processing\.min\.js" "cljsjs/processing/production/processing.min.inc.js"})
   (sift :include #{#"^cljsjs"})
   (deps-cljs :name "cljsjs.processing")))

(defn- parse-test-pde [file]
  (let [test-file (:path file)
        content (-> file boot/tmp-file slurp)]
    (if-let [matches (re-find #"(?s)^//\[([^\]]+)\]([^\n]+)\n(.*)" content)]
      (if (= 4 (count matches))
        (let [dimensions (matches 1)
              pixels-string (matches 2)
              processing-code (matches 3)
              dims (str/split dimensions #",")
              width (Integer/parseInt (dims 0))
              height (Integer/parseInt (dims 1))
              pixels (map #(Integer/parseInt (re-find #"\d+" %)) (str/split pixels-string #","))
              is-3d? (boolean (re-find #"size\(\s*\d+\s*\,\s*\d+\s*\,\s*(OPENGL|P3D)\s*\);" processing-code))
              var-id (str "var_" ((str/split (str (uuid/v1)) #"-") 0))]
          {:var-id var-id
           :test-file test-file
           :width width
           :height height
           :pixels-string pixels-string
           :processing-code (util/serialize-to-ascii processing-code)
           :is-3d is-3d?})))))

(defn- map->jsobj [m]
  (str "{"
       (str/join ", "
                 (map (fn [[key val]] (str (str/replace (name key) #"-" "_") ": " 
                                      (if (instance? String val)
                                        (str "\"" val "\"") 
                                        val)))
                      m))
       "}"))

(defn fs-metadata 
  ([fileset key value]
   (let [metadata (fs-metadata fileset)
         metadata' (assoc-in metadata [key] value)]
     (with-meta fileset {:metadata metadata'}) ))
  ([fileset key]
    (-> fileset meta :metadata key))
  ([fileset] 
    (fs-metadata fileset identity)))

(deftask extract-ref-test-data []
  (set-env! :resource-paths #{"deps-src/processing-js-1.4.16/test/ref/"})

    (boot/with-pre-wrap fileset
      ; remove the resource pdes
      (let [test-pde-files (->> (boot/user-files fileset)
                                (boot/by-ext '[".pde"])
                                (boot/not-by-re '[#"^resource\spde/"]))
            test-pde-file-data (map parse-test-pde test-pde-files)
            tmp-dir (boot/tmp-dir!)
            test-pde-js-files (atom [])]
        (boot/empty-dir! tmp-dir)
        (doseq [{:keys [var-id test-file pixels-string width height is-3d] :as ref-test} test-pde-file-data]
          (let [filename (str/replace test-file #"\.pde$" ".pde.js")
                js (str "var " var-id " = " (map->jsobj ref-test) ";")
                foreign-libs-entry {:file filename :provides [var-id] :width width :height height :is-3d is-3d :pixels-string pixels-string}]
            (swap! test-pde-js-files conj foreign-libs-entry)
            (spit (io/file tmp-dir filename) js)))

        (let [ref-test-var-ids (map #(str "'" (:var-id %) "'") test-pde-file-data)
              ref-test-js-arr (str "var ref_test_ids = [" (str/join ", " ref-test-var-ids) "]")]
          (println "adding file:  tests_var_ids.js ")
          (swap! test-pde-js-files conj {:file "tests_var_ids.js" :provides ["tests_var_ids"]})
          (spit (io/file tmp-dir "tests_var_ids.js") ref-test-js-arr))
        
        (-> (fs-metadata fileset
                         :test-pde-js-filenames-provides
                         @test-pde-js-files) 
            (boot/add-source tmp-dir) 
            boot/commit!))))

(defn add-compile-pde-ns! [fileset tmp-main suite-ns]
  (let [out-main (cljs-test-utils/ns->cljs-path suite-ns)
        out-file (doto (io/file tmp-main out-main) io/make-parents)
        foreign-libs (fs-metadata fileset :test-pde-js-filenames-provides)
        ns-spec `(~'ns ~suite-ns
                  (:require [prepare-ref-tests.convert-pde-to-js :as ~'convert]
                            [doo.runner :as ~'runner]
                  ~@(mapv #(vector (symbol (get-in % [:provides 0]))) foreign-libs)))
        ;run-exp `(~'convert/go)] ; doo needs to have entry point set instead of "main"
        run-exp `(do (~'runner/set-exit-point! (~'convert/exit)) (~'runner/set-entry-point! (~'convert/entry)))]
    (info "Writing %s...\n " out-main)
    (println 
        (->> [ns-spec run-exp]
             (map #(with-out-str (clojure.pprint/pprint %)))
             (str/join "\n" )))
    (spit out-file 
        (->> [ns-spec run-exp]
             (map #(with-out-str (clojure.pprint/pprint %)))
             (str/join "\n" )))))

(deftask prep-compile-pde-scripts []
  (let [out-file "compile-pde.js"
        out-id (str/replace out-file #"\.js$" "") 
        suite-ns 'ref-tests.compile-pde
        tmp-main (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (boot/empty-dir! tmp-main)
      (info "Writingz %s...\n" (str out-id ".cljs.edn"))
      (println (pr-str {:require [suite-ns]}))
      (spit (doto (io/file tmp-main (str out-id ".cljs.edn")) io/make-parents)
            (pr-str {:require [suite-ns]}))
      (add-compile-pde-ns! fileset tmp-main suite-ns)
      (-> fileset (boot/add-source tmp-main) boot/commit!)
      )
    )
  )


(defn add-run-ref-tests-ns! [fileset tmp-main suite-ns]
  (let [out-main (cljs-test-utils/ns->cljs-path suite-ns)
        out-file (doto (io/file tmp-main out-main) io/make-parents)
        ref-test-js-files-and-ids (fs-metadata fileset :ref-test-js-files-and-ids)
        ns-spec `(~'ns ~suite-ns
                  (:require [run-ref-tests.run-tests :as ~'run-ref-tests]
                            [doo.runner :as ~'runner]
                  ~@(mapv #(vector (symbol (:test-id %))) ref-test-js-files-and-ids)))
        ;run-exp `(~'convert/go)] ; doo needs to have entry point set instead of "main"
        run-exp `(do (~'runner/set-exit-point! (~'run-ref-tests/exit)) (~'runner/set-entry-point! (~'run-ref-tests/entry)))]
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

(defn- compiler-opts-prep [fileset]
  (let [foreign-libs (merge (fs-metadata fileset :test-pde-js-filenames-provides)
                            {:file "deps-src/processing-js-1.4.16/processing.js" 
                                 :provides ["processing-js"]})]
    {:main "ref-tests.compile-pde"
     :optimizations :none
     :foreign-libs foreign-libs}))

; have to wrap cljs becuase the compiler-opts we want to pass won't be known
; until after the previous tasks have run
(deftask wrap-cljs-prep []
  (merge-env! :source-paths #{"src" "test"})
  (fn middleware [next-handler]
    (fn handler [fileset]
      (let [compiler-opts (compiler-opts-prep fileset)
            cljs-handler (cljs :ids #{"compile-pde"}
                               :compiler-options compiler-opts)
            fileset' (atom nil)
            dummy-handler (fn [compiled-fileset] (reset! fileset' compiled-fileset))]
        ((cljs-handler dummy-handler) fileset)
        (next-handler @fileset')))))

(defn- compiler-opts-run [fileset]
  (let [ref-test-libs (mapv (fn [{:keys [file test-id]}] {:file (str/replace file #"\.pde\.js" ".js") :provides [test-id]}) 
                           (fs-metadata fileset :ref-test-js-files-and-ids))
        foreign-libs (into ref-test-libs
                           [{:file "deps-src/processing-js-1.4.16/processing.js" 
                             :provides ["processing-js"]}
                            {:file "deps-src/processing-js-1.4.16/test/ref/tests.js"
                             :provides ["original-list-of-tests"]}
                            {:file "run_ref_tests/test_functions.js"
                             :provides ["test-functions"]}])]
    {:main "ref-tests.run-tests"
     ;:optimizations :none
     :optimizations :advanced
     :foreign-libs foreign-libs}))

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
        ((cljs-handler dummy-handler) fileset)
        (next-handler @fileset')))))

(deftask run-compile-pde-scripts []
  (boot/with-pre-wrap fileset
    (if-let [path (some->> (boot/output-files fileset)
                           (filter (comp #{"compile-pde.js"} :path))
                           (sort-by :time)
                           (last)
                           (boot/tmp-file)
                           (.getPath))]
      (let [dir (.getParentFile (java.io.File. path))
            js-env :phantom
            cljs (merge (compiler-opts-prep fileset)
                        {:output-to path,
                         :output-dir (str/replace path #".js\z" ".out")})
            opts {:exec-dir dir :debug true}
            {:keys [out exit] :as result} (doo.core/run-script js-env cljs opts)]
        (let [compiled-ref-tests (util/unmarshal-from-string out)
              ref-test-foreign-libs (fs-metadata fileset :test-pde-js-filenames-provides)
              compiled-to-collect (atom [])]
         (doseq [{:keys [ref-test-id processing-js-code]} compiled-ref-tests]
           (let [ref-test-as-js (util/deserialize-from-ascii processing-js-code)
                 original (first (filter (fn [{:keys [file provides]}] (= (provides 0) ref-test-id)) ref-test-foreign-libs))
                 merged (merge original {:test-js (util/deserialize-from-ascii processing-js-code)})]
             (swap! compiled-to-collect conj merged)))
        (fs-metadata fileset :test-pde-js-filenames-provides @compiled-to-collect))))))

(deftask run-compiled-ref-tests []
  (boot/with-pre-wrap fileset
    (if-let [path (some->> (boot/output-files fileset)
                           (filter (comp #{"run-ref-tests.js"} :path))
                           (sort-by :time)
                           (last)
                           (boot/tmp-file)
                           (.getPath))]
      (do
        ; have to copy the pde and test resources into the folder that the phantom page can find
        (let [exec-dir (.getParentFile (java.io.File. path))
              exec-dir-path (.getPath exec-dir)
              ref-tests-dir-path "deps-src/processing-js-1.4.16/test/ref"
              ref-tests-dir (java.io.File. ref-tests-dir-path)]
          ;file-seq includes the directory itself so filter that out
          (doseq [f (filter #(not (= (.getPath %) ref-tests-dir-path)) (file-seq ref-tests-dir))]
            (let [source-path (.getPath f)
              dest-path (str/replace source-path (re-pattern ref-tests-dir-path) exec-dir-path)
              dest-file (io/file dest-path)]
              (if (.isDirectory f)
                (.mkdir dest-file)
                (io/copy f dest-file)))))
        
        (let [dir (.getParentFile (java.io.File. path))
              js-env :phantom
              ;js-env :firefox
              cljs (merge (compiler-opts-run fileset)
                          {:output-to path,
                           :output-dir (str/replace path #".js\z" ".out")})
              opts {:exec-dir dir :debug true}
              {:keys [out exit] :as result} (doo.core/run-script js-env cljs opts)])))

      fileset))

; this is actually tansferrig the metaadata across between the filesets
(defn- write-test-js-files 
  ([input-fileset output-fileset]
   (let [ref-tests-data (fs-metadata input-fileset 
                                     :test-pde-js-filenames-provides)
         ref-tests-as-js (atom [])
         tmp-main        (boot/tmp-dir!)]
     (doseq [{file :file [test-id] :provides test-js :test-js pixels-string :pixels-string height :height width :width is-3d? :is-3d} ref-tests-data]
       (let [js-file       (doto (io/file tmp-main (str/replace file #"\.pde\.js" ".js")) io/make-parents)
             var-declare   (str test-id " = {};")
             ; compiling pde->js creates IIFEs so need to wrap them in a function for later eval
             ;test-function (str test-id ".testFunction = function() { return " test-js ";};")
             ;try without
             test-function (str test-id ".testFunction = " test-js ";")

             ;test-function (str test-id ".testFunction = function() { return ( function(x) { console.log('MIAOW'); } ) };")
             pixels (str test-id ".pixels = [" pixels-string "];")
             height (str test-id ".height = " height ";")
             width (str test-id ".width = " width ";")
             is-3d? (str test-id ".is3D = " is-3d? ";")]
         (swap! ref-tests-as-js conj {:file file :test-id test-id})
         (spit js-file
               (str/join "\n" [var-declare test-function pixels height width is-3d?]))))

     ; write a js dictionary of test var ids against paths
     (let [test-paths-and-ids (str/join ", " 
                                        (map (fn [{:keys [file test-id]}] (str "\"" (str/replace file #"\.pde\.js" ".pde") "\": " (str "'" test-id "'")))
                                             @ref-tests-as-js))
           var-test-paths-and-ids (str "var test_paths_and_ids = {" test-paths-and-ids "};")]
       (swap! ref-tests-as-js conj {:file "test-paths-and-ids.js" :test-id "test-paths-and-ids"})
       (spit (doto (io/file tmp-main "test-paths-and-ids.js"))
             var-test-paths-and-ids))

     (-> (fs-metadata output-fileset :ref-test-js-files-and-ids @ref-tests-as-js)
         (boot/add-resource tmp-main) 
         boot/commit!)))
  ([fileset]
   (write-test-js-files fileset fileset)))

(deftask prep-compiled-test-sources []
  (set-env! :resource-paths #{"deps-src/processing-js-1.4.16/test/ref/"})
  (boot/with-pre-wrap fileset
    (write-test-js-files fileset))) 

(deftask doit []
  (comp
    (extract-ref-test-data)
    (prep-compile-pde-scripts)
    (wrap-cljs-prep)
    (run-compile-pde-scripts)
    (prep-compiled-test-sources)
    (show "-f")
    ))

(deftask doit-wrapped []
  (set-env! :resource-paths #{"deps-src/processing-js-1.4.16/test/ref/"})
  (comp 
  (fn middleware [next-handler]
    (fn handler [fileset]
      (let [fileset-atom (atom fileset)
            wrapped-tasks (comp (extract-ref-test-data)
                                (prep-compile-pde-scripts)
                                (wrap-cljs-prep)
                                (run-compile-pde-scripts))
            wrapping-handler (fn [fileset] (reset! fileset-atom (write-test-js-files fileset @fileset-atom)))]
        ((wrapped-tasks wrapping-handler) fileset)
        (next-handler @fileset-atom))))
  ;(fn middleware [next-handler]
    ;(fn handler [fileset]
      ;(next-handler fileset)))
    (prep-run-ref-test-scripts)
    (wrap-cljs-run)
    (run-compiled-ref-tests)
  ))


(deftask wrap-test-cljs []
  (merge-env! :source-paths #{"test"})
  (fn middleware [next-handler]
    (fn handler [fileset]
      (let [foreign-libs (fs-metadata fileset :test-pde-js-filenames-provides)
            compiler-opts (compiler-opts-prep fileset)
            test-cljs-handler (test-cljs :js-env :phantom
                                         :namespaces #{"prepare-ref-tests*"}
                                         ;:suite-ns 'convert-it-with-doo
                                         ;:optimizations :none
                                         :cljs-opts {:foreign-libs foreign-libs}
                                         :update-fs? true)
            fileset' (atom nil)
            dummy-handler (fn [compiled-fileset] (reset! fileset' compiled-fileset))]
        ((test-cljs-handler dummy-handler) fileset)
        (next-handler @fileset')))))

(deftask dooit []
  (comp
    (extract-ref-test-data)
    (wrap-test-cljs)
    (show "-f")))

(deftask test-externs []
  (merge-env! :source-paths #{"src" "test"})
  (task-options!
    test-cljs (fn [{{foreign-libs :foreign-libs} :cljs-opts :as options}]
                    (let [foreign-libs-arr (if foreign-libs foreign-libs [])
                          foreign-libs' (conj foreign-libs-arr 
                                              {:file "deps-src/processing-js-1.4.16/processing.js" :provides ["processing-js"]})]
                    (merge options {:cljs-opts {:foreign-libs foreign-libs'}}))))
  ;(task-options! test-cljs {:js-env :phantom :cljs-opts { :foreign-libs [{:file "deps-src/processing-js-1.4.16/processing.js" 
                                                                          ;:provides ["processing-js"]}]}})
  (comp
  ;(prep-cljs-tests)
  (test-cljs)
  ;(with-pre-wrap fileset
    ;(println (boot/input-files fileset))
    ;fileset)
  (show "-f")))

