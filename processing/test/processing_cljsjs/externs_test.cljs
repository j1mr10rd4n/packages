(ns processing-cljsjs.externs-test
  (:require [processing-js :as p]
            [goog.object :as object]
            [foo.bar :as whatever])
  (:require-macros [cljs.test :refer [async testing deftest is]]))

(enable-console-print!)

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



(deftest dummy-test []
  ;(println (.compile js/Processing processing-code))
  (println (object/get js/window "location"))
  ;(println (object/get (object/get (object/get js/window "document") "head") "innerHTML"))
  ;(println (object/get (object/get (object/get js/window "document") "body") "innerHTML"))
  (is (= 1 2)))

;(deftest async-test
  ;(async done
    ;(.open js/window "www.bbc.co.uk" (fn [] (println "opened") (is (= 1 333))(done)))))

