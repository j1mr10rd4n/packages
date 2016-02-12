(set-env!
 :resource-paths #{"resources"}
 :dependencies '[[cljsjs/boot-cljsjs "0.5.0"  :scope "test"]
                 [crisptrutski/boot-cljs-test "0.2.2-SNAPSHOT" :scope "test"]
                 [doo "0.1.7-SNAPSHOT" :scope "test"]
                 [org.clojure/clojurescript   "1.7.228"]]) 

(require '[cljsjs.boot-cljsjs.packaging :refer :all]
         '[crisptrutski.boot-cljs-test :refer [test-cljs exit!]])

(def +lib-version+ "1.4.16")
(def +version+ (str +lib-version+ "-0"))

(task-options!
 pom  {:project     'cljsjs/processing
       :version     +version+
       :description "Javascript port of the Processing visual programming language"
       :url         "http://processingjs.org"
       :scm         {:url "https://github.com/cljsjs/packages"}
       :license     {"MIT" "http://opensource.org/licenses/MIT"}}
 test-cljs {:js-env :phantom})

(deftask package []
  (comp
   (download :url      (str "https://github.com/processing-js/processing-js/archive/v" +lib-version+ ".zip")
             :checksum "62815eedfe60c6f44672795972702482"
             :unzip    true)
   (sift :move {#"^processing-js-([\d\.]*)/processing\.js"      "cljsjs/processing/development/processing.inc.js"
                #"^processing-js-([\d\.]*)/processing\.min\.js" "cljsjs/processing/production/processing.min.inc.js"})
   (sift :include #{#"^cljsjs"})
   (deps-cljs :name "cljsjs.processing")))

(deftask test-externs []
  (merge-env! :source-paths #{"test"})
  (test-cljs))
