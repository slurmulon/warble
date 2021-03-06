(ns ^:eftest/synchronized bach.integration-test
  (:require #?@(:clj [[clojure.test :refer [deftest is testing]]]
                :cljs [[cljs.test :refer-macros [deftest is testing run-tests]]
                       [bach.crypto]])
            [instaparse.core :as insta]
            [hiccup-find.core :refer [hiccup-find]]
            [bach.compose :as compose]
            [bach.ast :as ast]
            [bach.track :as track]))

(def id-counter (atom 0))
(def next-id! (fn [_] (swap! id-counter inc)))
(def clear! #(reset! id-counter 0))
(def norm #?(:clj identity :cljs clj->js))

(def fixture-bach-a
  "@Tempo = 150

  :a = chord('A7')
  :e = chord('E6')
  :g = chord('Gm')
  :f = chord('F#')

  :part-a = 3 of [
    3 -> { :a, scale('A dorian') }
    2 -> :e
    when 3 do { 1 -> :g }
  ]

  :part-b = 2 of [
    when 1 do {
      2 -> :a
      1 -> chord('Z')
    }
    1 -> :f
    1 -> :e
  ]

  ## Look a comment

  play! 2 of [:part-a :part-b]
  ")

(def fixture-bach-b
  "@Meter = 3|4
  @Tempo = 83

  :D = Chord('Dm')
  :G = Chord('G7')
  :C = Chord('Cmaj7')
  :F = Chord('Fmaj7')
  :B = Chord('Bm7b5')
  :E = Chord('E7')
  :A = Chord('A7')

  play! [
    4 of [
      3/4 -> {
        :D
        Scale('A aeolian')
      }
      3/4 -> :G
      3/4 -> :C
      3/4 -> :F
      3/4 -> :B
      3/4 -> :E
      3/4 -> :A
    ]

    3/4 -> :A
    3/4 -> :E
    3/4 -> :A
    3/4 -> :D
    3/4 -> :F
    3/4 -> :E
    2 * 3/4 -> :A
  ]")

(def fixture-bach-c
  "@Meter = 6|8
  @Tempo = 166

  :D = Chord('Dm')
  :G = Chord('G7')
  :C = Chord('Cmaj7')
  :F = Chord('Fmaj7')
  :B = Chord('Bm7b5')
  :E = Chord('E7')
  :A = Chord('A7')

  :part-a = [
    bar -> {
      :D
      Scale('A aeolian')
    }
    bar -> :G
    bar -> :C
    bar -> :F
    bar -> :B
    bar -> :E
  ]

  play! [
    3 of [
      :part-a
      bar -> Chord('Am')
      bar -> :A
    ]

    :part-a

    bar -> Chord('Am')
    bar -> :E
    bar -> :A
    bar -> :D
    bar -> :F
    bar -> :E
    2 * bar -> Chord('Am')
  ]")

(def fixture-bach-d
  "@Tempo = 80

  play! [
    4 of [
      3/8 -> {
        Scale('E aeolian')
        Chord('Em')
      }
      5/8 -> Chord('Am')
      3/8 -> Chord('Bm')
      5/8 -> Chord('Cmaj7')
    ]

    2 of [
      1 -> Chord('Am')
      3/4 -> Chord('A')
      1/4 -> Chord('B')
      when 1 do { 2 -> Chord('E') }
      when 2 do { 2 -> Chord('B7b13') }
    ]
  ]")

(def fixture-bach-e
  ":a = stub('a')
  :b = stub('b')
  :c = stub('c')
  :e = stub('e')
  :f = stub('f')
  :g = stub('g')
  :h = stub('h')
  :i = stub('i')
  :j = stub('j')

  play! [
    8 of [
      2 -> :a
      8 * 4n -> :b
      when { 1 4 7 } do { 1 -> :c }
      when even? do { 1 -> :e }
      when 6..8 do { 1 -> :f }
      when !{ 1 last? } do { 1 -> :g }
      when [ even? !{2 4} ] do { 3 -> :h }
      when { gte? 5 lt? 3 } do { bar -> :i }
      when { odd? factor? 4 } do { 2 -> :j }
    ]
  ]")

(deftest ^:eftest/synchronized compose
  (testing "basic"
    (with-redefs [compose/uid (memoize next-id!)]
      (clear!)
      (let [actual (bach.tree/cast-tree sequential? vec (compose/provision fixture-bach-a))
            want {:iterations 2,
                  :headers {:tempo 150, :meter [4 4]},
                  :units
                  {:beat {:step 1, :pulse (/ 1 4)},
                   :bar {:step 1, :pulse 4},
                   :time {:step 1600.0, :pulse 400.0, :bar 1600.0}},
                  :metrics {:min 1, :max 3, :total 21},
                  :elements
                  {:scale {"2" {:value "A dorian", :props []}},
                   :chord
                   {"1" {:value "A7", :props []},
                    "3" {:value "E6", :props []},
                    "4" {:value "Gm", :props []},
                    "5" {:value "Z", :props []},
                    "6" {:value "F#", :props []}}},
                  :steps
                  [[[0 "chord.1" "scale.2"] ["chord.1" "scale.2"] ["chord.3"]]
                   [[0 "chord.1" "scale.2"] [] []]
                   [[0 "chord.1" "scale.2"] [] []]
                   [[1 "chord.3"] ["chord.3"] ["chord.1" "scale.2"]]
                   [[1 "chord.3"] [] []]
                   [[2 "chord.1" "scale.2"] ["chord.1" "scale.2"] ["chord.3"]]
                   [[2 "chord.1" "scale.2"] [] []]
                   [[2 "chord.1" "scale.2"] [] []]
                   [[3 "chord.3"] ["chord.3"] ["chord.1" "scale.2"]]
                   [[3 "chord.3"] [] []]
                   [[4 "chord.1" "scale.2"] ["chord.1" "scale.2"] ["chord.3"]]
                   [[4 "chord.1" "scale.2"] [] []]
                   [[4 "chord.1" "scale.2"] [] []]
                   [[5 "chord.3"] ["chord.3"] ["chord.1" "scale.2"]]
                   [[5 "chord.3"] [] []]
                   [[6 "chord.4"] ["chord.4"] ["chord.3"]]
                   [[7 "chord.1" "chord.5"] ["chord.1" "chord.5"] ["chord.4"]]
                   [[8 "chord.1" "chord.6"] ["chord.6"] ["chord.5"]]
                   [[9 "chord.3"] ["chord.3"] ["chord.1" "chord.6"]]
                   [[10 "chord.6"] ["chord.6"] ["chord.3"]]
                   [[11 "chord.3"] ["chord.3"] ["chord.6"]]],
                  :beats
                  [{:items [{:duration 3, :elements ["chord.1" "scale.2"]}],
                    :id 0,
                    :duration 3,
                    :index 0}
                   {:items [{:duration 2, :elements ["chord.3"]}],
                    :id 1,
                    :duration 2,
                    :index 3}
                   {:items [{:duration 3, :elements ["chord.1" "scale.2"]}],
                    :id 2,
                    :duration 3,
                    :index 5}
                   {:items [{:duration 2, :elements ["chord.3"]}],
                    :id 3,
                    :duration 2,
                    :index 8}
                   {:items [{:duration 3, :elements ["chord.1" "scale.2"]}],
                    :id 4,
                    :duration 3,
                    :index 10}
                   {:items [{:duration 2, :elements ["chord.3"]}],
                    :id 5,
                    :duration 2,
                    :index 13}
                   {:items [{:duration 1, :elements ["chord.4"]}],
                    :id 6,
                    :duration 1,
                    :index 15}
                   {:items
                    [{:duration 1, :elements ["chord.5"]}
                     {:duration 2, :elements ["chord.1"]}],
                    :id 7,
                    :duration 1,
                    :index 16}
                   {:items [{:duration 1, :elements ["chord.6"]}],
                    :id 8,
                    :duration 1,
                    :index 17}
                   {:items [{:duration 1, :elements ["chord.3"]}],
                    :id 9,
                    :duration 1,
                    :index 18}
                   {:items [{:duration 1, :elements ["chord.6"]}],
                    :id 10,
                    :duration 1,
                    :index 19}
                   {:items [{:duration 1, :elements ["chord.3"]}],
                    :id 11,
                    :duration 1,
                    :index 20}]}]
      ; (clojure.pprint/pprint (bach.tree/cast-tree sequential? vec actual))
        (is (= want actual))))))
