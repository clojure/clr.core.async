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
  processors Environment/ProcessorCount)                   ;;; (.availableProcessors (Runtime/getRuntime))
  
  
;; Implementation of a substitute for java.util.concurrent.atomic.AtomicReferenceArray
;; Would love to do this lock-free (using Interlocked, Volatile.Read/Write, but can't.  See comment below.

(definterface IAtomicReferenceArray
   (set [^long idx o])
   (get [^long idx]))
   
(deftype AtomicReferenceArray [^|Object[]| arr]   
  IAtomicReferenceArray
  (set [this ^long idx o] 
     (locking arr
	    (aset arr idx o)
		o))
  (get [this ^long idx]
      (locking arr
	     (aget arr idx))))
		 
		 
     
  




;; Compliments of Copilot
;; Problem, we cannot do the equivalent of 'ref _array[i]' in Clojure
;; We'll have to do locking.
;/// <summary>
;/// Thread-safe array of references where each element can be updated atomically.
;/// </summary>
;public class AtomicReferenceArray<T> where T : class
;{
;    private readonly T[] _array
;
;    public AtomicReferenceArray(int length)
;    {
;        if (length < 0)
;            throw new ArgumentOutOfRangeException(nameof(length), "Length must be non-negative.");
;        _array = new T[length];
;    }
;
;    public AtomicReferenceArray(T[] source)
;    {
;        if (source == null)
;            throw new ArgumentNullException(nameof(source));
;        _array = new T[source.Length];
;        Array.Copy(source, _array, source.Length);
;    }
;
;    public int Length => _array.Length;
;
;    /// <summary>
;    /// Gets the value at the given index.
;    /// </summary>
;    public T Get(int index)
;    {
;        ValidateIndex(index);
;        return Volatile.Read(ref _array[index]);
;    }
;
;    /// <summary>
;    /// Sets the value at the given index.
;    /// </summary>
;    public void Set(int index, T newValue)
;    {
;        ValidateIndex(index);
;        Volatile.Write(ref _array[index], newValue);
;    }
;
;    /// <summary>
;    /// Atomically sets the value to newValue if the current value equals expectedValue.
;    /// </summary>
;    public bool CompareAndSet(int index, T expectedValue, T newValue)
;    {
;        ValidateIndex(index);
;        return Interlocked.CompareExchange(ref _array[index], newValue, expectedValue) == expectedValue;
;    }
;
;    /// <summary>
;    /// Atomically gets the current value and sets a new value.
;    /// </summary>
;    public T GetAndSet(int index, T newValue)
;    {
;        ValidateIndex(index);
;        return Interlocked.Exchange(ref _array[index], newValue);
;    }
;
;    private void ValidateIndex(int index)
;    {
;        if (index < 0 || index >= _array.Length)
;            throw new ArgumentOutOfRangeException(nameof(index));
;    }
;}  