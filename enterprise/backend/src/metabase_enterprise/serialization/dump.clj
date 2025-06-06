(ns metabase-enterprise.serialization.dump
  "Serialize entities into a directory structure of YAMLs."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [metabase-enterprise.serialization.names :refer [fully-qualified-name name-for-logging safe-name]]
   [metabase-enterprise.serialization.serialize :as serialize]
   [metabase.config.core :as config]
   [metabase.models.interface :as mi]
   [metabase.settings.core :as setting]
   [metabase.util.log :as log]
   [metabase.util.yaml :as yaml]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private serialization-order
  (delay (-> (edn/read-string (slurp (io/resource "serialization-order.edn")))
             (update-vals (fn [order]
                            (into {} (map vector order (range))))))))

(defn- serialization-sorted-map* [order-key]
  (if-let [order (or (get @serialization-order order-key)
                     (get @serialization-order (last order-key)))]
    ;; known columns are sorted by their order, then unknown are sorted alphabetically
    (let [getter #(if (contains? order %)
                    [0 (get order %)]
                    [1 %])]
      (sorted-map-by (fn [k1 k2]
                       (compare (getter k1) (getter k2)))))
    (sorted-map)))

(def ^:private serialization-sorted-map (memoize serialization-sorted-map*))

(defn- serialization-deep-sort
  ([m]
   (let [model (-> (:serdes/meta m) last :model)]
     (serialization-deep-sort m [(keyword model)])))
  ([m path]
   (cond
     (map? m)  (into (serialization-sorted-map path)
                     (for [[k v] m]
                       [k (serialization-deep-sort v (conj path k))]))
     (and (sequential? m)
          (map? (first m))) (mapv #(serialization-deep-sort % path) m)
     :else                  m)))

(defn spit-yaml!
  "Writes obj to filename and creates parent directories if necessary.

  Writes (even nested) yaml keys in a deterministic fashion."
  [filename obj]
  (io/make-parents filename)
  (try
    (spit filename (yaml/generate-string (serialization-deep-sort obj)
                                         {:dumper-options {:flow-style :block :split-lines false}}))
    (catch Exception e
      (if-not (.canWrite (.getParentFile (io/file filename)))
        (throw (ex-info (format "Destination path is not writeable: %s" filename) {:filename filename}))
        (throw e)))))

(defn- as-file?
  [instance]
  (some (fn [model]
          (mi/instance-of? model instance))
        [:model/Pulse :model/Dashboard :model/Segment :model/Field :model/User]))

(defn- spit-entity!
  [path entity]
  (let [filename (if (as-file? entity)
                   (format "%s%s.yaml" path (fully-qualified-name entity))
                   (format "%s%s/%s.yaml" path (fully-qualified-name entity) (safe-name entity)))]
    (when (.exists (io/as-file filename))
      (log/warn (str filename " is about to be overwritten."))
      (log/debug (str "With object: " (pr-str entity))))

    (spit-yaml! filename (serialize/serialize entity))))

(defn dump!
  "Serialize entities into a directory structure of YAMLs at `path`."
  [path & entities]
  (doseq [entity (flatten entities)]
    (try
      (spit-entity! path entity)
      (catch Throwable e
        (log/errorf e "Error dumping %s" (name-for-logging entity)))))
  (spit-yaml! (str path "/manifest.yaml")
              {:serialization-version serialize/serialization-protocol-version
               :metabase-version      config/mb-version-info}))

(defn dump-settings!
  "Combine all settings into a map and dump it into YAML at `path`."
  [path]
  (spit-yaml! (str path "/settings.yaml")
              (into {} (for [{:keys [key value]} (setting/admin-writable-site-wide-settings
                                                  :getter (partial setting/get-value-of-type :string))]
                         [key value]))))

(defn dump-dimensions!
  "Combine all dimensions into a vector and dump it into YAML at in the directory for the
   corresponding schema starting at `path`."
  [path]
  (doseq [[table-id dimensions] (group-by (comp :table_id #(t2/select-one :model/Field :id %) :field_id)
                                          (t2/select :model/Dimension))
          :let [table (t2/select-one :model/Table :id table-id)]]
    (spit-yaml! (if (:schema table)
                  (format "%s%s/schemas/%s/dimensions.yaml"
                          path
                          (->> table :db_id (fully-qualified-name :model/Database))
                          (:schema table))
                  (format "%s%s/dimensions.yaml"
                          path
                          (->> table :db_id (fully-qualified-name :model/Database))))
                (mapv serialize/serialize dimensions))))
