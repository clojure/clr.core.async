;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.channels
  (:require [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.dispatch :as dispatch]
            [clojure.core.async.impl.mutex :as mutex])
  (:import [System.Collections.Generic |LinkedList`1[System.Object]| |Queue`1[System.Object]|]        ;;; [java.util LinkedList Queue]
           [clojure.core.async.impl.mutex ILock]                                                      ;;; [java.util.concurrent.locks Lock]
           [clojure.lang IDeref]))   

(set! *warn-on-reflection* true)

(defmacro assert-unlock [lock test msg]
  `(when-not ~test
     (.unlock ~lock)
     (throw (new InvalidOperationException (str "Assert failed: " ~msg "\n" (pr-str '~test))))))      ;;; AssertionError

(defn box [val]
  (reify IDeref
         (deref [_] val)))

(defprotocol MMC
  (cleanup [_])
  (abort [_]))


;;; Two issues on translating the iterations encountered below:
;;;   (a) the iterator/enumerator models of JVM/CLR, respectively, are different
;;;   (b) we cannot do deletes from a list from a CLR enumerator
;;; So we have to do a complete rewrite of the guilty loops.
;;; We are dealing with LinkedList, not a Clojure sequential structure, so we will go gnarly.
;;; We need to walk two pointers, current node and next node.  Check the current node, delete if required.
;;; Then move the pointers.

(deftype ManyToManyChannel [^|LinkedList`1| takes ^|LinkedList`1| puts ^|Queue`1| buf closed ^clojure.core.async.impl.mutex.ILock mutex add!]
  MMC
  (cleanup
   [_]
;;;   (when-not (.isEmpty takes)
;;;     (let [iter (.iterator takes)]
;;;       (loop [taker (.next iter)]
;;;         (when-not (impl/active? taker)
;;;           (.remove iter))
;;;         (when (.hasNext iter)
;;;           (recur (.next iter))))))
;;;   (when-not (.isEmpty puts)
;;;     (let [iter (.iterator puts)]
;;;       (loop [[putter] (.next iter)]
;;;         (when-not (impl/active? putter)
;;;           (.remove iter))
;;;         (when (.hasNext iter)
;;;           (recur (.next iter))))))
    (when-not (= (.Count takes) 0)
	    (loop [curr-node (.First takes)
		       next-node (.Next curr-node)]   ;; curr-node not null at init because count is not 0
		  (when-not (impl/active? (.Value curr-node))
		    (.Remove takes curr-node))
		  (when next-node
		    (recur next-node (.Next next-node)))))
    (when-not (= (.Count puts) 0)
	    (loop [curr-node (.First puts)
		       next-node (.Next curr-node)]   ;; curr-node not null at init because count is not 0
		  (when-not (impl/active? (.Value curr-node))
		    (.Remove puts curr-node))
		  (when next-node
		    (recur next-node (.Next next-node))))))		   		   
  (abort
   [this]
;;;   (let [iter (.iterator puts)]
;;;     (when (.hasNext iter)
;;;       (loop [[^Lock putter] (.next iter)]
;;;         (.lock putter)
;;;         (let [put-cb (and (impl/active? putter) (impl/commit putter))]
;;;           (.unlock putter)
;;;           (when put-cb
;;;             (dispatch/run (fn [] (put-cb true))))
;;;           (when (.hasNext iter)
;;;             (recur (.next iter)))))))
;; this is more straightforward -- enumerator vs iterator again, but no delete
    (let [iter (.GetEnumerator puts)]
	  (when (.MoveNext iter)
	    (loop [[^ILock putter] (.Current iter)]                                     ;;; ^Lock
		  (.lock putter)
          (let [put-cb (and (impl/active? putter) (impl/commit putter))]
            (.unlock putter)
            (when put-cb
              (dispatch/run (fn [] (put-cb true))))  
		    (when (.MoveNext iter)
              (recur (.Current iter)))))))		   
   (.Clear puts)                                                                    ;;; .clear
   (impl/close! this))

  impl/WritePort
  (put!
   [this val handler]
   (when (nil? val)
     (throw (ArgumentException. "Can't put nil on channel")))                 ;;; IllegalArgumentException
   (.lock mutex)
   (cleanup this)
   (if @closed
     (let [^ILock handler handler]                                            ;;; ^Lock
       (.lock handler)
       (when (impl/active? handler) (impl/commit handler))
       (.unlock handler)
       (.unlock mutex)
       (box false))
     (let [^ILock handler handler]                                            ;;; ^Lock
       (if (and buf (not (impl/full? buf)) (not (= (.Count takes) 0)))        ;;; (.isEmpty takes)
         (do
           (.lock handler)
           (let [put-cb (and (impl/active? handler) (impl/commit handler))]
             (.unlock handler)
             (if put-cb
               (let [done? (reduced? (add! buf val))]
                 (if (pos? (count buf))
                   (let ;;; [iter (.iterator takes)
                        ;;;  take-cbs (loop [takers []]
                        ;;;             (if (and (.hasNext iter) (pos? (count buf)))
                        ;;;               (let [^Lock taker (.hasNext iter)]
                        ;;;                 (.lock taker)
                        ;;;                 (let [ret (and (impl/active? taker) (impl/commit taker))]
                        ;;;                  (.unlock taker)
                        ;;;                   (if ret
                        ;;;                     (let [val (impl/remove! buf)]
                        ;;;                       (.remove iter)
                        ;;;                       (recur (conj takers (fn [] (ret val)))))
                        ;;;                     (recur takers))))
                        ;;;               takers))
						[take-cbs (loop [takers []
						                 curr-node (.First takes)]
						            (if (and curr-node (pos? (count buf)))
									  (let [^ILock taker (.Value curr-node)                                   ;;; ^Lock
									         next-node (.Next curr-node)]
									    (.lock taker)
										(let [ret (and (impl/active? taker) (impl/commit taker))]
										  (.unlock taker)
										  (if ret
										    (let [val (impl/remove! buf)]
											  (.Remove takes curr-node)
											  (recur (conj takers (fn [] (ret val))) next-node))
											(recur takers next-node))))
									takers))]					
                     (if (seq take-cbs)
                       (do
                         (when done?
                           (abort this))
                         (.unlock mutex)
                         (doseq [f take-cbs]
                           (dispatch/run f)))
                       (do
                         (when done?
                           (abort this))
                         (.unlock mutex))))
                   (do
                     (when done?
                       (abort this))
                     (.unlock mutex)))
                 (box true))
               (do (.unlock mutex)
                   nil))))
         (let  ;;;[iter (.iterator takes)
               ;;;[put-cb take-cb] (when (.hasNext iter)
               ;;;                   (loop [^Lock taker (.next iter)]                     
               ;;;                     (if (< (impl/lock-id handler) (impl/lock-id taker))
               ;;;                       (do (.lock handler) (.lock taker))
               ;;;                       (do (.lock taker) (.lock handler)))
               ;;;                     (let [ret (when (and (impl/active? handler) (impl/active? taker))
               ;;;                                 [(impl/commit handler) (impl/commit taker)])]
               ;;;                       (.unlock handler)
               ;;;                       (.unlock taker)
               ;;;                       (if ret
               ;;;                         (do
               ;;;                           (.remove iter)
               ;;;                           ret)
               ;;;                         (when (.hasNext iter)
               ;;;                           (recur (.next iter)))))))]
			   [[put-cb take-cb] (when (.First takes)
			                      (loop [curr-node (.First takes)
								         ^ILock taker (.Value curr-node)]                                          ;;; ^Lock
								    (if (< (impl/lock-id handler) (impl/lock-id taker))
									  (do (.lock handler) (.lock taker))
									  (do (.lock taker) (.lock handler)))
									(let [ret (when (and (impl/active? handler) (impl/active? taker))
									             [(impl/commit handler) (impl/commit taker)])]
								      (.unlock handler)
									  (.unlock taker)
									  (if ret
									    (do
										  (.Remove takes curr-node)
										  ret)
										(let [next-node (.Next curr-node)]
										  (when next-node
										    (recur next-node (.Value next-node))))))))]
										  
           (if (and put-cb take-cb)
             (do
               (.unlock mutex)
               (dispatch/run (fn [] (take-cb val)))
               (box true))
             (if (and buf (not (impl/full? buf)))
               (do
                 (.lock handler)
                 (let [put-cb (and (impl/active? handler) (impl/commit handler))]
                   (.unlock handler)
                   (if put-cb
                     (let [done? (reduced? (add! buf val))]
                       (when done?
                         (abort this))
                       (.unlock mutex)
                       (box true))
                     (do (.unlock mutex)
                         nil))))
               (do
                 (when (and (impl/active? handler) (impl/blockable? handler))
                   (assert-unlock mutex
                                  (< (.Count puts) impl/MAX-QUEUE-SIZE)                       ;;; .size
                                  (str "No more than " impl/MAX-QUEUE-SIZE
                                       " pending puts are allowed on a single channel."
                                       " Consider using a windowed buffer."))
                   (.AddLast puts [handler val]))                                             ;;; .add
                 (.unlock mutex)
                 nil))))))))

  impl/ReadPort
  (take!
   [this handler]
   (.lock mutex)
   (cleanup this)
   (let [^ILock handler handler                                                               ;;; ^Lock
         commit-handler (fn []
                          (.lock handler)
                          (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                            (.unlock handler)
                            take-cb))]
     (if (and buf (pos? (count buf)))
       (if-let [take-cb (commit-handler)]
         (let [val (impl/remove! buf)
;;;               iter (.iterator puts)
;;;               [done? cbs]
;;;               (when (and (not (impl/full? buf)) (.hasNext iter))
;;;                 (loop [cbs []
;;;                        [^Lock putter val] (.next iter)]
;;;                   (.lock putter)
;;;                   (let [cb (and (impl/active? putter) (impl/commit putter))]
;;;                     (.unlock putter)
;;;                     (.remove iter)
;;;                     (let [cbs (if cb (conj cbs cb) cbs)
;;;                           done? (when cb (reduced? (add! buf val)))]
;;;                       (if (and (not done?) (not (impl/full? buf)) (.hasNext iter))
;;;                         (recur cbs (.next iter))
;;;                         [done? cbs])))))
               [done? cbs]
			   (when (and (not (impl/full? buf)) (.First puts))
			     (loop [cbs []
				        curr-node (.First puts)]
				   (let [next-node (.Next curr-node)
						 [^ILock putter val] (.Value curr-node)]                               ;;; ^Lock
				   (.lock putter)
				   (let [cb (and (impl/active? putter) (impl/commit putter))]
				     (.unlock putter)
					 (.Remove puts curr-node)
					 (let [cbs (if cb (conj cbs cb) cbs)
					       done? (when cb (reduced? (add! buf val)))]
					   (if (and (not done?) (not (impl/full? buf)) next-node)
					     (recur cbs next-node)
						 [done? cbs]))))))]
           (when done?
             (abort this))
           (.unlock mutex)
           (doseq [cb cbs]
             (dispatch/run #(cb true)))
           (box val))
         (do (.unlock mutex)
             nil))
       (let [
;;;      	    iter (.iterator puts)
;;;             [take-cb put-cb val]
;;;             (when (.hasNext iter)
;;;               (loop [[^Lock putter val] (.next iter)]
;;;                 (if (< (impl/lock-id handler) (impl/lock-id putter))
;;;                   (do (.lock handler) (.lock putter))
;;;                   (do (.lock putter) (.lock handler)))
;;;                 (let [ret (when (and (impl/active? handler) (impl/active? putter))
;;;                             [(impl/commit handler) (impl/commit putter) val])]
;;;                   (.unlock handler)
;;;                   (.unlock putter)
;;;                   (if ret
;;;                     (do
;;;                       (.remove iter)
;;;                       ret)
;;;                     (when-not (impl/active? putter)
;;;                       (.remove iter)
;;;                       (when (.hasNext iter)
;;;                         (recur (.next iter))))))))
             [take-cb put-cb val]
			 (when (.First puts)
			   (loop [curr-node (.First puts)]
                 (let [next-node (.Next curr-node)
					   [^ILock putter val] (.Value curr-node)]                           ;;; ^Lock
                   (if (< (impl/lock-id handler) (impl/lock-id putter))
                     (do (.lock handler) (.lock putter))
                     (do (.lock putter) (.lock handler)))
                   (let [ret (when (and (impl/active? handler) (impl/active? putter))
                               [(impl/commit handler) (impl/commit putter) val])]
                     (.unlock handler)
                     (.unlock putter)
                     (if ret
                       (do
                         (.Remove puts curr-node)
                         ret)
                       (when-not (impl/active? putter)
                         (.Remove puts curr-node)
                         (when next-node
                           (recur next-node ))))))))]
         (if (and put-cb take-cb)
           (do
             (.unlock mutex)
             (dispatch/run #(put-cb true))
             (box val))
           (if @closed
             (do
               (when buf (add! buf))
               (let [has-val (and buf (pos? (count buf)))]
                 (if-let [take-cb (commit-handler)]
                   (let [val (when has-val (impl/remove! buf))]
                     (.unlock mutex)
                     (box val))
                   (do
                     (.unlock mutex)
                     nil))))
             (do
               (when (impl/blockable? handler)
                 (assert-unlock mutex
                                (< (.Count takes) impl/MAX-QUEUE-SIZE)                                ;;; .size
                                (str "No more than " impl/MAX-QUEUE-SIZE
                                     " pending takes are allowed on a single channel."))
                 (.AddLast takes handler))                                                            ;;; .add 
               (.unlock mutex)
               nil)))))))

  impl/Channel
  (closed? [_] @closed)
  (close!
   [this]
   (.lock mutex)
   (cleanup this)
   (if @closed
     (do
       (.unlock mutex)
       nil)
     (do
       (reset! closed true)
       (when (and buf (= (.Count puts) 0))                                                          ;;; (.isEmpty puts)
         (add! buf))
;;;       (let [iter (.iterator takes)]
;;;         (when (.hasNext iter)
;;;           (loop [^Lock taker (.next iter)]
;;;             (.lock taker)
;;;             (let [take-cb (and (impl/active? taker) (impl/commit taker))]
;;;               (.unlock taker)
;;;               (when take-cb
;;;                 (let [val (when (and buf (pos? (count buf))) (impl/remove! buf))]
;;;                   (dispatch/run (fn [] (take-cb val)))))
;;;               (.remove iter)
;;;               (when (.hasNext iter)
;;;                 (recur (.next iter)))))))
       (when (.First takes)
	     (loop [curr-node (.First takes)]
		   (let [next-node (.Next curr-node)
		         ^ILock taker (.Value curr-node)]                                                   ;;; ^Lock
		     (.lock taker)
			 (let [take-cb (and (impl/active? taker) (impl/commit taker))]
			   (.unlock taker)
			   (when take-cb
			     (let [val (when (and buf (pos? (count buf))) (impl/remove! buf))]
				   (dispatch/run (fn [] (take-cb val)))))
			   (.Remove takes curr-node)
			   (when next-node
			     (recur next-node))))))
       (when buf (impl/close-buf! buf))
       (.unlock mutex)
       nil))))

(defn- ex-handler [ex]
  ;;;(-> (Thread/currentThread)
  ;;;    .getUncaughtExceptionHandler             ------------we have no equivalent of this
  ;;;    (.uncaughtException (Thread/currentThread) ex))
  nil)

(defn- handle [buf exh t]
  (let [else ((or exh ex-handler) t)]
    (if (nil? else)
      buf
      (impl/add! buf else))))

(defn chan
  ([buf] (chan buf nil))
  ([buf xform] (chan buf xform nil))
  ([buf xform exh]
     (ManyToManyChannel.
      (|LinkedList`1|.) (|LinkedList`1|.) buf (atom false) (mutex/mutex)
      (let [add! (if xform (xform impl/add!) impl/add!)]
        (fn
          ([buf]
             (try
               (add! buf)
               (catch Exception t               ;;; Throwable
                 (handle buf exh t))))
          ([buf val]
             (try
               (add! buf val)
               (catch Exception t               ;;; Throwable
                 (handle buf exh t)))))))))