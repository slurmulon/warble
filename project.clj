(defproject warble "0.1.0-SNAPSHOT"
  :description "ABC-inspired notation for representing musical tracks and loops with a focus on readability and productivity"
  :url "https://github.com/slurmulon/warble"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [instaparse "1.4.3"]]
  :main ^:skip-aot warble.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
