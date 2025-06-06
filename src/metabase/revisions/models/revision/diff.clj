(ns metabase.revisions.models.revision.diff
  (:require
   [clojure.core.match :refer [match]]
   [clojure.data :as data]
   [metabase.util.i18n :refer [deferred-tru]]
   [toucan2.core :as t2]))

(defn- diff-string [k v1 v2 identifier]
  (match [k v1 v2]
    [:name _ _]
    (deferred-tru "renamed {0} from \"{1}\" to \"{2}\"" identifier v1 v2)

    [:description nil _]
    (deferred-tru "added a description")

    [:description (_ :guard some?) _]
    (deferred-tru "changed the description")

    [:private true false]
    (deferred-tru "made {0} public" identifier)

    [:private false true]
    (deferred-tru "made {0} private" identifier)

    [:public_uuid _ nil]
    (deferred-tru "made {0} private" identifier)

    [:public_uuid nil _]
    (deferred-tru "made {0} public" identifier)

    [:enable_embedding false true]
    (deferred-tru "enabled embedding")

    [:enable_embedding true false]
    (deferred-tru "disabled embedding")

    [:parameters _ _]
    (deferred-tru "changed the filters")

    [:embedding_params _ _]
    (deferred-tru "changed the embedding parameters")

    [:archived _ after]
    (if after
      (deferred-tru "trashed {0}" identifier)
      (deferred-tru "untrashed {0}" identifier))

    [:collection_position _ _]
    (deferred-tru "changed pin position")

    [:collection_id nil coll-id]
    (deferred-tru "moved {0} to {1}" identifier (if coll-id
                                                  (t2/select-one-fn :name 'Collection coll-id)
                                                  (deferred-tru "Our analytics")))

    [:collection_id (prev-coll-id :guard int?) coll-id]
    (deferred-tru "moved {0} from {1} to {2}"
                  identifier
                  (t2/select-one-fn :name 'Collection prev-coll-id)
                  (if coll-id
                    (t2/select-one-fn :name 'Collection coll-id)
                    (deferred-tru "Our analytics")))

    [:visualization_settings _ _]
    (deferred-tru "changed the visualization settings")

    ;;  Card specific
    [:parameter_mappings _ _]
    (deferred-tru "changed the filter mapping")

    [:collection_preview _ after]
    (if after
      (deferred-tru "enabled collection review")
      (deferred-tru "disabled collection preview"))

    [:dataset_query _ _]
    (deferred-tru "modified the query")

    ;; report_card.type
    [:type (_ :guard #{:question "question"}) (_ :guard #{:model "model"})]
    (deferred-tru "turned this to a model")

    [:type old new]
    (deferred-tru "type changed from {0} to {1}" old new)

    [:display _ _]
    (deferred-tru "changed the display from {0} to {1}" (name v1) (name v2))

    [:result_metadata _ _]
    (deferred-tru "edited the metadata")

    [:dashboard_id v1 v2]
    (cond
      (and v1 v2) (deferred-tru "moved from dashboard {0} to {1}"
                                (t2/select-one-fn :name :model/Dashboard :id v1)
                                (t2/select-one-fn :name :model/Dashboard :id v2))
      (nil? v1) (deferred-tru "moved this question into {0}"
                              (t2/select-one-fn :name :model/Dashboard :id v2))
      (nil? v2) (deferred-tru "moved this question from {0}"
                              (t2/select-one-fn :name :model/Dashboard :id v1)))

    [:width v1 v2]
    (if (and v1 v2)
      (deferred-tru "changed the width setting from {0} to {1}" (name v1) (name v2))
      (deferred-tru "changed the width setting"))

    ;;  whenever database_id, query_type, table_id changed,
    ;; the dataset_query will changed so we don't need a description for this
    [#{:table_id :database_id :query_type} _ _]
    nil

    :else nil))

(defn build-sentence
  "Join parts of a sentence together to build a compound one."
  [parts]
  (when (seq parts)
    (cond
      (= (count parts) 1) (str (first parts) \.)
      (= (count parts) 2) (str (first parts) " " (deferred-tru "and")  " " (second parts) \.)
      :else               (str (first parts) ", " (build-sentence (rest parts))))))

(defn ^:private model-str->i18n-str
  [model-str]
  (case model-str
    "Dashboard" (deferred-tru "Dashboard")
    "Card"      (deferred-tru "Card")
    "Segment"   (deferred-tru "Segment")))

(defn diff-strings*
  "Create a seq of string describing how `o1` is different from `o2`.
  The directionality of the statement should indicate that `o1` changed into `o2`."
  [model o1 o2]
  (when-let [[before after] (data/diff o1 o2)]
    (let [model-name (model-str->i18n-str model)
          ;; ignore collection_id as part of diff if the dashboard_id has changed
          ;; so that the final diff string doesn't contain two messages about moving
          ks         (cond->> (keys (or after before))
                       (and (= model "Card")
                            (not= (:dashboard_id before) (:dashboard_id after)))
                       (remove #{:collection_id}))]
      (loop [ks               ks
             identifier-count 0
             strings          []]
        (if-not (seq ks)
          strings
          (let [k          (first ks)
                identifier (if (zero? identifier-count) (deferred-tru "this {0}" model-name) (deferred-tru "it"))]
            (if-let [diff-str (diff-string k (k before) (k after) identifier)]
              (recur (rest ks) (inc identifier-count) (conj strings diff-str))
              (recur (rest ks) identifier-count strings))))))))
