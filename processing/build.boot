(set-env!
 :resource-paths #{"resources"}
 :dependencies '[[cljsjs/boot-cljsjs "0.5.0"  :scope "test"]])

(require '[cljsjs.boot-cljsjs.packaging :refer :all])

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
   (download :url      (str "https://github.com/processing-js/processing-js/archive/v" +version+ ".zip")
             :checksum "62815eedfe60c6f44672795972702482"
             :unzip    true)
   (sift :move {#"^processing-js-([\d\.]*)/processing\.js"      "cljsjs/processing/development/processing.inc.js"
                #"^processing-js-([\d\.]*)/processing\.min\.js" "cljsjs/processing/production/processing.min.inc.js"})
   (sift :include #{#"^cljsjs"})
   (deps-cljs :name "cljsjs.processing")))
