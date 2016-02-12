 (ns convert-ref-tests.doo-wrapper
 (:require
  [convert-ref-tests.convert-pde-to-js :as convert]
  [doo.runner :as runner]))

(do
 (runner/set-exit-point! (convert/exit))
 (runner/set-entry-point! (convert/entry)))
