(ns run-ref-tests.known-failures)

; these fail because the compiled code uses the addMethod method to dynamically
; declare methods - the method names are strings so don't get munged by the google
; closure compiler when advanced compilation is used
; could maybe declare exports for the added methods?
(defonce steve #{ "test-this.pde"
                  "multiple-particle-systems.pde"
                  "bouncy-bubbles.pde"
                  "composite-objects.pde"
                  "flocking.pde"
                  "inheritence.pde"
                  "koch.pde"
                  "simple-particle-system.pde"
                  "spore1.pde"
                  "wolfram.pde"
                  "neighborhood.pde" })
