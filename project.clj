(defproject pixelsquiz "1.0"
  :description "Game engine for the Pixels Camp quiz"
  :url "https://github.com/pixelscamp/pixelscamp-quiz-stage"
  :dependencies [
    [org.clojure/clojure "1.10.1"]
    [org.clojure/core.async "0.4.500"]
    [org.clojure/tools.namespace "0.3.1"]
    [nrepl "0.6.0"]
    [reduce-fsm "0.1.4"]
    [http-kit "2.3.0"]
    [ring "1.7.1"]
    [ring/ring-defaults "0.3.2"]
    [compojure "1.6.1"]
    [org.clojars.torbjornvatn/hidapi "1.1"]
    [simple-time "0.2.1"]
    [clj-time "0.15.2"]  ; pulled in by simple-time
    [cheshire "5.9.0"]
    [org.craigandera/dynne "0.4.1"]
    [incanter/incanter-core "1.9.3"]  ; fix override warning (pulled in by dynne)
  ]
  :main ^:skip-aot pixelsquiz.core
  :target-path ".target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]}}
  :jvm-opts ["-Djava.awt.headless=true"]
  :repl-options {
    :host "127.0.0.1"
    :port 7888
  }
)
