;; Copyright (C) 2011~2012, Eduardo Julián. All rights reserved.
;;
;; The use and distribution terms for this software are covered by the 
;; Eclipse Public License 1.0
;; (http://opensource.org/licenses/eclipse-1.0.php) which can be found
;; in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound
;; by the terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns ^{:author "Eduardo Julian <eduardoejp@gmail.com>",
      :doc "This namespace wraps the basic OrientDB API functionality and all the DocumentDB functionality."}
  clj-orient.core
  (:refer-clojure :exclude [load])
  (:import (com.orientechnologies.orient.client.remote OServerAdmin)
    (com.orientechnologies.orient.core.db ODatabase ODatabasePoolBase ODatabaseRecordThreadLocal)
    (com.orientechnologies.orient.core.db.document ODatabaseDocumentTx ODatabaseDocumentPool)
    (com.orientechnologies.orient.core.db.record OTrackedList OTrackedSet OTrackedMap)
    (com.orientechnologies.orient.core.hook ORecordHook ORecordHookAbstract)
    (com.orientechnologies.orient.core.id ORecordId ORID)
    (com.orientechnologies.orient.core.metadata.schema OClass OProperty OClass$INDEX_TYPE OType)
    (com.orientechnologies.orient.core.record ORecord)
    (com.orientechnologies.orient.core.record.impl ODocument ORecordBytes)
    (com.orientechnologies.orient.core.intent OIntentMassiveInsert OIntentMassiveRead))
  (:require clojure.set)
  (:use clojure.template))

(declare schema save-schema! oclass)
(declare prop-in prop-out)
(declare document? orid? oclass?)

; <Utils>
(def kw->oclass-name
  (memoize
    (fn [k]
      (if (string? k)
        k
        (str (if-let [n (namespace k)] (str n "_"))
             (name k))))))

(def oclass-name->kw (memoize (fn [o] (keyword (.replace o "_" "/")))))

(deftype CljODoc [^ODocument odoc]
  clojure.lang.IPersistentMap
  (assoc [_ k v] (.field odoc (name k) (prop-in v)) _)
  (assocEx [_ k v] (if (.containsKey _ k)
                     (throw (Exception. "Key already present."))
                     (do (.assoc odoc k v) _)))
  (without [_ k] (.removeField odoc (name k)) _)
  
  java.lang.Iterable
  (iterator [_] (.iterator (.seq _)))
  
  clojure.lang.Associative
  (containsKey [_ k] (.containsField odoc (name k)))
  (entryAt [_ k] (if-let [v (.valAt _ k)] (clojure.lang.MapEntry. k v)))
  
  clojure.lang.IPersistentCollection
  (count [_] (count (.fieldNames odoc)))
  (cons [self o] (doseq [[k v] o] (.assoc self k v)) self)
  (empty [_] (with-meta (CljODoc. (ODocument.)) (meta _)))
  (equiv [_ o] (= odoc (if (instance? CljODoc o) (.-odoc o) o)))
  
  clojure.lang.Seqable
  (seq [_] (for [k (.fieldNames odoc)] (clojure.lang.MapEntry. (keyword k) (prop-out (.field odoc k)))))
  
  clojure.lang.ILookup
  (valAt [_ k not-found]
    (or (prop-out (.field odoc (name k)))
        (case k
          :#rid (.getIdentity odoc)
          :#class (oclass-name->kw (.field odoc "@class"))
          :#version (.field odoc "@version")
          :#size (.field odoc "@size")
          :#type (.field odoc "@type")
          nil)
        not-found))
  (valAt [_ k] (.valAt _ k nil))
  
  clojure.lang.IFn
  (invoke [_ k] (.valAt _ k))
  (invoke [_ k nf] (.valAt _ k nf))
  
  clojure.lang.IObj
  (meta [self] (prop-out (.field odoc "__meta__")))
  (withMeta [self new-meta]
    {:pre [(map? new-meta)]}
    (.field odoc "__meta__" (prop-in new-meta)) self)
  
  java.lang.Object
  (equals [self o] (= (dissoc odoc "__meta__") (if (instance? CljODoc o) (dissoc (.-odoc o) "__meta__") o))))

(defn wrap-odoc "Wraps an ODocument object inside a CljODoc object."
  [odoc] (CljODoc. odoc))

(defmacro defopener [sym class docstring]
  `(defn ~sym ~docstring [~'db-loc ~'user ~'pass]
     (-> ~class (. (global)) (.acquire ~'db-loc ~'user ~'pass))))

(defn prop-in ; Prepares a property when inserting it on a document.
  [x]
  (cond
    (keyword? x) (str x)
    (set? x) (->> x (map prop-in) java.util.HashSet.)
    (map? x) (apply hash-map (mapcat (fn [[k v]] [(str k) (prop-in v)]) x))
    (coll? x) (map prop-in x)
    (document? x) (.-odoc x)
    :else x))

(defn prop-out ; Prepares a property when extracting it from a document.
  [x]
  (cond
    (and (string? x) (.startsWith x ":")) (keyword (.substring x 1))
    ;(instance? ODocument x) (CljODoc. x) ; Had to comment it out to avoid stack overflows when printing CljODocs...
    (instance? java.util.Set x) (->> x (map prop-out) set)
    (instance? java.util.Map x) (->> x (into {}) (mapcat (fn [[k v]] [(prop-out k) (prop-out v)])) (apply hash-map))
    (instance? java.util.List x) (->> x (map prop-out))
    :else x))

;;;;;;;;;;;;;;;;;;;
;;; DB Handling ;;;
;;;;;;;;;;;;;;;;;;;
(def ^{:doc "This dynamic var holds the current open DB.",
       :tag com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx,
       :dynamic true}
       *db*)

(defn create-db!
  "Creates a new database either locally or remotely. It does not, however, return the open instance or bind *db*."
  ([db-location]
   (-> (ODatabaseDocumentTx. db-location) .create) nil)
  ([^String db-location user password]
   (-> (OServerAdmin. db-location) (.connect user password) (.createDatabase "remote")) nil))

(defn set-db! "Sets *db*'s root binding to the given DB."
  [db]
  (alter-var-root #'*db* (fn [_] db))
  (.set ODatabaseRecordThreadLocal/INSTANCE db)
  db)

(defopener open-document-db! ODatabaseDocumentPool
  "Opens and returns a new ODatabaseDocumentTx.")

(defn close-db!
  "Closes the DB at *db* and sets *db* to nil."
  ([] (when *db* (.close *db*) (set-db! nil)))
  ([^ODatabase db] (.close db)))

(defn delete-db! "Deletes the database bound to *db* and sets *db* to nil."
  ([] (.delete *db*) (set-db! nil))
  ([db] (if (string? db)
          (recur (ODatabaseDocumentTx. db))
          (.delete db))))

(defmacro with-db
  "Evaluates the given forms in an environment where *db* is bound to the given database."
  [db & forms]
  `(binding [*db* ~db]
     (let [x# (do ~@forms)]
       (close-db! *db*)
       x#)))

(defn browse-class "Returns a seq of all the documents of the specified class."
  [kclass & [polymorphic?  fetch-plan]]
  (let [iter (.browseClass ^ODatabaseDocumentTx *db* (kw->oclass-name kclass) (boolean polymorphic?))]
    (if fetch-plan (.setFetchPlan iter fetch-plan))
    (map #(CljODoc. %) (iterator-seq iter))))
(defn browse-cluster "Returns a seq of all the documents in the specified cluster."
  [kcluster & [fetch-plan]]
  (let [iter (.browseCluster *db* (kw->oclass-name kcluster))]
    (if fetch-plan (.setFetchPlan iter fetch-plan))
    (map #(CljODoc. %) (iterator-seq iter))))

(defn count-class "" [kclass] (.countClass ^ODatabaseDocumentTx *db* (kw->oclass-name kclass)))
(defn count-cluster "" [id] (.countClusterElements *db* (if (keyword? id) (kw->oclass-name id) id)))

(defn cluster-names "" [] (map oclass-name->kw (.getClusterNames *db*)))
(defn cluster-name "" [id] (oclass-name->kw (.getClusterNameById *db* id)))
(defn cluster-id "" [kname] (.getClusterIdByName *db* (kw->oclass-name kname)))
(defn cluster-type "" [clname] (keyword (.getClusterType *db* (oclass-name->kw clname))))

(defn add-cluster! "Type in #{:physical, :memory}"
  ([kname type]
   (.addCluster *db*
     (kw->oclass-name kname)
     (into-array [])))
  ([kname type location data-segment]
   (.addCluster *db*
     (kw->oclass-name kname)
     location
     data-segment
     (into-array []))))

(defn drop-cluster! "" [kluster] (.dropCluster *db* (kw->oclass-name kluster)))

(defn db-closed? ""
  ([] (db-closed? *db*))
  ([db] (if (string? db)
          (recur (ODatabaseDocumentTx. db))
          (or (nil? db) (.isClosed db)))))
(defn db-open? ""
  ([] (db-open? *db*))
  ([db] (not (db-closed? db))))
(defn db-exists? ""
  ([] (db-exists? *db*))
  ([db] (if (string? db)
          (recur (ODatabaseDocumentTx. db))
          (.exists ^ODatabase db))))

(defmacro with-tx
  "Runs the following forms inside a transaction.
If an exception arrises, the transaction will fail inmediately and do an automatic rollback.
The exception will be rethrown so the programmer can catch it."
  [& forms]
  `(try (.begin *db*)
     (let [r# (do ~@forms)] (.commit *db*) r#)
     (catch Exception e# (.rollback *db*) (throw e#))))

;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Document Handling ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;
(defn document? "" [x] (instance? CljODoc x))
(defn orid? "" [x] (instance? ORID x))
(defn oclass? "" [x] (instance? OClass x))

(defn document
  "Returns a newly created document given the document's class (as a keyword).
It can optionally take a Clojure hash-map to set the document's properties."
  ([kclass] (CljODoc. (ODocument. (kw->oclass-name kclass))))
  ([kclass properties] (merge (document kclass) properties)))

(def +intents+ {:massive-read (OIntentMassiveRead.), :massive-write (OIntentMassiveInsert.)})

(defn declare-intent! [intent]
  "Declares the database intent. It can be one of #{:massive-read, :massive-write, nil}."
  (.declareIntent *db* (+intents+ intent)))

(defmacro with-intent "Evaluates the forms inside with the given database intent #{:massive-read, :massive-write, nil}."
  [intent & body]
  `(try (.declareIntent *db* (+intents+ ~intent))
        ~@body
        (finally
         (.declareIntent *db* nil))))

(defn documents!
  "Massively inserts documents of the given class into the database in an efficient manner.
The documents must be passed as a sequence of hash-maps."
  [klass props*]
  (with-intent :massive-write
    (let [class (kw->oclass-name klass)
          doc (ODocument. *db* class)]
      (doseq [p props*]
        (.reset doc)
        (.setClassName doc class)
        (doseq [[k v] p]
          (.field doc (name k) (prop-in v)))
        (.save doc))
      nil)))

(defn save! "Saves a document, an OClass or an object (for the Object Database)."
  ([item] (cond
           (document? item) (.save ^ODocument (.-odoc item))
           (oclass? item) (save-schema!)
           :else (.save *db* item))
     item)
  ([document kluster] (.save ^ODocument (.-odoc document) (kw->oclass-name kluster))))

(defn orid "Creates an ORecordId object from a vector [cluster-id record-id] or directly from those arguments."
  ([ridvec] (ORecordId. (first ridvec) (second ridvec)))
  ([cluster-id record-id] (ORecordId. cluster-id record-id)))

(defn orid->vec "Given an ORID, returns a vector [cluster-id, cluster-position]."
  [^ORID orid] [(.getClusterId orid) (.getClusterPosition orid)])

(defn cluster-pos "Given an ORID, returns the Cluster Position"
  [orid] (.getClusterPosition orid))

(defn load "Returns a document (wrapped by CljODoc), given it's id (either as ORID or a vector (either [cluster-id item-id] or [:cluster item-id]))"
  ([orid] (CljODoc. (if (vector? orid)
                      (let [[c i] orid]
                        (if (keyword? c)
                          (.load *db* (ORecordId. (cluster-id c) i))
                          (.load *db* (ORecordId. c i))))
                      (.load *db* orid))))
  ([orid fetch-plan] (CljODoc. (if (vector? orid)
                                 (let [[c i] orid]
                                   (if (keyword? c)
                                     (.load *db* (ORecordId. (cluster-id c) i) fetch-plan)
                                     (.load *db* (ORecordId. c i) fetch-plan)))
                                 (.load *db* orid fetch-plan)))))

(defn delete!
  "Deletes a document if it is passed or if its id (as ORID or vector) is passed.
Can also remove a class from the DB Schema."
  [x]
  (cond
    (document? x) (-> x .-odoc .delete)
    (or (orid? x) (vector? x)) (-> x load .-odoc .delete)
    (keyword? x) (.dropClass (schema) (kw->oclass-name x))
    (oclass? x) (.dropClass (schema) (.getName ^OClass x)))
  nil)

(defn undo! "Undoes local changes to documents."
  [d] (-> d .-odoc .undo))

(defn doc->map [x] (merge {} x))

; JSON
(defn doc->json
  "Exports the record in JSON format specifying optional formatting settings.
format: Format settings separated by comma. Available settings are:
rid: exports the record's id as property \"@rid\"
version: exports the record's version as property \"@version\"
class: exports the record's class as property \"@class\"
attribSameRow: exports all the record attributes in the same row
indent:<level>: Indents the output if the <level> specified. Default is 0
Example: \"rid,version,class,indent:6\" exports record id, version and class properties
along with record properties using an indenting level equals of 6."
  ([doc] (.toJSON (.-odoc doc)))
  ([doc format] (.toJSON (.-odoc doc) format)))

(defn doc->json* "Returns JSON representation of the document with this format: \"rid,version,class,indent:2\""
  [doc] (.toJSON (.-odoc doc) "rid,version,class,indent:2"))

(defn read-json "Fills the record parsing the content in JSON format."
  [doc json] (CljODoc. (.fromJSON (.-odoc doc))))

;;;;;;;;;;;;;;;;;;;;;;;;
;;; Class Properties ;;;
;;;;;;;;;;;;;;;;;;;;;;;;
(def kw->it
  {:dictionary OClass$INDEX_TYPE/DICTIONARY,
   :fulltext   OClass$INDEX_TYPE/FULLTEXT,
   :unique     OClass$INDEX_TYPE/UNIQUE,
   :not-unique OClass$INDEX_TYPE/NOTUNIQUE,
   :proxy      OClass$INDEX_TYPE/PROXY})

(def kw->otype
  {; Basic data-types
   :boolean OType/BOOLEAN
   :byte OType/BYTE
   :short OType/SHORT
   :integer OType/INTEGER
   :long OType/LONG
   :float OType/FLOAT
   :double OType/DOUBLE
   :decimal OType/DECIMAL
   :string OType/STRING
   
   ; Dates
   :date OType/DATE
   :datetime OType/DATETIME
   
   ; Embedded
   :embedded OType/EMBEDDED
   :embedded-list OType/EMBEDDEDLIST
   :embedded-set OType/EMBEDDEDSET
   :embedded-map OType/EMBEDDEDMAP
   
   ; Inter-document links
   :link OType/LINK
   :link-list OType/LINKLIST
   :link-set OType/LINKSET
   :link-map OType/LINKMAP
   
   ; Other stuff...
   :binary OType/BINARY
   :custom OType/CUSTOM
   :transient OType/TRANSIENT
   })

(def otype->kw ^:const (clojure.set/map-invert kw->otype))

(defn get-prop "" [prop klass] (.getProperty (oclass klass) (name prop)))

(defn update-prop!
  "Updates an OProperty object.

Please note:
  type must be one of the following keywords:
    #{:boolean :byte :short :integer :long :float :double :decimal :string
      :date :datetime :binary :custom :transient
      :embedded :embedded-list :embedded-map :embedded-set
      :link :link-list :link-map :link-set}
  index must be one of the following keywords: #{:dictionary, :fulltext, :unique, :not-unique, :proxy}
  If passed a 'false' value for index, the index is dropped."
  ([^OProperty oprop {:keys [name type regex min max mandatory? nullable? index]}]
   (if name (.setName oprop (clojure.core/name name)))
   (if type (.setType oprop (kw->otype type)))
   (if regex (.setRegexp oprop (str regex)))
   (if min (.setMin oprop (str min)))
   (if max (.setMax oprop (str max)))
   (if-not (nil? mandatory?) (.setMandatory oprop mandatory?))
   (if-not (nil? nullable?) (.setNotNull oprop (not nullable?)))
   (cond (keyword? index) (.createIndex oprop (kw->it index))
         (false? index) (.dropIndex oprop)
         :else nil)
   oprop)
  ([klass prop conf] (update-prop! (get-prop prop klass) conf)))

(defn create-prop!
  "When providing a type, it must be one of the following keywords:
  #{:boolean :byte :short :integer :long :float :double :decimal :string
    :date :datetime :binary :custom :transient
    :embedded :embedded-list :embedded-map :embedded-set
    :link :link-list :link-map :link-set}
When using linked types #{:embedded, :link}, provide a vector of [link-type type]

When providing a configuration hash-map, it must be in the format specified for update-prop!."
  ([klass pname ptype] (create-prop! (oclass klass) pname ptype {}))
  ([klass pname ptype conf]
   (-> (if (vector? ptype)
         (.createProperty (oclass klass) (name pname) (kw->otype (first ptype)) (or (kw->otype (second ptype)) (oclass (second ptype))))
         (.createProperty (oclass klass) (name pname) (kw->otype ptype)))
     (update-prop! conf))
   klass))

(defn props "" [klass] (map #(-> % .getName keyword) (.declaredProperties (oclass klass))))
(defn prop-info "Returns a hash-map with detailed information about a class' property."
  [klass prop]
  (let [p (get-prop prop klass)]
    {:type (otype->kw (.getType p))
     :min (.getMin p)
     :max (.getMax p)
     :regex (and (.getRegexp p) (re-pattern (.getRegexp p)))
     :mandatory? (.isMandatory p)
     :nullable? (not (.isNotNull p))
     :linked-class (and (.getLinkedClass p) (-> p .getLinkedClass .getName oclass-name->kw))
     :linked-type (and (.getLinkedType p) (otype->kw (.getLinkedType p)))}))
(defn class-indexes "" [klass] (set (.getClassIndexes (oclass klass))))
(defn class-cluster-ids "" [klass] (seq (.getClusterIds (oclass klass))))
(defn class-default-cluster-id "" [klass] (.getDefaultClusterId (oclass klass)))

(defn exists-prop? "" [klass prop] (.existsProperty (oclass klass) (name prop)))

(defn drop-prop! "Removes a property from an OClass."
  [kclass kname]
  (let [^OClass kclass (oclass kclass)]
    (.dropProperty kclass (name kname))
    (save! kclass)))

(defn indexed? "Tests whether the given props are indexed for the given oclass."
  [klass props] (.areIndexed (oclass klass) (map name props)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Document/GraphElement Classes ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn schema "Returns *db*'s OSchema."
  [] (-> *db* .getMetadata .getSchema))

(defn save-schema! "" [] (.save (schema)) nil)

(defn ^OClass oclass
  "Returns an OClass given the classname as a keyword.
If given an OClass, returns it inmediately."
  [kclass]
  (if (oclass? kclass)
    kclass
    (.getClass (schema) (kw->oclass-name kclass))))

(defn class-name "Returns the classname (as a keyword) from an OClass."
  [odoc] (oclass-name->kw (.getName ^OClass odoc)))

(defn oclasses "Returns a seq of all the OClass objects in the schema."
  [] (-> (schema) .getClasses seq))

(defn derive! "Derives a class from another in the schema."
  [ksubclass, ksuperclass]
  (let [^OClass subclass (oclass ksubclass)
        ^OClass superclass (oclass ksuperclass)]
    (.setSuperClass subclass superclass)
    (save! subclass)
    ksubclass))

(defn sub-classes "Returns a set of the names (as keywords) of subclasses for the given oclass."
  [kclass] (set (map #(oclass-name->kw (.getName ^OClass %)) (iterator-seq (.getBaseClasses (oclass kclass))))))

(defn create-class!
  "Creates a class in the given database and makes it inherit the given superclass."
  ([kclass] (-> (schema) (.createClass (kw->oclass-name kclass)) save!))
  ([kclass ksuperclass-or-props]
   (let [oclass (create-class! kclass)]
     (if (map? ksuperclass-or-props)
       (reduce (fn [oc [k v]] (if (vector? v)
                                (apply create-prop! oc k v)
                                (apply create-prop! oc k [v])))
               oclass ksuperclass-or-props)
       (derive! kclass ksuperclass-or-props))))
  ([kclass ksuperclass props]
   (let [oclass (create-class! kclass ksuperclass)
         oclass (reduce (fn [oc [k v]] (if (vector? v)
                                         (apply create-prop! oc k v)
                                         (apply create-prop! oc k [v])))
                        oclass props)]
     (save! oclass))))

(defn exists-class? "" [kclass] (.existsClass (schema) (kw->oclass-name kclass)))

(defn superclass? ""
  [ksuperclass, ksubclass]
  (let [^OClass subclass (oclass ksubclass)
        ^OClass superclass (oclass ksuperclass)]
    (.isSubClassOf subclass superclass)))

(defn subclass? "" [ksubclass, ksuperclass] (superclass? ksuperclass ksubclass))

(defn truncate-class! "" [kclass] (.truncate (oclass kclass)))

(defn schema-info ""
  []
  (let [schema (schema)]
    {:id (.getIdentity schema), :version (.getVersion schema),
     :classes (set (map #(oclass-name->kw (.getName ^OClass %)) (.getClasses schema)))}))

(defn add-cluster-ids! [klass ids]
  (reduce (fn [oc i] (.addClusterId oc i))
          (oclass klass) (map #(if (integer? %) % (cluster-id %)) ids))
  (save-schema!))

(defn remove-cluster-ids! [klass ids]
  (reduce (fn [oc i] (.removeClusterId oc i))
          (oclass klass) (map #(if (integer? %) % (cluster-id %)) ids))
  (save-schema!))

(defn set-default-cluster-id! [klass id]
  (.setDefaultClusterId (oclass klass)
    (if (integer? id) id (cluster-id id)))
  nil)

; <Hooks>
(def +triggers+
  {'before-create 'onRecordBeforeCreate, 'after-create 'onRecordAfterCreate,
   'before-read 'onRecordBeforeRead, 'after-read 'onRecordAfterRead,
   'before-update 'onRecordBeforeUpdate, 'after-update 'onRecordAfterUpdate,
   'before-delete 'onRecordBeforeDelete, 'after-delete 'onRecordAfterDelete})

(defn hooks "" [] (seq (.getHooks *db*)))
(defn add-hook! "" [hook] (.registerHook *db* hook))
(defn remove-hook! "" [hook] (.unregisterHook *db* hook))

(defmacro defhook
  "Creates a new hook from the following fn definitions (each one is optional):
  (before-create [~document] ~@body)
  (before-read [~document] ~@body)
  (before-update [~document] ~@body)
  (before-delete [~document] ~@body)
  (after-create [~document] ~@body)
  (after-read [~document] ~@body)
  (after-update [~document] ~@body)
  (after-delete [~document] ~@body)
Example:
(defhook log-hook \"Optional doc-string.\"
  (after-create [x] (println \"Created:\" x)))

Notes: defhook only creates the hook. To add it to the current *db* use add-hook.
       All passed records are first wrapped inside a CljODoc object for convenience."
  [sym & triggers]
  (let [[[doc-string] triggers] (split-with string? triggers)]
    `(def ~(with-meta sym {:doc doc-string})
       (proxy [com.orientechnologies.orient.core.hook.ORecordHookAbstract] []
         ~@(for [[meth [arg] & forms] triggers]
             `(~(+triggers+ meth) [~arg] (let [~arg (CljODoc. ~arg)] ~@forms)))))))

; For dealing with ORecordBytes
(defn record-bytes
  "For creating ORecordBytes objects. The source can be either a byte array or an input stream.
To get the data out, use ->output-stream."
  [source]
  (if (instance? java.io.InputStream source)
    (doto (ORecordBytes. *db*) (.fromInputStream source))
    (ORecordBytes. *db* source)))

(defn ->output-stream
  "Writes an ORecordBytes object to the given output stream.
If no output stream is passed, a java.io.ByteArrayOutputStream will be created, written-to and returned."
  ([orb out-stream] (.toOutputStream orb out-stream) out-stream)
  ([orb] (->output-stream orb (java.io.ByteArrayOutputStream.))))

(defn ->bytes "Returns a byte array with the bytes from the ORecordBytes object."
  [orb] (-> orb ->output-stream .toByteArray))

;; <Caching>
(do-template [<sym> <method> <doc>]
  (defn <sym> <doc> [d]
    (<method> (.-odoc d)))
  pin!    .pin      "Suggests to the engine to keep the record in cache."
  unpin!  .unpin    "Suggests to the engine to not keep the record in cache."
  unload! .unload   "Unloads current record."
  pinned? .isPinned "Checks if the record is pinned."
  )

(defn reload!
  "Loads the record content in memory."
  ([doc] (.reload (.-odoc doc)))
  ([doc fetch-plan] (.reload (.-odoc doc) fetch-plan))
  ([doc fetch-plan ignore-cache?] (.reload (.-odoc doc) fetch-plan ignore-cache?)))
