(ns hiposfer.kamal.dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require [com.stuartsierra.component :as component]
            [hiposfer.kamal.core :as core]
            [clojure.spec.test.alpha :as st]
            [expound.alpha :as expound]
            [clojure.spec.alpha :as s]
            [clojure.tools.namespace.repl :as repl]))

(def system nil)

(defn init!
  "Constructs the current development system."
  []
  (alter-var-root #'system
    (constantly (core/system (core/config {:dev false
                                           :join? false})))))

(defn start!
  "Starts the current development system."
  []
  (alter-var-root #'system component/start))

(defn stop!
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system (fn [s] (when s (component/stop s)))))

(defn go!
  "Initializes the current development system and starts it running."
  []
  (stop!)
  (init!)
  (st/instrument)
  (set! s/*explain-out* (expound/custom-printer {:theme :figwheel-theme}))
  (start!))

;; WARN: this will fail if you just ran `lein uberjar` without
;; cleaning afterwards. See
;; https://stackoverflow.com/questions/44246924/clojure-tools-namespace-refresh-fails-with-no-namespace-foo
(defn refresh!
  "reset the system to a fresh state. Prefer using this over go!"
  []
  (stop!)
  (repl/refresh :after 'hiposfer.kamal.dev/go!))

;(reset)

;(set! *print-length* 50)
;(take 10 (:network @(:network (:grid system))))

;(type (:networks (:router system)))
