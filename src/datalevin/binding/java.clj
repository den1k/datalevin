(ns ^:no-doc datalevin.binding.java
  "LMDB binding for Java"
  (:require
   [datalevin.bits :as b]
   [datalevin.spill :as sp]
   [datalevin.util :refer [raise] :as u]
   [datalevin.constants :as c]
   [datalevin.scan :as scan]
   [datalevin.lmdb :as l :refer [open-kv IBuffer IRange IRtx IDB IKV
                                 IList ILMDB IWriting]]
   [clojure.stacktrace :as st]
   [clojure.java.io :as io])
  (:import
   [org.lmdbjava Env EnvFlags Env$MapFullException Stat Dbi DbiFlags
    PutFlags Txn TxnFlags KeyRange Txn$BadReaderLockException CopyFlags
    Cursor CursorIterable$KeyVal GetOp SeekOp KeyVal]
   [java.lang AutoCloseable]
   [java.util.concurrent ConcurrentLinkedQueue]
   [java.util Iterator UUID]
   [java.io File InputStream OutputStream]
   [java.nio.file Files OpenOption]
   [java.nio ByteBuffer BufferOverflowException]
   [clojure.lang IPersistentVector MapEntry]
   [datalevin.spill SpillableVector]
   [datalevin.utl BufOps]
   [org.eclipse.collections.impl.map.mutable UnifiedMap]))

(extend-protocol IKV
  CursorIterable$KeyVal
  (k [this] (.key ^CursorIterable$KeyVal this))
  (v [this] (.val ^CursorIterable$KeyVal this))

  MapEntry
  (k [this] (.key ^MapEntry this))
  (v [this] (.val ^MapEntry this)))

(defn- flag
  [flag-key]
  (case flag-key
    :fixedmap   EnvFlags/MDB_FIXEDMAP
    :nosubdir   EnvFlags/MDB_NOSUBDIR
    :rdonly-env EnvFlags/MDB_RDONLY_ENV
    :writemap   EnvFlags/MDB_WRITEMAP
    :nometasync EnvFlags/MDB_NOMETASYNC
    :nosync     EnvFlags/MDB_NOSYNC
    :mapasync   EnvFlags/MDB_MAPASYNC
    :notls      EnvFlags/MDB_NOTLS
    :nolock     EnvFlags/MDB_NOLOCK
    :nordahead  EnvFlags/MDB_NORDAHEAD
    :nomeminit  EnvFlags/MDB_NOMEMINIT

    :cp-compact CopyFlags/MDB_CP_COMPACT

    :reversekey DbiFlags/MDB_REVERSEKEY
    :dupsort    DbiFlags/MDB_DUPSORT
    :integerkey DbiFlags/MDB_INTEGERKEY
    :dupfixed   DbiFlags/MDB_DUPFIXED
    :integerdup DbiFlags/MDB_INTEGERDUP
    :reversedup DbiFlags/MDB_REVERSEDUP
    :create     DbiFlags/MDB_CREATE

    :nooverwrite PutFlags/MDB_NOOVERWRITE
    :nodupdata   PutFlags/MDB_NODUPDATA
    :current     PutFlags/MDB_CURRENT
    :reserve     PutFlags/MDB_RESERVE
    :append      PutFlags/MDB_APPEND
    :appenddup   PutFlags/MDB_APPENDDUP
    :multiple    PutFlags/MDB_MULTIPLE

    :rdonly-txn TxnFlags/MDB_RDONLY_TXN))

(defn- flag-type
  [type-key]
  (case type-key
    :env  EnvFlags
    :copy CopyFlags
    :dbi  DbiFlags
    :put  PutFlags
    :txn  TxnFlags))

(defn- kv-flags
  [type flags]
  (let [t (flag-type type)]
    (if (empty? flags)
      (make-array t 0)
      (into-array t (mapv flag flags)))))

(defn- put-bf
  [^ByteBuffer bf data type]
  (when data
    (.clear bf)
    (b/put-buffer bf data type)
    (.flip bf)))

(deftype Rtx [lmdb
              ^Txn txn
              ^ByteBuffer kb
              ^ByteBuffer start-kb
              ^ByteBuffer stop-kb
              ^ByteBuffer start-vb
              ^ByteBuffer stop-vb
              aborted?]
  IBuffer
  (put-key [_ x t]
    (try
      (put-bf kb x t)
      (catch BufferOverflowException _
        (raise "Key cannot be larger than 511 bytes." {:input x}))
      (catch Exception e
        (raise "Error putting read-only transaction key buffer: "
               e {:value x :type t}))))
  (put-val [_ x t]
    (raise "put-val not allowed for read only txn buffer" {}))

  IRange
  (range-info [_ range-type k1 k2 kt]
    (put-bf start-kb k1 kt)
    (put-bf stop-kb k2 kt)
    (case range-type
      :all               (KeyRange/all)
      :all-back          (KeyRange/allBackward)
      :at-least          (KeyRange/atLeast start-kb)
      :at-most-back      (KeyRange/atLeastBackward start-kb)
      :at-most           (KeyRange/atMost start-kb)
      :at-least-back     (KeyRange/atMostBackward start-kb)
      :closed            (KeyRange/closed start-kb stop-kb)
      :closed-back       (KeyRange/closedBackward start-kb stop-kb)
      :closed-open       (KeyRange/closedOpen start-kb stop-kb)
      :closed-open-back  (KeyRange/closedOpenBackward start-kb stop-kb)
      :greater-than      (KeyRange/greaterThan start-kb)
      :less-than-back    (KeyRange/greaterThanBackward start-kb)
      :less-than         (KeyRange/lessThan start-kb)
      :greater-than-back (KeyRange/lessThanBackward start-kb)
      :open              (KeyRange/open start-kb stop-kb)
      :open-back         (KeyRange/openBackward start-kb stop-kb)
      :open-closed       (KeyRange/openClosed start-kb stop-kb)
      :open-closed-back  (KeyRange/openClosedBackward start-kb stop-kb)))

  (list-range-info [_ k-range-type k1 k2 kt v-range-type v1 v2 vt]
    (put-bf start-kb k1 kt)
    (put-bf stop-kb k2 kt)
    (put-bf start-vb v1 vt)
    (put-bf stop-vb v2 vt)
    (let [kvalues (l/range-table k-range-type k1 k2 start-kb stop-kb)
          vvalues (l/range-table v-range-type v1 v2 start-vb stop-vb)]
      (into kvalues vvalues)))

  IRtx
  (read-only? [_]
    (.isReadOnly txn))
  (get-txn [_]
    txn)
  (close-rtx [_]
    (.close txn))
  (reset [this]
    (.reset txn)
    this)
  (renew [this]
    (.renew txn)
    this))

(defn- stat-map [^Stat stat]
  {:psize          (.-pageSize stat)
   :depth          (.-depth stat)
   :branch-pages   (.-branchPages stat)
   :leaf-pages     (.-leafPages stat)
   :overflow-pages (.-overflowPages stat)
   :entries        (.-entries stat)})

(declare ->ListIterable)

(deftype DBI [^Dbi db
              ^ConcurrentLinkedQueue curs
              ^ByteBuffer kb
              ^:unsynchronized-mutable ^ByteBuffer vb
              ^boolean validate-data?]
  IBuffer
  (put-key [this x t]
    (or (not validate-data?)
        (b/valid-data? x t)
        (raise "Invalid data, expecting " t {:input x}))
    (try
      (put-bf kb x t)
      (catch BufferOverflowException _
        (raise "Key cannot be larger than 511 bytes." {:input x}))
      (catch Exception e
        (raise "Error putting r/w key buffer of "
               (.dbi-name this) "with value" x ": " e
               {:type t}))))
  (put-val [this x t]
    (or (not validate-data?)
        (b/valid-data? x t)
        (raise "Invalid data, expecting " t {:input x}))
    (try
      (put-bf vb x t)
      (catch BufferOverflowException _
        (let [size (* ^long c/+buffer-grow-factor+ ^long (b/measure-size x))]
          (set! vb (b/allocate-buffer size))
          (b/put-buffer vb x t)
          (.flip vb)))
      (catch Exception e
        (raise "Error putting r/w value buffer of "
               (.dbi-name this) ": " e
               {:value x :type t :dbi (.dbi-name this)}))))

  IDB
  (dbi [_] db)
  (dbi-name [_]
    (b/text-ba->str (.getName db)))
  (put [_ txn flags]
    (if flags
      (.put db txn kb vb (kv-flags :put flags))
      (.put db txn kb vb (kv-flags :put c/default-put-flags))))
  (put [this txn]
    (.put this txn nil))
  (del [_ txn all?]
    (if all?
      (.delete db txn kb)
      (.delete db txn kb vb)))
  (del [this txn]
    (.del this txn true))
  (get-kv [_ rtx]
    (let [^ByteBuffer kb (.-kb ^Rtx rtx)]
      (.get db (.-txn ^Rtx rtx) kb)))
  (iterate-kv [_ rtx [range-type k1 k2] k-type]
    (let [range-info (l/range-info rtx range-type k1 k2 k-type)]
      (.iterate db (.-txn ^Rtx rtx) range-info)))
  (iterate-list [this rtx [k-range-type k1 k2] k-type
                 [v-range-type v1 v2] v-type]
    (let [txn (.-txn ^Rtx rtx)
          cur (.get-cursor this txn)
          ctx (l/list-range-info rtx k-range-type k1 k2 k-type
                                 v-range-type v1 v2 v-type)]
      (->ListIterable this cur rtx ctx)))
  (get-cursor [_ txn]
    (or (when (.isReadOnly ^Txn txn)
          (when-let [^Cursor cur (.poll curs)]
            (.renew cur txn)
            cur))
        (.openCursor db txn)))
  (close-cursor [_ cur]
    (.close ^Cursor cur))
  (return-cursor [_ cur]
    (.add curs cur)))

(deftype ListIterable [^DBI db
                       ^Cursor cur
                       ^Rtx rtx
                       ctx]
  AutoCloseable
  (close [_]
    (if (.isReadOnly ^Txn (.-txn rtx))
      (.return-cursor db cur)
      (.close cur)))

  Iterable
  (iterator [_]
    (let [[forward-key?
           start-key?
           include-start-key?
           stop-key?
           include-stop-key?
           ^ByteBuffer sk
           ^ByteBuffer ek
           forward-val?
           start-val?
           include-start-val?
           stop-val?
           include-stop-val?
           ^ByteBuffer sv
           ^ByteBuffer ev] ctx

          key-ended?   (volatile! false)
          val-started? (volatile! false)

          init-key
          #(do
             (println "init-key")
             (if start-key?
               (and (.get cur sk GetOp/MDB_SET_RANGE)
                    (if include-start-key?
                      (if stop-key?
                        (<= (BufOps/compareByteBuf (.key cur) ek) 0)
                        true)
                      (if forward-key?
                        (if (zero? (BufOps/compareByteBuf (.key cur) sk))
                          (.seek cur SeekOp/MDB_NEXT_NODUP)
                          (if stop-key?
                            (<= (BufOps/compareByteBuf (.key cur) ek) 0)
                            true))
                        (.seek cur SeekOp/MDB_PREV_NODUP))))
               (if forward-key?
                 (.seek cur SeekOp/MDB_FIRST)
                 (.seek cur SeekOp/MDB_LAST))))
          init-val
          #(do
             (let [^ByteBuffer k (.key cur)]
               (when-not (zero? (.limit k))
                 (println "init-val cur key =>" (b/read-buffer k :string))
                 (.rewind k)))
             (vreset! val-started? true)
             (if start-val?
               (and (.get cur (.key cur) sv SeekOp/MDB_GET_BOTH_RANGE)
                    (let [rs (BufOps/compareByteBuf (.val cur) sv)]
                      (if (= rs 0)
                        (if include-start-val?
                          true
                          (if forward-val?
                            (.seek cur SeekOp/MDB_NEXT_DUP)
                            (.seek cur SeekOp/MDB_PREV_DUP)))
                        (if (> rs 0)
                          (if forward-val?
                            (if stop-val?
                              (<= (BufOps/compareByteBuf (.val cur) ev) 0)
                              true)
                            (.seek cur SeekOp/MDB_PREV_DUP))
                          (if forward-val?
                            (.seek cur SeekOp/MDB_NEXT_DUP)
                            (if stop-val?
                              (>= (BufOps/compareByteBuf (.val cur) ev) 0)
                              true))))))
               (if forward-val?
                 (.seek cur SeekOp/MDB_FIRST_DUP)
                 (.seek cur SeekOp/MDB_LAST_DUP))))
          key-end       #(do
                           (println "key-end")
                           (vreset! key-ended? true) false)
          key-continue? #(do
                           (println "key-continue?")
                           (if stop-key?
                             (let [r (BufOps/compareByteBuf (.key cur) ek)]
                               (if (= r 0)
                                 (do (vreset! key-ended? true)
                                     include-stop-key?)
                                 (if (> r 0)
                                   (if forward-key? (key-end) true)
                                   (if forward-key? true (key-end)))))
                             true))
          check-key     #(do
                           (println "check-key")
                           (if (.seek cur %) (key-continue?) (key-end)))
          advance-key   #(or (and (if forward-key?
                                    (check-key SeekOp/MDB_NEXT_NODUP)
                                    (check-key SeekOp/MDB_PREV_NODUP))
                                  (init-val))
                             (if @key-ended? false (recur)))
          init-kv       #(or (and (init-key) (init-val))
                             (advance-key))
          val-end       #(do
                           (println "val-end")
                           (if @key-ended? false (advance-key)))
          val-continue? #(do
                           (println "val-continue?")
                           (if stop-val?
                             (let [r (BufOps/compareByteBuf
                                       (.val cur) ev)]
                               (if (= r 0)
                                 (if include-stop-val? true (val-end))
                                 (if (> r 0)
                                   (if forward-val? (val-end) true)
                                   (if forward-val? true (val-end)))))
                             true))
          check-val     #(do
                           (println "check-val")
                           (if (.seek cur %) (val-continue?) (val-end)))
          advance-val   #(if forward-val?
                           (check-val SeekOp/MDB_NEXT_DUP)
                           (check-val SeekOp/MDB_PREV_DUP))
          ]
      (reify
        Iterator
        (hasNext [_]
          (if (not @val-started?)
            (init-kv)
            (advance-val)))
        (next [_]
          (println "~~~")
          (let [^ByteBuffer k (.key cur)
                ^ByteBuffer v (.val cur)]
            (when-not (zero? (.limit k))
              (println "cur key =>" (b/read-buffer k :string))
              (.rewind k))
            (when-not (zero? (.limit v))
              (println "cur val =>" (b/read-buffer v :long))
              (.rewind v))
            (MapEntry. k v)))))))

(defn- up-db-size [^Env env]
  (.setMapSize env (* ^long c/+buffer-grow-factor+
                      ^long (-> env .info .mapSize))))

(defn- transact*
  [txs ^UnifiedMap dbis txn]
  (doseq [^IPersistentVector tx txs]
    (let [cnt      (.length tx)
          op       (.nth tx 0)
          dbi-name (.nth tx 1)
          k        (.nth tx 2)
          ^DBI dbi (or (.get dbis dbi-name)
                       (raise dbi-name " is not open" {}))]
      (case op
        :put      (let [v     (.nth tx 3)
                        kt    (when (< 4 cnt) (.nth tx 4))
                        vt    (when (< 5 cnt) (.nth tx 5))
                        flags (when (< 6 cnt) (.nth tx 6))]
                    (.put-key dbi k kt)
                    (.put-val dbi v vt)
                    (if flags
                      (.put dbi txn flags)
                      (.put dbi txn)))
        :del      (let [kt (when (< 3 cnt) (.nth tx 3)) ]
                    (.put-key dbi k kt)
                    (.del dbi txn))
        :put-list (let [vs (.nth tx 3)
                        kt (when (< 4 cnt) (.nth tx 4))
                        vt (when (< 5 cnt) (.nth tx 5))]
                    (.put-key dbi k kt)
                    (doseq [v vs]
                      (.put-val dbi v vt)
                      (.put dbi txn)))
        :del-list (let [vs (.nth tx 3)
                        kt (when (< 4 cnt) (.nth tx 4))
                        vt (when (< 5 cnt) (.nth tx 5))]
                    (.put-key dbi k kt)
                    (doseq [v vs]
                      (.put-val dbi v vt)
                      (.del dbi txn false)))
        (raise "Unknown kv operator: " op {})))))

(defn- get-list*
  [lmdb ^Rtx rtx ^Cursor cur k kt vt]
  (.put-key rtx k kt)
  (when (.get cur (.-kb rtx) GetOp/MDB_SET)
    (let [^SpillableVector holder
          (sp/new-spillable-vector nil (:spill-opts (l/opts lmdb)))]
      (.seek cur SeekOp/MDB_FIRST_DUP)
      (.cons holder (b/read-buffer (.val cur) vt))
      (dotimes [_ (dec (.count cur))]
        (.seek cur SeekOp/MDB_NEXT_DUP)
        (.cons holder (b/read-buffer (.val cur) vt)))
      holder)))

(defn- visit-list*
  [^Rtx rtx ^Cursor cur k kt visitor]
  (let [kv (reify IKV
             (k [_] (.key cur))
             (v [_] (.val cur)))]
    (.put-key rtx k kt)
    (when (.get cur (.-kb rtx) GetOp/MDB_SET)
      (.seek cur SeekOp/MDB_FIRST_DUP)
      (visitor kv)
      (dotimes [_ (dec (.count cur))]
        (.seek cur SeekOp/MDB_NEXT_DUP)
        (visitor kv)))))

(defn- list-count*
  [^Rtx rtx ^Cursor cur k kt]
  (.put-key rtx k kt)
  (if (.get cur (.-kb rtx) GetOp/MDB_SET)
    (.count cur)
    0))

(defn- in-list?*
  [^Rtx rtx ^Cursor cur k kt v vt]
  (l/list-range-info rtx :at-least k nil kt :at-least v nil vt)
  (.get cur (.-start-kb rtx) (.-start-vb rtx) SeekOp/MDB_GET_BOTH))

(declare ->LMDB reset-write-txn)

(deftype LMDB [^Env env
               ^String dir
               temp?
               opts
               ^ConcurrentLinkedQueue pool
               ^UnifiedMap dbis
               ^ByteBuffer kb-w
               ^ByteBuffer start-kb-w
               ^ByteBuffer stop-kb-w
               ^ByteBuffer start-vb-w
               ^ByteBuffer stop-vb-w
               write-txn
               writing?]
  IWriting
  (writing? [_] writing?)

  (write-txn [_] write-txn)

  (mark-write [_]
    (->LMDB
      env dir temp? opts pool dbis kb-w start-kb-w stop-kb-w
      start-vb-w stop-vb-w write-txn true))

  ILMDB
  (close-kv [_]
    (when-not (.isClosed env)
      (loop [^Iterator iter (.iterator pool)]
        (when (.hasNext iter)
          (.close-rtx ^Rtx (.next iter))
          (.remove iter)
          (recur iter)))
      (.sync env true)
      (.close env))
    (when temp? (u/delete-files dir))
    nil)

  (closed-kv? [_] (.isClosed env))

  (dir [_] dir)

  (opts [_] opts)

  (open-dbi [this dbi-name]
    (.open-dbi this dbi-name nil))
  (open-dbi [this dbi-name {:keys [key-size val-size flags validate-data?]
                            :or   {key-size       c/+max-key-size+
                                   val-size       c/+default-val-size+
                                   flags          c/default-dbi-flags
                                   validate-data? false}}]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (assert (< ^long key-size 512) "Key size cannot be greater than 511 bytes")
    (let [kb  (b/allocate-buffer key-size)
          vb  (b/allocate-buffer val-size)
          db  (.openDbi env ^String dbi-name
                        ^"[Lorg.lmdbjava.DbiFlags;" (kv-flags :dbi flags))
          dbi (->DBI db (ConcurrentLinkedQueue.) kb vb validate-data?)]
      (.put dbis dbi-name dbi)
      dbi))

  (get-dbi [this dbi-name]
    (.get-dbi this dbi-name true))
  (get-dbi [this dbi-name create?]
    (or (.get dbis dbi-name)
        (if create?
          (.open-dbi this dbi-name)
          (.open-dbi this dbi-name {:key-size c/+max-key-size+
                                    :val-size c/+default-val-size+
                                    :flags    c/read-dbi-flags}))))

  (clear-dbi [this dbi-name]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (try
      (let [^DBI dbi (.get-dbi this dbi-name )]
        (with-open [txn (.txnWrite env)]
          (.drop ^Dbi (.-db dbi) txn)
          (.commit txn)))
      (catch Exception e
        (raise "Fail to clear DBI: " dbi-name " " e {}))))

  (drop-dbi [this dbi-name]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (try
      (let [^DBI dbi (.get-dbi this dbi-name)]
        (with-open [txn (.txnWrite env)]
          (.drop ^Dbi (.-db dbi) txn true)
          (.commit txn))
        (.remove dbis dbi-name)
        nil)
      (catch Exception e
        (raise "Fail to drop DBI: " dbi-name e {}))))

  (list-dbis [this]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (try
      (mapv b/text-ba->str (.getDbiNames env))
      (catch Exception e
        (raise "Fail to list DBIs: " e {}))))

  (copy [this dest]
    (.copy this dest false))
  (copy [this dest compact?]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (let [d (u/file dest)]
      (if (u/empty-dir? d)
        (.copy env d (kv-flags :copy (if compact? [:cp-compact] [])))
        (raise "Destination directory is not empty." {}))))

  (get-rtx [this]
    (try
      (or (when-let [^Rtx rtx (.poll pool)]
            (.renew rtx))
          (->Rtx this
                 (.txnRead env)
                 (b/allocate-buffer c/+max-key-size+)
                 (b/allocate-buffer c/+max-key-size+)
                 (b/allocate-buffer c/+max-key-size+)
                 (b/allocate-buffer c/+max-key-size+)
                 (b/allocate-buffer c/+max-key-size+)
                 (volatile! false)))
      (catch Txn$BadReaderLockException _
        (raise
          "Please do not open multiple LMDB connections to the same DB
           in the same process. Instead, a LMDB connection should be held onto
           and managed like a stateful resource. Refer to the documentation of
           `datalevin.core/open-kv` for more details." {}))))

  (return-rtx [this rtx]
    (.reset ^Rtx rtx)
    (.add pool rtx))

  (stat [this]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (try
      (stat-map (.stat env))
      (catch Exception e
        (raise "Fail to get statistics: " e {}))))
  (stat [this dbi-name]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (if dbi-name
      (let [^Rtx rtx (.get-rtx this)]
        (try
          (let [^DBI dbi (.get-dbi this dbi-name false)
                ^Dbi db  (.-db dbi)
                ^Txn txn (.-txn rtx)]
            (stat-map (.stat db txn)))
          (catch Exception e
            (raise "Fail to get stat: " (ex-message e) {:dbi dbi-name}))
          (finally (.return-rtx this rtx))))
      (l/stat this)))

  (entries [this dbi-name]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (let [^DBI dbi (.get-dbi this dbi-name false)
          ^Rtx rtx (.get-rtx this)]
      (try
        (.-entries ^Stat (.stat ^Dbi (.-db dbi) (.-txn rtx)))
        (catch Exception e
          (raise "Fail to get entries: " (ex-message e)
                 {:dbi dbi-name}))
        (finally (.return-rtx this rtx)))))

  (open-transact-kv [this]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (try
      (reset-write-txn this)
      (.mark-write this)
      (catch Exception e
        ;; (st/print-stack-trace e)
        (raise "Fail to open read/write transaction in LMDB: "
               (ex-message e) {}))))

  (close-transact-kv [this]
    (try
      (if-let [^Rtx wtxn @write-txn]
        (when-let [^Txn txn (.-txn wtxn)]
          (let [aborted? @(.-aborted? wtxn)]
            (when-not aborted? (.commit txn))
            (vreset! write-txn nil)
            (.close txn)
            (if aborted? :aborted :committed)))
        (raise "Calling `close-transact-kv` without opening" {}))
      (catch Exception e
        ;; (st/print-stack-trace e)
        (raise "Fail to commit read/write transaction in LMDB: "
               (ex-message e) {}))))

  (abort-transact-kv [this]
    (when-let [^Rtx wtxn @write-txn]
      (vreset! (.-aborted? wtxn) true)
      (vreset! write-txn wtxn)
      nil))

  (transact-kv [this txs]
    (assert (not (.closed-kv? this)) "LMDB env is closed.")
    (locking  write-txn
      (let [^Rtx rtx  @write-txn
            one-shot? (nil? rtx)]
        (try
          (if one-shot?
            (with-open [txn (.txnWrite env)]
              (transact* txs dbis txn)
              (.commit txn))
            (transact* txs dbis (.-txn rtx)))
          :transacted
          (catch Env$MapFullException _
            (when-not one-shot? (.close ^Txn (.-txn rtx)))
            (up-db-size env)
            (if one-shot?
              (.transact-kv this txs)
              (do (reset-write-txn this)
                  (raise "DB resized" {:resized true}))))
          (catch Exception e
            ;; (st/print-stack-trace e)
            (raise "Fail to transact to LMDB: " e {}))))))

  (get-value [this dbi-name k]
    (.get-value this dbi-name k :data :data true))
  (get-value [this dbi-name k k-type]
    (.get-value this dbi-name k k-type :data true))
  (get-value [this dbi-name k k-type v-type]
    (.get-value this dbi-name k k-type v-type true))
  (get-value [this dbi-name k k-type v-type ignore-key?]
    (scan/get-value this dbi-name k k-type v-type ignore-key?))

  (get-first [this dbi-name k-range]
    (.get-first this dbi-name k-range :data :data false))
  (get-first [this dbi-name k-range k-type]
    (.get-first this dbi-name k-range k-type :data false))
  (get-first [this dbi-name k-range k-type v-type]
    (.get-first this dbi-name k-range k-type v-type false))
  (get-first [this dbi-name k-range k-type v-type ignore-key?]
    (scan/get-first this dbi-name k-range k-type v-type ignore-key?))

  (get-range [this dbi-name k-range]
    (.get-range this dbi-name k-range :data :data false))
  (get-range [this dbi-name k-range k-type]
    (.get-range this dbi-name k-range k-type :data false))
  (get-range [this dbi-name k-range k-type v-type]
    (.get-range this dbi-name k-range k-type v-type false))
  (get-range [this dbi-name k-range k-type v-type ignore-key?]
    (scan/get-range this dbi-name k-range k-type v-type ignore-key?))

  (range-seq [this dbi-name k-range]
    (.range-seq this dbi-name k-range :data :data false nil))
  (range-seq [this dbi-name k-range k-type]
    (.range-seq this dbi-name k-range k-type :data false nil))
  (range-seq [this dbi-name k-range k-type v-type]
    (.range-seq this dbi-name k-range k-type v-type false nil))
  (range-seq [this dbi-name k-range k-type v-type ignore-key?]
    (.range-seq this dbi-name k-range k-type v-type ignore-key? nil))
  (range-seq [this dbi-name k-range k-type v-type ignore-key? opts]
    (scan/range-seq this dbi-name k-range k-type v-type ignore-key? opts))

  (range-count [this dbi-name k-range]
    (.range-count this dbi-name k-range :data))
  (range-count [this dbi-name k-range k-type]
    (scan/range-count this dbi-name k-range k-type))

  (get-some [this dbi-name pred k-range]
    (.get-some this dbi-name pred k-range :data :data false))
  (get-some [this dbi-name pred k-range k-type]
    (.get-some this dbi-name pred k-range k-type :data false))
  (get-some [this dbi-name pred k-range k-type v-type]
    (.get-some this dbi-name pred k-range k-type v-type false))
  (get-some [this dbi-name pred k-range k-type v-type ignore-key?]
    (scan/get-some this dbi-name pred k-range k-type v-type ignore-key?))

  (range-filter [this dbi-name pred k-range]
    (.range-filter this dbi-name pred k-range :data :data false))
  (range-filter [this dbi-name pred k-range k-type]
    (.range-filter this dbi-name pred k-range k-type :data false))
  (range-filter [this dbi-name pred k-range k-type v-type]
    (.range-filter this dbi-name pred k-range k-type v-type false))
  (range-filter [this dbi-name pred k-range k-type v-type ignore-key?]
    (scan/range-filter this dbi-name pred k-range k-type v-type ignore-key?))

  (range-filter-count [this dbi-name pred k-range]
    (.range-filter-count this dbi-name pred k-range :data))
  (range-filter-count [this dbi-name pred k-range k-type]
    (scan/range-filter-count this dbi-name pred k-range k-type))

  (visit [this dbi-name visitor k-range]
    (.visit this dbi-name visitor k-range :data))
  (visit [this dbi-name visitor k-range k-type]
    (scan/visit this dbi-name visitor k-range k-type))

  (open-list-dbi [this dbi-name {:keys [key-size val-size]
                                 :or   {key-size c/+max-key-size+
                                        val-size c/+max-key-size+}}]
    (assert (and (>= c/+max-key-size+ ^long key-size)
                 (>= c/+max-key-size+ ^long val-size))
            "Data size cannot be larger than 511 bytes")
    (.open-dbi this dbi-name
               {:key-size key-size :val-size val-size
                :flags    (conj c/default-dbi-flags :dupsort)}))
  (open-list-dbi [lmdb dbi-name]
    (.open-list-dbi lmdb dbi-name nil))

  IList
  (put-list-items [this dbi-name k vs kt vt]
    (.transact-kv this [[:put-list dbi-name k vs kt vt]]))

  (del-list-items [this dbi-name k kt]
    (.transact-kv this [[:del dbi-name k kt]]))
  (del-list-items [this dbi-name k vs kt vt]
    (.transact-kv this [[:del-list dbi-name k vs kt vt]]))

  (visit-list [this dbi-name visitor k kt]
    (when k
      (let [lmdb this]
        (scan/scan-list
          (visit-list* rtx cur k kt visitor)
          (raise "Fail to visit list: " (ex-message e)
                 {:dbi dbi-name :k k})))))

  (list-count [this dbi-name k kt]
    (if k
      (let [lmdb this]
        (scan/scan-list
          (list-count* rtx cur k kt)
          (raise "Fail to count list: " (ex-message e) {:dbi dbi-name :k k})))
      0))

  (in-list? [this dbi-name k v kt vt]
    (if (and k v)
      (let [lmdb this]
        (scan/scan-list
          (in-list?* rtx cur k kt v vt)
          (raise "Fail to test if an item is in list: "
                 (ex-message e) {:dbi dbi-name :k k :v v})))
      false))

  (get-list [this dbi-name k kt vt]
    (when k
      (let [lmdb this]
        (scan/scan-list
          (get-list* this rtx cur k kt vt)
          (raise "Fail to get a list: " (ex-message e)
                 {:dbi dbi-name :key k})))))

  (list-range [this dbi-name k-range kt v-range vt]
    (scan/list-range this dbi-name k-range kt v-range vt))

  )

(defn- reset-write-txn
  [^LMDB lmdb]
  (let [kb-w       ^ByteBuffer (.-kb-w lmdb)
        start-kb-w ^ByteBuffer (.-start-kb-w lmdb)
        stop-kb-w  ^ByteBuffer (.-stop-kb-w lmdb)
        start-vb-w ^ByteBuffer (.-start-vb-w lmdb)
        stop-vb-w  ^ByteBuffer (.-stop-vb-w lmdb)]
    (.clear kb-w)
    (.clear start-kb-w)
    (.clear stop-kb-w)
    (.clear start-vb-w)
    (.clear stop-vb-w)
    (vreset! (.-write-txn lmdb) (->Rtx lmdb
                                       (.txnWrite ^Env (.-env lmdb))
                                       kb-w
                                       start-kb-w
                                       stop-kb-w
                                       start-vb-w
                                       stop-vb-w
                                       (volatile! false)))))

(defmethod open-kv :java
  ([dir]
   (open-kv dir {}))
  ([dir {:keys [mapsize flags temp?]
         :or   {mapsize c/+init-db-size+
                flags   c/default-env-flags
                temp?   false}
         :as   opts}]
   (try
     (let [^File file (u/file dir)
           builder    (doto (Env/create)
                        (.setMapSize (* ^long mapsize 1024 1024))
                        (.setMaxReaders c/+max-readers+)
                        (.setMaxDbs c/+max-dbs+))
           ^Env env   (.open builder file (kv-flags :env flags))
           lmdb       (->LMDB env
                              dir
                              temp?
                              opts
                              (ConcurrentLinkedQueue.)
                              (UnifiedMap.)
                              (b/allocate-buffer c/+max-key-size+)
                              (b/allocate-buffer c/+max-key-size+)
                              (b/allocate-buffer c/+max-key-size+)
                              (b/allocate-buffer c/+max-key-size+)
                              (b/allocate-buffer c/+max-key-size+)
                              (volatile! nil)
                              false)]
       (when temp? (u/delete-on-exit file))
       lmdb)
     (catch Exception e
       ;; (st/print-stack-trace e)
       (raise "Fail to open database: " (ex-message e) {:dir dir})))))

;; TODO remove after LMDBJava supports apple silicon
(defn apple-silicon-lmdb []
  (when (and (u/apple-silicon?)
             (not (System/getenv "DTLV_COMPILE_NATIVE"))
             (not (u/graal?)))
    (try
      (let [dir             (u/tmp-dir (str "lmdbjava-native-lib-"
                                            (UUID/randomUUID)) )
            ^File file      (File. ^String dir "liblmdb.dylib")
            path            (.toPath file)
            fpath           (.getAbsolutePath file)
            ^ClassLoader cl (.getContextClassLoader (Thread/currentThread))]
        (u/create-dirs dir)
        (.deleteOnExit file)
        (System/setProperty "lmdbjava.native.lib" fpath)

        (with-open [^InputStream in
                    (.getResourceAsStream
                      cl "dtlvnative/macos-latest-aarch64-shared/liblmdb.dylib")
                    ^OutputStream out
                    (Files/newOutputStream path (into-array OpenOption []))]
          (io/copy in out))
        (println "Library extraction is successful:" fpath
                 "with size" (Files/size path)))
      (catch Exception e
        ;; (st/print-stack-trace e)
        (u/raise "Failed to extract LMDB library" {})))))

(apple-silicon-lmdb)
