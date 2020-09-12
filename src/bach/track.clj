; FIXME: (MAYBE) Don't look at durations as fractions of a measure based on the time signature denominator.
; - For instance: Right now a "whole" note in 6|8 would result in 6 1/8ths (worth of duration) being played. When REALLY that should be 8 1/8th notes (so a measure plus 1/4 note)
; - A whole note should always mean 4 quarter notes, or 8 eighth notes (etc.), regardless of the time signature
; TODO: Allow Bach notes to be scaled/scoped in three different ways:
;  - Measure/bar (like it is now, which sucks for odd meters such as 5|8)
;  - Number of beats / beat unit (1 beat = 1 of the denominator unit of a time signature)
;  - Traditional western (i.e. whole note, or 1,  means 4 quarter notes regardless of time signature)
;    * Probably make this the default, or just switch over to this entirely for now

(ns bach.track
  (:require [instaparse.core :as insta]
            [bach.data :refer [hiccup-to-hash-map ratio-to-vector trim-matrix-row]]))

(defstruct compiled-track :headers :data)

(def default-tempo 120)
(def default-scale "C2 Major")
(def default-time-signature [4 4])
; TODO: Remove all but `tempo`, `time`, `total-beats`, `ms-per-beat`, and `lowest-beat`
(def default-headers {:tempo default-tempo
                      :time default-time-signature
                      :total-beats 0
                      :ms-per-beat 0
                      :lowest-beat [1 4]
                      :title "Untitled"
                      :audio ""
                      :desc ""
                      :link ""
                      :tags []})

(def powers-of-two (iterate (partial * 2) 1))

(defn variable-scope
  "Provides a localized scope/stack for tracking variables"
  [scope]
  (let [context (atom {})]
    (letfn [(variables []
              (get @context :vars {}))
            ; TODO: might just want to move this into `dereference-variables`
            (create-variable [label value]
              (swap! context assoc :vars (conj (variables) [label value])))]
      (scope variables create-variable context))))

; TODO: integreate reduce-values
; TODO: check for :play
; TODO: validate any variables in :play
(defn validate
  "Determines if a parsed track is valid or not"
  [track]
  (variable-scope (fn [variables create-variable _]
    (insta/transform
      {:assign (fn [label-token value-token]
                 (let [[& label] label-token
                       [& value] value-token
                       [value-type] value-token]
                   (case value-type
                     :identifier
                       (when (not (contains? (variables) value))
                         (throw (Exception. (str "variable is not declared before it's used: " value ", " (variables)))))
                     (create-variable label value))))
       :div (fn [top-token bottom-token]
              (let [top    (-> top-token    last read-string)
                    bottom (-> bottom-token last read-string)]
                (cond
                  (not (some #{bottom} (take 10 powers-of-two)))
                    (throw (Exception. "note divisors must be base 2 and no greater than 512")))))
       :tempo (fn [& tempo-token]
                (let [tempo (-> tempo-token last read-string)]
                  (when (not (<= 0 tempo 256))
                    (throw (Exception. "tempos must be between 0 and 256 beats per minute")))))}
      track)))
  true)

(def validate-memo (memoize validate))

; TODO variable-map (call deref-variables, return (:vars context)

(defn deref-variables
  "Dereferences any variables found in the parsed track. Does NOT support hoisting (yet)"
  [track]
  (variable-scope (fn [variables track-variable _]
    (insta/transform
      {:assign (fn [label-token value-token]
                 (let [label (last label-token)
                       value (last value-token)
                       [value-type] value-token]
                   (case value-type
                     :identifier
                       (let [stack-value (get (variables) value)]
                         (track-variable label stack-value)
                         [:assign label-token stack-value])
                     (do (track-variable label value-token)
                       [:assign label-token value-token]))))
       :identifier (fn [label]
                     (let [stack-value (get (variables) label)]
                       (if stack-value stack-value [:identifier label])))
       :play (fn [value-token]
               (let [[& value] value-token
                     [value-type] value-token]
                 (case value-type
                   :identifier
                    (let [stack-value (get (variables) value)]
                      [:play stack-value])
                   [:play value-token])))}
    track))))

(defn reduce-values
  "Reduces any primitive values in a parsed track"
  [track]
  (insta/transform
    {:add +,
     :sub -,
     :mul *,
     :div /,
     :meter (fn [n d] [n d]),
     :number clojure.edn/read-string,
     :string #(clojure.string/replace % #"^(\"|\')|(\"|\')$" "")} track))

(defn reduce-track
  "Dereferences variables and reduces the primitive values in a parsed track"
  [track]
  (-> track
     deref-variables
     reduce-values))

(defn get-headers
  "Provides the headers (aka meta info) for a parsed track"
  [track]
  (let [headers (atom default-headers)
        reduced-track (reduce-track track)] ; TODO: might not want this at this level, should probably be called higher up
    (insta/transform
      {:header (fn [kind-token value]
                 (let [kind (last kind-token)
                       header-key (keyword (clojure.string/lower-case kind))]
                    (swap! headers assoc header-key value)))}
      reduced-track)
    @headers))

(defn find-header
  "Generically finds a header entry / meta tag in a parsed track by its label"
  [track label default]
  (let [result (atom default)]
    (insta/transform
      {:header (fn [meta-key value]
                 (let [kind (last meta-key)]
                   (when (= kind label)
                     (reset! result value))))}
      track)
    @result))

(defn get-time-signature
  [track]
  (let [reduced-track (reduce-values track)
        header (find-header reduced-track "Time" default-time-signature)]
    header))

; FIXME: Support floating point tempos
(defn get-tempo
  [track]
  (find-header track "Tempo" default-tempo))

(defn get-tags
  [track]
  (find-header track "Tags" []))

(defn get-title
  [track]
  (find-header track "Title" [:string "Untitled"]))

(defn get-beat-unit
  "Determines the reference unit to use for beats, based on time signature"
  [track]
  (/ 1 (last (get-time-signature track)))) ; AKA 1/denominator

(defn get-scaled-beat-unit
  "Determines the reference unit to use for beats, scaled to a quarter note
   @see https://music.stackexchange.com/a/24141"
  [track]
  (/ 4 (last (get-time-signature track))))

(defn get-beats-per-measure
  "Determines how many beats are in each measure, based on the time signature"
  [track]
  (first (get-time-signature track))) ; AKA numerator

; TODO: Coerce lowest-beat to be a modulus of the total number of beats in a measure based on the time signature
(defn get-lowest-beat
  "Determines the lowest beat unit defined in the track.
   Serves as the basis for normalization of the track, enabling trivial interpretation"
  [track]
  ; FIXME: Use ##Inf instead in `lowest-duration` once we upgrade to Clojure 1.9.946+
  ; @see: https://cljs.github.io/api/syntax/Inf
  (let [lowest-duration (atom 1024)
        reduced-track (reduce-values track)]
    (insta/transform
      ; NOTE: might need to "evaluate" duration (e.g. if it's like `1+1/2`)
      {:pair (fn [duration _]
               (when (< duration @lowest-duration)
                 (reset! lowest-duration duration)))}
      reduced-track)
    (let [beat-unit (get-beat-unit reduced-track)
          beats-per-measure (get-beats-per-measure reduced-track)
          lowest-beat-unit (/ 1 (-> @lowest-duration
                                    rationalize
                                    clojure.lang.Numbers/toRatio
                                    denominator))
          lowest-beat (min 1 lowest-beat-unit)
          lowest-beat-unit-ratio (/ lowest-beat beat-unit)
          lowest-beat-mod (mod beats-per-measure lowest-beat-unit-ratio)]
      (if (= lowest-beat 1)
        ; FIXME: Might need to min this with the time signature
        (* beats-per-measure beat-unit)
        (if (= lowest-beat-mod 0) lowest-beat beat-unit)))))


(defn get-normalized-lowest-beat
  "Determines the lowest beat normalized against the beat unit of the track (defined in the time signature)"
  [track]
  (let [lowest-beat (get-lowest-beat track)
        ; beat-unit (get-scaled-beat-unit track)]
        beat-unit (get-beat-unit track)]
    (* lowest-beat beat-unit)))

(defn get-normalized-beats-per-measure
  "Determines how many beats are in a measure, normalized against the lowest beat of the track"
  [track]
  (let [lowest-beat (get-lowest-beat track)]
    (if (< lowest-beat 1) (denominator lowest-beat) lowest-beat)))
    ; TODO: Determine why this causes IndexOutOfBounds exception in normalize-measures
    ; (if (< lowest-beat 1) (numerator lowest-beat) lowest-beat)))

; NOTE: this can also be interpreted as "total measures" because the beats aren't normalized
; to the lowest common beat found in the track
; FIXME: Make 1 = 4 1/4 notes (in :trad mode)
;        Make 1 = 1 measure (in :bar mode)
;        Make 1 = 1 beat (in :unit mode)
(defn get-total-beats
  "Determines the total number of beats in the track (1 = 1 whole note, NOT necessarily 1 measure)"
  [track]
  (let [total-beats (atom 0)
        reduced-track (reduce-values track)]
    (insta/transform
      {:pair (fn [duration _]
               (swap! total-beats + duration))}
      reduced-track)
    @total-beats))

(defn get-scaled-total-beats
  "Determines the total number of beats in the track scaled to the beat unit (4/4 time, 4 beats = four quarter notes)"
  [track]
  (let [total-beats (get-total-beats track)
        ; beat-unit (get-scaled-beat-unit track)]
        beat-unit (get-beat-unit track)]
    (/ total-beats beat-unit)))

(defn get-normalized-total-beats
  "Determines the total beats in a track normalized to the lowest beat of the track"
  [track]
  (let [total-beats (get-total-beats track)
        lowest-beat (get-normalized-lowest-beat track)]
    (/ total-beats lowest-beat)))

(defn get-total-measures
  "Determines the total number of measures in the track. Beats and measures are equivelant here
   since the beats are not normalized to the lowest common beat"
  [track]
  (get-total-beats track))

(defn get-total-measures-ceiled
  "Provides the total number of measures in a track, ceiled"
  [track]
  (Math/ceil (get-total-beats track)))

(defn get-total-duration
  "Determines the total time duration of a track (milliseconds, seconds, minutes)"
  [track unit]
  (let [total-beats (get-scaled-total-beats track) ; using scaled because it's adjusted based on time signature, which is important for comparing against tempo
        tempo-bpm (get-tempo track)
        duration-minutes (/ total-beats tempo-bpm)
        duration-seconds (* duration-minutes 60)
        duration-milliseconds (* duration-seconds 1000)]
    (case unit
      :milliseconds duration-milliseconds
      :seconds duration-seconds
      :minutes duration-minutes)))

; @see https://music.stackexchange.com/questions/24140/how-can-i-find-the-length-in-seconds-of-a-quarter-note-crotchet-if-i-have-a-te
(defn get-ms-per-beat
  "Determines the number of milliseconds each beat should be played for (normalized to lowest common beat).
   Mostly exists to make parsing easier for the high-level interpreter / player"
  [track]
  (let [reduced-track (reduce-track track)
        tempo (get-tempo reduced-track)
        lowest-beat (get-lowest-beat reduced-track)
        ; TODO: Probably break this out into its own function
        scaled-lowest-beat (/ (/ 1 4) lowest-beat)
        ms-per-beat (* (/ 60 tempo) 1000)
        norm-ms-per-beat (/ ms-per-beat scaled-lowest-beat)]
    (println "[get-ms-per-beat] lowest-beat" lowest-beat)
    (println "[get-ms-per-beat] scaled-lowest-beat" scaled-lowest-beat)
    (float norm-ms-per-beat)))

; TODO: Fix tracks where end measure is not equal to the number of bars in the time signature (e.g. 2 beats when time sig is 4|4)
; @see: https://lodash.com/docs/#chunk
; FIXME: one thing this should do differently is append the result of the original track definition,
; that way variables and such are retained properly. otherwise this works great.
; FIXME: to achieve above, we could just extract dereferenced notes in :play (from the track) and pass that into insta/transform
(defn normalize-measures
  "Parses the track data exported via `Play` into a normalized matrix where each row (measure) has the same number of elements (beats).
   Makes parsing the track much easier for the high-level interpreter / player as the matrix is trivial to iterate through"
  [track]
  (let [beat-cursor (atom 0) ; NOTE: measured in time-scaled/whole notes, NOT normalized to the lowest beat! (makes parsing easier)
        beats-per-measure (get-normalized-beats-per-measure track)
        lowest-beat (get-lowest-beat track)
        total-measures (get-total-measures-ceiled track)
        total-beats (get-total-beats track)
        unused-tail-beats (mod beats-per-measure (* beats-per-measure (mod total-beats (min total-beats 4))))
        measure-matrix (mapv #(into [] %) (make-array clojure.lang.PersistentArrayMap total-measures beats-per-measure))
        measures (atom (trim-matrix-row measure-matrix (- (count measure-matrix) 1) unused-tail-beats))
        reduced-track (reduce-track track)]
    (println "normalize-track, lowest-beat" lowest-beat)
    (println "normalize-track, total-measures" total-measures)
    (println "normalize-track, total-beats", (get-total-beats track))
    (println "normalize-track, beats-per-measure" beats-per-measure)
    (println "normalize-track, unused-tail-beats" unused-tail-beats)

    (insta/transform
      ; we only want to reduce the notes exported via the `Play` construct, otherwise it's ambiguous what to use
      {:play (fn [play-track]
        (letfn [(update-cursor [beats]
                  (swap! beat-cursor + beats))
                (update-measures [measure-index beat-index notes]
                  (swap! measures assoc-in [measure-index beat-index] notes))
                (beat-indices [beat]
                  (let [global-beat-index (/ @beat-cursor lowest-beat)
                        local-beat-index (mod global-beat-index beats-per-measure)
                        measure-index (int (Math/floor (/ global-beat-index beats-per-measure)))]
                    {:measure measure-index :beat local-beat-index}))] ; TODO; consider using normalized local beat index instead
          (insta/transform
            ; TODO: Generally rename `notes` to `items`. Makes more sense since a beat can contain more than just notes.
            ; TODO: Reduce `notes` so that we don't have a pointless wrapper `:atom`
            ; TODO: Normalize `notes` to a collection
            {:pair (fn [beats notes]
                     (let [indices (beat-indices beats)
                           measure-index (:measure indices)
                           beat-index (:beat indices)
                           compiled-notes {:duration beats :notes (hiccup-to-hash-map notes)}] ; TODO; consider adding: :indices [measure-index beat-index]
                       (update-measures measure-index beat-index compiled-notes)
                       (update-cursor beats)))}
          play-track)))}
        reduced-track)
    @measures))

(defn provision-headers
  "Combines default static meta information with dynamic meta information to provide a provisioned set of headers"
  [track]
  (let [headers (get-headers track)
        time-sig (get-time-signature track)
        total-beats (get-total-beats track)
        ms-per-beat (get-ms-per-beat track)
        lowest-beat (get-lowest-beat track)]
    (assoc headers :time time-sig,
                   :total-beats total-beats,
                   :ms-per-beat ms-per-beat,
                   :lowest-beat lowest-beat)))

; TODO: Allow track to be compiled in flat/stream mode (i.e. no measures, just evenly sized beats)
(defn compile-track
  "Provides a 'compiled' version of a parsed track that contains all of the information necessary to easily
   interpret a track as a single stream of normalized data (no references, all values are resolved)"
  [track]
  (when (validate track)
    (let [headers (provision-headers track)
          data (normalize-measures track)]
      (struct compiled-track headers data))))
