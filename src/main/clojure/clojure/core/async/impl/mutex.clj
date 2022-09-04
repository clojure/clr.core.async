;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.mutex
  (:import [System.Threading Mutex]))       ;;; [java.util.concurrent.locks Lock ReentrantLock]

;;;(defn mutex []
;;;  (let [m (ReentrantLock.)]
;;;    (reify
;;;     Lock
;;;     (lock [_] (.lock m))
;;;     (unlock [_] (.unlock m)))))


;; The easiest solution is to define a protocol to give us the same methods (lock, unlock) as java.util.concurrent.locks.Lock, 
;; then have mutex reify the protocol wrapping a System.Threading.Mutex.

(defprotocol ILock 
  "Providing the same affordance as java.util.concurrent.locks.Lock"
   (lock [_] "Lock the lock")
   (unlock [_] "Unlock the lock"))
   
(deftype Lock [^Mutex m] 
  ILock
  (lock [x] (.WaitOne m))
  (unlock [x] (.ReleaseMutex m)))
  
  
(defn mutex []
  (Lock. (Mutex.)))
  
