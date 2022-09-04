;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.concurrent
  (:import [System.Threading Thread ThreadStart]))           ;;; [java.util.concurrent ThreadFactory]

(set! *warn-on-reflection* true)

 
;;;(defn counted-thread-factory
;;;  "Create a ThreadFactory that maintains a counter for naming Threads.
;;;     name-format specifies thread names - use %d to include counter
;;;     daemon is a flag for whether threads are daemons or not
;;;     opts is an options map:
;;;       init-fn - function to run when thread is created"
;;;  ([name-format daemon]
;;;    (counted-thread-factory name-format daemon nil))
;;;  ([name-format daemon {:keys [init-fn] :as opts}]
;;;   (let [counter (atom 0)]
;;;     (reify
;;;       ThreadFactory
;;;       (newThread [_this runnable]
;;;         (let [body (if init-fn
;;;                      (fn [] (init-fn) (.run ^Runnable runnable))
;;;                      runnable)
;;;               t (Thread. ^Runnable body)]
;;;           (doto t
;;;             (.setName (format name-format (swap! counter inc)))
;;;             (.setDaemon daemon))))))))  
  

;;; DM: Added this type to match java.util.concurrent.ThreadFactory

(defprotocol ThreadFactory 
   "Protocol to match java.util.concurrent.ThreadFactory"
  (newThread [_ runnable] "create a new thread"))

;;; In ClojureJVM, an IFn is a Runnable.  Not so in ClojureCLR.
;;; We take in something callable, but have to wrap it as a ThreadStart delegate.

(defn counted-thread-factory
  "Create a ThreadFactory that maintains a counter for naming Threads.
     name-format specifies thread names - use %d to include counter
     daemon is a flag for whether threads are daemons or not
     opts is an options map:
       init-fn - function to run when thread is created"
  ([name-format daemon]
    (counted-thread-factory name-format daemon nil))
  ([name-format daemon {:keys [init-fn] :as opts}]
   (let [counter (atom 0)]
     (reify
       ThreadFactory
       (newThread [_this runnable]
         (let [body (if init-fn
                      (gen-delegate ThreadStart [] (init-fn) (runnable))
                      (gen-delegate ThreadStart [] (runnable)))
               t (Thread. ^ThreadStart body)]
           (doto t
             (.set_Name (format name-format (swap! counter inc)))
             (.set_IsBackground daemon))))))))

(defonce
  ^{:doc "Number of processors reported by the JVM"}
  processors Environment/ProcessorCount)       ;;; (.availableProcessors (Runtime/getRuntime))