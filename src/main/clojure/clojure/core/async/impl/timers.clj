1422;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.timers
  (:require [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.channels :as channels])
  (:import [System.Threading AutoResetEvent Thread]))                ;;;  [java.util.concurrent DelayQueue Delayed TimeUnit ConcurrentSkipListMap]
  
;; we have to do some serious reworking here.  
;; We do not have direct equivalents of any of the data structures used in the JVM implementation.
;;
;; A quick analysis:
;; 
;; ConcurrentSkipListMap is used essentially as a priority queue.  
;;   This code uses the ceilingEntry method to find the first entry with a larger timestamp.
;;   A skip list can do this in log(n) time.    
;;
;;    If we use a SortedList, we'll be O(n).  An alternative is a SortedSet, which has a GetViewBetween.  
;;    That should be time-efficient, but likely will do more memory allocation.   Pick one.
;;   
;;    (It would be nice to use System.Collections.Generic.PriorityQueue, but that is not available in Framework.)
;;
;; DelayQueue is a blocking queue for which entries become available only when their timestamps have been reached.
;;    We do have a BlockingCollection, but no underlying collection type to use that has the needed characteristics.
;;    We are going to have roll our own.
;;
;; But if you look at the use case here, we don't really need so much.  Let us talk our way through this.
;;
;; Clients call the function `timeout` to get a channel that will close within a certain number of milliseconds.
;; If there is a channel with a remaining timeout between the desired duration and that duration + TIMEOUT_RESOLUTION_MS, that channel can be used.
;; Otherwise we need to create a channel, register it in our system here, and return it.
;;
;; We need a thread to timeout the channels.
;; This thread can WaitOne on a wait handle.  Its timeout is infinite if the collection of channels is empty.
;; Otherwise, its timeout will be at the time when the next channel is to be closed.
;; the 'timeout' function might post something with a closer timeout.  It should trigger the wait handle, waking this thread.
;; When awakened, the thread closes any channels whose time has expired, then waits again.
;;
;; In the JVM code, the thread waits on the DelayQueue.  We replace that by waiting on the wait handle with a timeout.
;;

;;; Original code
;;;
;;;(set! *warn-on-reflection* true)
;;;
;;;(defonce ^:private ^DelayQueue timeouts-queue
;;;  (DelayQueue.))
;;;
;;;(defonce ^:private ^ConcurrentSkipListMap timeouts-map
;;;  (ConcurrentSkipListMap.))
;;;
;;;(def ^:const TIMEOUT_RESOLUTION_MS 10)
;;;
;;;(deftype TimeoutQueueEntry [channel ^long timestamp]
;;;  Delayed
;;;  (getDelay [_this time-unit]
;;;    (.convert time-unit
;;;              (- timestamp (System/currentTimeMillis))
;;;              TimeUnit/MILLISECONDS))
;;;  (compareTo
;;;   [_this other]
;;;   (let [ostamp (.timestamp ^TimeoutQueueEntry other)]
;;;     (if (< timestamp ostamp)
;;;       -1
;;;       (if (= timestamp ostamp)
;;;         0
;;;         1))))
;;;  impl/Channel
;;;  (close! [_this]
;;;    (impl/close! channel)))
;;;
;;;(defn- timeout-worker
;;;  []
;;;  (let [q timeouts-queue]
;;;    (loop []
;;;      (let [^TimeoutQueueEntry tqe (.take q)]
;;;        (.remove timeouts-map (.timestamp tqe) tqe)
;;;        (impl/close! tqe))
;;;      (recur))))
;;;
;;;(defonce timeout-daemon
;;;  (delay
;;;   (doto (Thread. ^Runnable timeout-worker "clojure.core.async.timers/timeout-daemon")
;;;     (.setDaemon true)
;;;     (.start))))
;;;
;;;(defn timeout
;;;  "returns a channel that will close after msecs"
;;;  [^long msecs]
;;;  @timeout-daemon
;;;  (let [timeout (+ (System/currentTimeMillis) msecs)
;;;        me (.ceilingEntry timeouts-map timeout)]
;;;    (or (when (and me (< (.getKey me) (+ timeout TIMEOUT_RESOLUTION_MS)))
;;;          (.channel ^TimeoutQueueEntry (.getValue me)))
;;;        (let [timeout-channel (channels/chan nil)
;;;              timeout-entry (TimeoutQueueEntry. timeout-channel timeout)]
;;;          (.put timeouts-map timeout timeout-entry)
;;;          (.put timeouts-queue timeout-entry)
;;;          timeout-channel))))


;;; And away we go.

(set! *warn-on-reflection* true)

(def ^:const TIMEOUT_RESOLUTION_MS 10)
(def ^:const TIMEOUT_RESOLUTION_TICKS (* TIMEOUT_RESOLUTION_MS TimeSpan/TicksPerMillisecond))

(def ^:private ^AutoResetEvent wait-handle (AutoResetEvent. false))

(deftype TimeoutQueueEntry [channel ^long timestamp]
  IComparable
  (CompareTo [this y]
    (if (and y (instance? TimeoutQueueEntry y)) 
	  (let [tqe ^TimeoutQueueEntry y]
	    (.CompareTo timestamp (.timestamp tqe)))
	  1)) 
  impl/Channel
  (close! [_this]
    (impl/close! channel)))
	
	
(def ^:private ^|System.Collections.Generic.SortedSet`1[ clojure.core.async.impl.timers.TimeoutQueueEntry]| timeouts-set (|System.Collections.Generic.SortedSet`1[ clojure.core.async.impl.timers.TimeoutQueueEntry]|.))

(defn- least-upper-bound-entry 
  "Get the timeout entry with the smallest value of timeout in the interval given"
  [start-time end-time]
  (let [start-entry (TimeoutQueueEntry. nil start-time)
        end-entry (TimeoutQueueEntry. nil end-time)]
    (.Min (.GetViewBetween timeouts-set start-entry end-entry))))
	  
(defn- expired? 
  "Determine if a TimeQueueEntry has expired"
  [^TimeoutQueueEntry tqe]
  (let [ts (.timestamp tqe)
        now (.Ticks DateTime/UtcNow)]
	(< ts now)))

(defn- maybe-close-tqe
  "If this entry has expired, close its channel.  
   Return true if entry has expired, false otherwise"
   [^TimeoutQueueEntry tqe]
   (when (expired? tqe)
      (impl/close! tqe)
	  true))

(defn- process-entries
  "Close the channel on each timed-out entry"
  []
  (locking (.SyncRoot ^System.Collections.ICollection timeouts-set)
    (loop []
	  (when-let [tqe (.Min timeouts-set)]
   	    (when (maybe-close-tqe tqe)
		  (.Remove timeouts-set tqe)
		  (recur))))))

(defn- timeout-worker
  []
    (loop []
	  (process-entries)
	  (let [tqe (.Min timeouts-set)]
	     (if tqe 
		   (.WaitOne wait-handle (TimeSpan. (- (.timestamp tqe) (.Ticks DateTime/UtcNow))))
		   (.WaitOne wait-handle)))
	  (recur)))
	  
(defonce timeout-daemon
  (delay
   (doto (Thread. ^System.Threading.ThreadStart (gen-delegate System.Threading.ThreadStart [] (timeout-worker)))
     (.set_Name "clojure.core.async.timers/timeout-daemon")
	 (.set_IsBackground true)
	 (.Start))))
	 
(defn timeout
  "returns a channel that will close after msecs"
  [^long msecs]
  @timeout-daemon
  (locking (.SyncRoot ^System.Collections.ICollection timeouts-set)
    (let [timeout (+ (.Ticks DateTime/UtcNow) (* TimeSpan/TicksPerMillisecond msecs))
          lub (least-upper-bound-entry timeout (+ timeout TIMEOUT_RESOLUTION_TICKS))]
      (or (when lub
            (.channel ^TimeoutQueueEntry lub))
          (let [timeout-channel (channels/chan nil)
                timeout-entry (TimeoutQueueEntry. timeout-channel timeout)]
	  	    (.Add timeouts-set timeout-entry)
            (.Set wait-handle)
	  	    timeout-channel)))))
		  