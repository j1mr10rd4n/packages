(ns foo.bar
  (:require [goog.dom :as dom]
            [goog.object :as object]))

(enable-console-print!)

(println ">>>>>>>>>>>>>>>>>>>> I GOT RUN!")

(let [dh (dom/DomHelper. (object/get js/window "document"))
      html (.item (.getElementsByTagNameAndClass dh "html"))
      body (.createElement dh "body")
      p (.createElement dh "p")]

  (.setTextContent dh p "pawooga")
  (.appendChild dh body (.createElement dh "p"))
  (.appendChild dh html body)

  ;(println "html is "  (.getOuterHtml dh html))
  ;(println (.innerHTML (.item html)))
  )


