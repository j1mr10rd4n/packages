(ns processing-ref-tests.convert-to-js
    (:require [processing-js :as p]
              [arbitrary :as ab]
              ;list of provides from the ref test scripts
              ;[ref_tests :as rt]
              [goog.object :as object]))


(def processing-code "class Test {
	Test self;
	Test() { self = this; }
  boolean test() { return this == self; }
}

Test t = new Test();

void setup() {
  size(100,100);
}

void draw() {
  if(t.test()) { background(0,255,0); }
  else { background(255,0,0); }
  exit();
}")


(println (.compile js/Processing processing-code))

(println ">>>>>> CONVERTING PROCESSING TESTS TO JS <<<<<<")
(println  (object/get js/window "location"))

;(.fubit js/window)

(println (object/get (object/get (object/get js/window "document") "head") "innerHTML"))
