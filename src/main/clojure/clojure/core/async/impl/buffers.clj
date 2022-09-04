;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.buffers
  (:require [clojure.core.async.impl.protocols :as impl])
  (:import [System.Collections.Generic |LinkedList`1[System.Object]|]   ;;;  [java.util LinkedList]  -- I don't even know why this works.  You can use |LinkedList`1| below.
           [clojure.lang Counted]))

(set! *warn-on-reflection* true)

(deftype FixedBuffer [^|LinkedList`1| buf ^long n]
  impl/ABuffer                                      ;;; Buffer
  (full? [_this]
    (>= (.Count buf) n))                            ;;; .size 
  (remove! [_this]  
    (let [v (.Last buf)] (.RemoveLast buf) (when v (.Value v))))                              ;;; .removeLast
  (add!* [this itm]
    (.AddFirst buf itm)                             ;;; .addFirst
    this)
  (close-buf! [_this])
  Counted
  (count [_this]
    (.Count buf)))                                  ;;; .size 

(defn fixed-buffer [^long n]
  (FixedBuffer. (|LinkedList`1|.) n))


(deftype DroppingBuffer [^|LinkedList`1| buf ^long n]
  impl/UnblockingBuffer
  impl/ABuffer                                      ;;; Buffer
  (full? [_this]
    false)
  (remove! [_this]
    (let [v (.Last buf)] (.RemoveLast buf) (when v (.Value v))))                              ;;; .removeLast
  (add!* [this itm]
    (when-not (>= (.Count buf) n)                   ;;; .size 
      (.AddFirst buf itm))                          ;;; .addFirst
    this)
  (close-buf! [_this])
  Counted
  (count [_this]
    (.Count buf)))                                  ;;; .size 

(defn dropping-buffer [n]
  (DroppingBuffer. (|LinkedList`1|.) n))

(deftype SlidingBuffer [^|LinkedList`1| buf ^long n]
  impl/UnblockingBuffer
  impl/ABuffer                                      ;;; Buffer
  (full? [_this]
    false)
  (remove! [_this]
    (let [v (.Last buf)] (.RemoveLast buf) (when v (.Value v))))                              ;;; .removeLast
  (add!* [this itm]
    (when (= (.Count buf) n)                        ;;; .size 
      (impl/remove! this))
    (.AddFirst buf itm)                             ;;; .addFirst
    this)
  (close-buf! [_this])
  Counted
  (count [_this]
    (.Count buf)))                                  ;;; .size 

(defn sliding-buffer [n]
  (SlidingBuffer. (|LinkedList`1|.) n))

(defonce ^:private NO-VAL (Object.))
(defn- undelivered? [val]
  (identical? NO-VAL val))

(deftype PromiseBuffer [^:unsynchronized-mutable val]
  impl/UnblockingBuffer
  impl/ABuffer                                      ;;; Buffer
  (full? [_]
    false)
  (remove! [_]
    val)
  (add!* [this itm]
    (when (undelivered? val)
      (set! val itm))
    this)
  (close-buf! [_]
    (when (undelivered? val)
      (set! val nil)))
  Counted
  (count [_]
    (if (undelivered? val) 0 1)))

(defn promise-buffer []
  (PromiseBuffer. NO-VAL))