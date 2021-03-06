(ns hiposfer.kamal.router.transit
  "collection of functions related to the use of GTFS feed on routing
  networks."
  (:refer-clojure :exclude [name namespace])
  (:require [hiposfer.kamal.router.algorithms.protocols :as np]
            [hiposfer.kamal.router.util.fastq :as fastq]
            [hiposfer.kamal.router.util.geometry :as geometry]
            [hiposfer.kamal.router.io.osm :as osm]
            [hiposfer.kamal.router.graph :as graph]
            [datascript.core :as data]
            [clojure.set :as set]))

(defn node? [e] (boolean (:node/id e)))
(defn way? [e] (boolean (:way/id e)))
(defn stop? [e] (boolean (:stop/id e)))
;; ............................................................................
;; https://developers.google.com/transit/gtfs/reference/#routestxt
;; TODO: use gtfs.edn for this
(def route-types
  {0 "Tram"; Streetcar, Light rail. Any light rail or street level system within a metropolitan area.
   1 "Subway"; Metro. Any underground rail system within a metropolitan area.
   2 "Rail"; Used for intercity or long-distance travel.
   3 "Bus"; Used for short- and long-distance bus routes.
   4 "Ferry"; Used for short- and long-distance boat service.
   5 "Cable car"; Used for street-level cable cars where the cable runs beneath the car.
   6 "Gondola"; Suspended cable car. Typically used for aerial cable cars where the car is suspended from the cable.
   7 "Funicular"}); Any rail system designed for steep inclines.})

(defn walk-time
  ([p1 p2]
   (.longValue ^Double (/ (geometry/earth-distance p1 p2)
                          osm/walking-speed)))
  ([lon1 lat1 lon2 lat2]
   (.longValue ^Double (/ (geometry/earth-distance lon1 lat1 lon2 lat2)
                          osm/walking-speed))))

(def penalty 30) ;; seconds

;; ............................................................................
(defrecord WalkCost [^Long value]
  np/Valuable
  (cost [_] value)
  Comparable
  (compareTo [_ o] (Long/compare value (np/cost o))))

(defn walk-cost? [o] (instance? WalkCost o))

;; ............................................................................
(defrecord TransitRouter [network graph trips]
  np/Dijkstra
  (node [this k] (get graph k))
  (relax [this arc trail]
    (let [[src-id value] (first trail)
          dst-id         (np/dst arc)
          src            (get graph src-id)
          dst            (get graph dst-id)
          now            (np/cost value)]
      (cond
        ;; The user is just walking so we route based on walking duration
        (graph/node? src) ;; [node (or node stop)]
        (map->WalkCost {:value      (Long/sum now (walk-time src dst))
                        :way/entity (data/entity network (:way/e arc))})

        ;; the user is trying to leave a vehicle. Apply penalty but route
        ;; normally
        (graph/node? dst) ;; [stop node]
        (map->WalkCost {:value      (Long/sum (Long/sum now penalty)
                                              (walk-time src dst))
                        :way/entity (data/entity network (:way/e arc))})

        ;; riding on public transport .............
        (walk-cost? value)
        ;; the user is trying to get on a vehicle - find the next trip
        (let [route       (:route/e arc)
              route-trips (map :e (data/datoms network :avet :trip/route route))
              local-trips (set/intersection (set route-trips) trips)]
          (fastq/find-trip network local-trips src-id dst-id now))

        ;; the user is already in a trip. Just find the trip going to dst [stop stop]
        :else
        (fastq/continue-trip network value dst-id)))))

(defn name
  "returns a name that represents this entity in the network.
   returns nil if the entity has no name"
  [e]
  (cond
    (way? e) (:way/name e)
    (stop? e) (:stop/name e)))

;; WARNING: this function exists solely for the purpose of removing the ambiguity
;; that arises from Ways and Stops having the same name, which makes the `name`
;; function above not correct for partition-by
(defn namespace
  "same as name but returns a vector [k v] where k is the keyword used to
   fetch v. returns nil if the entity has no name"
  [e]
  (cond
    (way? e) [:way/name (:way/name e)]
    (stop? e) [:stop/name (:stop/name e)]))

(defn context
  "Returns an entity holding the 'context' of the trace. For pedestrian routing
  that is the way. For transit routing it is the stop"
  ([piece] ;; a piece already represents a context ;)
   (if (node? (key (first piece))) ;; trip-step otherwise
     (:way/entity (val (first piece)))
     (key (first piece)))) ;; stop
  ([trace vprev]
   (let [previous @vprev]
     (vreset! vprev trace)
     (cond
       ;; walking normally -> return the way
       (and (node? (key previous)) (node? (key trace)))
       (:way/entity (val trace))

       ;; getting into a trip -> return the stop
       (and (node? (key previous)) (stop? (key trace)))
       (key trace)

       ;; on a trip -> return the stop
       (and (stop? (key previous)) (stop? (key trace)))
       (key trace)

       ;; leaving a trip -> return the last stop
       (and (stop? (key previous)) (node? (key trace)))
       (key previous)))))
