(ns hiposfer.kamal.router.io.gtfs
  "namespace for handling GTFS to Datascript convertion and vice versa.

  The algorithms coerces GTFS csv files into entities that reference
  each other through IDs. The IDs are taken directly from GTFS.

  A small dynamic renaming is performed to make it more compatible
  with Datascript design. The renaming is based on the GTFS naming
  convention, for example: stop_lon, stop_url, trip_id, trip_headsign.

  We transform those strings into: :stop/lon, :stop/url, :trip/id,
  :trip/headsign, respectively.

  This is important to be able to create relationships between entities through
  transactions, for example {:trip/id 1, :trip/route [:route/id 1]}. This
  allows us to create relationships between entities without using java references
  ... just data"
  (:require [hiposfer.gtfs.edn :as reference]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [datascript.impl.entity :as dimp]
            [datascript.core :as data]
            [hiposfer.gtfs.edn :as gtfs])
  (:import (java.util.zip ZipInputStream ZipEntry ZipOutputStream)
           (java.time Duration LocalDate ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util List)))

;; detailed diagram of files relations
;; http://tommaps.com/wp-content/uploads/2016/09/gtfs-feed-diagram.png

;; Route Planning in Transportation Networks
;; Overview of the different algorithms and models used
;; - https://arxiv.org/pdf/1504.05140.pdf

;; A good introduction to time-dependent model for timetable information
;; - https://www.ceid.upatras.gr/webpages/faculty/zaro/pub/jou/J23-TTI-Springer.pdf

(def re-number   #"-?\d+(\.\d+)?")
(def re-duration #"(\d{2}):(\d{2}):(\d{2})")
;; we restrict this to avoid collisions with normal integers of length 8
(def re-date     #"20\d{2}[0-1]\d[0-3]\d")
(def re-timezone #"\w+/\w+")

(defn- duration
  "parse the arrival and departure time into Duration instances. This is
  due to GTFS allowing times greater than 23:59:59 which is the biggest
  Java Local Time. A Trip real arrival time can then be calculated as the
  start time + duration at each stop"
  [text]
  (let [[_ & values] (re-matches re-duration text)
        [hh mm ss] (map #(Long/parseLong %) values)
        [hh mm ss] [(Duration/ofHours hh)
                    (Duration/ofMinutes mm)
                    (Duration/ofSeconds ss)]]
    (.getSeconds (reduce (fn [^Duration res v] (. res (plus v)))
                         hh
                         [mm ss]))))

(def date-format (DateTimeFormatter/ofPattern "uuuuMMdd"))
(defn- local-date [text] (LocalDate/parse text date-format))

(defn- number
  "this is to avoid using edn/read-string which fails when encountering
  a number like 008"
  [text]
  (try
    (Long/parseLong text)
    (catch Exception e
      (Double/parseDouble text))))

(defn- timezone
  [text]
  (try
    (ZoneId/of text)
    (catch Exception _ text))) ;; failure, not a timezone, return text

(defn decode
  [text]
  (cond ;; date before number due to the overlapping regex :(
    (re-matches re-date text)     (local-date text)
    (re-matches re-number text)   (number text)
    (re-matches re-duration text) (duration text)
    (re-matches re-timezone text) (timezone text)
    :else text)) ;; simple string

;(coerce "-0.2")
;(coerce "212")
;(coerce "25:30:02")
;(coerce "20180802")
;(coerce "65123245")
;(coerce "Europe/Berlin")

(def truncators
  "map of GTFS filenames to post-processing functions. Useful to remove
   unnecessary information from the GTFS feed; just to reduce the amount
    of datoms in datascript"
  {"routes.txt"   #(dissoc % :route/url)
   "trips.txt"    #(dissoc % :trip/shape)})

(def gtfs-spec (gtfs/spec))

(defn ref?
  "a reference is a field that links to a unique field in another file
  and that is not that field itself"
  [field]
  (and (contains? (set (map :field-name (reference/identifiers gtfs-spec)))
                  (:field-name field))
       (not (:unique field))))

(defn parse
  "takes a filename and parses its content if supported by this parser.
   Entries that do not conform to the gtfs spec are removed. Returns
   a vector of conformed entries"
  [^ZipInputStream zipstream filename]
  (let [file    (io/reader zipstream)
        content (csv/read-csv file)
        head    (map #(reference/get-mapping gtfs-spec filename %) (first content))
        fields  (into {} (map (juxt :keyword identity) head))]
    (for [row (rest content)]
      (into {}
        (for [[k v] (map vector (map :keyword head) row)
              :when (not-empty v)]
          (if (ref? (get fields k))
            [k [(keyword (name k) "id") (decode v)]]
            [k (decode v)]))))))

;(with-open [f (io/reader "resources/frankfurt.gtfs/trips.txt")]
;  (into [] (take 10 (parse (csv/read-csv f) "trips.txt"))))

;; these are also the only supported files for the time being
;; we dont read anything that is not here yet
(def read-order ["agency.txt" "calendar.txt" "routes.txt"
                 "trips.txt" "stops.txt" "stop_times.txt"
                 "frequencies.txt"])

(defn- transaction*
  "returns a sequence of [filename transaction].

  WARNING: The order of the sequence is not guarantee to be consistent
  for a Datascript transaction"
  [^ZipInputStream zipstream]
  (for [^ZipEntry entry (repeatedly #(. zipstream (getNextEntry)))
        :while (some? entry)
        :when  (contains? (set read-order) (. entry (getName)))]
    (let [filename (. entry (getName))
          truncate (get truncators filename identity)]
      ;; NOTE: we are forced to use into [] because otherwise the stream
      ;; would close before we get to read all values
      [filename (into [] (map truncate) (parse zipstream filename))])))

;; NOTE: the commented lines below allow a lazy sorting but are much
;; more complicated and since the processing time is completely dominated
;; by stop_times.txt file, it is almost irrelevant to make this lazy
;; maybe in the future it could be different
(defn transaction!
  "returns a Datascript transaction of the complete gtfs feed"
  [zipstream]
  (mapcat second (sort-by #(. ^List read-order (indexOf (first %)))
                           (transaction* zipstream))))
  ;([files-content stack]
  ; (lazy-seq
  ;   (when (and (not-empty stack) (not-empty files-content))
  ;     (let [tx (some (fn [[file content]]
  ;                      (when (= file (first stack))
  ;                        content))
  ;                    files-content)]
  ;       (concat tx (transaction! (remove (fn [[file]] (= file (first stack)))
  ;                                        files-content)
  ;                                (rest stack))))))))

;(with-open [z (ZipInputStream. (io/input-stream "resources/frankfurt.gtfs.zip"))]
;  (time (vec (drop 100 (transaction! z)))))
;; ............................................................................


;; just for convenience
(def uniques (into #{} (comp (filter :unique) (map :keyword)) (reference/fields gtfs-spec)))
(def keywords (into #{} (map :keyword (reference/fields gtfs-spec))))

(defn resource
  "takes a datascript entity and checks if any of its values are entities, if so
   replaces them by their unique identity value"
  [entity]
  (into {} (remove nil?)
    (for [[k v] entity]
      (cond
        (not (contains? keywords k))
        nil

        (dimp/entity? v)
        [k (select-keys v [(some (set (keys v)) uniques)])]

        (set? v)
        [k (map #(select-keys % [(some (set (keys %)) uniques)]) v)]

        (contains? keywords k) [k v]))))
;; ............................................................................

(defn encode
  "encodes the value of a gtfs field back to the format specified in the
  reference spec"
  [k v]
  (case k
    (:stop_time/arrival_time :stop_time/departure_time
     :frequency/start_time   :frequency/end_time)
    (let [duration (Duration/ofSeconds v)]
      (format "%d:%02d:%02d"
              (.toHours duration)
              (mod (.toMinutes duration) 60)
              (mod (.getSeconds duration) 60)))

    (:service/start_date :service/end_date)
    (. ^LocalDate v (format date-format))
    ;; default - no op
    v))

(defn- reference-keyword
  "returns the attribute id for a reference field.

   For example: [trips.txt service_id] -> :service/id"
  [field]
  (reduce (fn [_ entry] (when (= (:field-name field) (:field-name entry))
                          (reduced (:keyword entry))))
          nil
          (filter :unique (reference/fields gtfs-spec))))

(def feed-hidrator
  "A map of filename -> fn -> [entity ...]. Useful to fetch entities that do
  not have a unique field to fetch from"
  {"stop_times.txt"
   (fn [network]
     (for [trip      (data/datoms network :aevt :trip/id)
           :let [std     (data/datoms network :avet :stop_time/trip (:e trip))
                 entries (map #(data/entity network (:e %)) std)]
           stop_time (sort-by :stop_time/stop_sequence entries)]
       stop_time))
   "frequencies.txt"
   (fn [network]
     (for [trip      (data/datoms network :aevt :trip/id)
           :let [fd      (data/datoms network :avet :frequency/trip (:e trip))
                 entries (map #(data/entity network (:e %)) fd)]
           frequency (sort-by :frequency/start_time entries)]
       frequency))})
   ;TODO
   ;calendar_dates.txt
   ;fare_rules.txt
   ;transfers.txt
   ;feed_info.txt

(defn- key-paths
  "returns a lazy sequence of [k1 ..] (a pth) to fetch the values from
  feed such that references are deconstructed up until its value"
  [feed]
  (for [field reference/fields
        :when (= (:filename feed) (:filename field))]
    (if (ref? field)
      [(:keyword field) (reference-keyword field)]
      [(:keyword field)])))

(defn- feed-entities
  "returns a lazy sequence of all entities belonging to feed"
  [network feed]
  (let [filename (:filename feed)
        field-id (first (filter :unique (:fields feed)))
        id       (reference/get-mapping gtfs-spec filename (:field-name field-id))]
    (cond
      ;; the feed contains a unique ID, use it to fetch all entities
      (some? id)
      (for [d (data/datoms network :avet (:keyword id))]
        (data/entity network (:e d)))

      ;; if there is a feed hidrator for the feed use it to fetch entities
      (some? (feed-hidrator (:filename feed)))
      (apply (feed-hidrator (:filename feed)) [network]))))
      ;; nothing found - ignore feed)))

(defn- dump
  "returns a lazy sequence of [filename content] for each feed of the gtfs
   spec present in network"
  [network]
  (for [feed (:feeds gtfs-spec)
        :let [paths (key-paths feed)]
        :when (not-empty (feed-entities network feed))]
    [(:filename feed)
     (cons (map :field-name (:fields feed));; header
       (for [entity (feed-entities network feed)]
         (for [path paths]
           (if (= 1 (count path))
             (encode (first path) (or (get-in entity path) ""))
             ;; references MUST NOT be encoded
             (get-in entity path)))))]))

(defn dump!
  "writes the complete GTFS information from network into outstream as a Zip file"
  [network outstream]
  (let [zipstream (ZipOutputStream. outstream)]
    (with-open [writer (io/writer zipstream)]
      (doseq [[filename content] (dump network)
              :let [zip-entry (ZipEntry. ^String filename)]]
        #_(println "writing" filename)
        (.putNextEntry zipstream zip-entry)
        (csv/write-csv writer content)
        (.flush writer)))))

#_(time (dump! @(first @(:networks (:router hiposfer.kamal.dev/system)))
               (io/output-stream "resources/test/foo.zip")))

#_(reference/get-mapping "agency.txt" (:field-name (first (filter :unique reference/fields))))
#_(reference-keyword (reference/get-mapping "trips.txt" "service_id"))
#_(reference/get-mapping "calendar.txt" "start_date")

;(seq (Locale/getAvailableLocales))
;(seq (Locale/getISOLanguages))
