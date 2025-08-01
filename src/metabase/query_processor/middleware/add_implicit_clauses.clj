(ns metabase.query-processor.middleware.add-implicit-clauses
  "Middlware for adding an implicit `:fields` and `:order-by` clauses to certain queries."
  (:require
   [clojure.walk :as walk]
   [metabase.legacy-mbql.schema :as mbql.s]
   [metabase.legacy-mbql.util :as mbql.u]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.lib.util.match :as lib.util.match]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.query-processor.store :as qp.store]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Add Implicit Fields                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- table->sorted-fields
  "Return a sequence of all Fields for table that we'd normally include in the equivalent of a `SELECT *`."
  [table-id]
  (->> (lib.metadata/fields (qp.store/metadata-provider) table-id)
       (remove #(#{:sensitive :retired} (:visibility-type %)))
       (sort-by (juxt :position (comp u/lower-case-en :name)))))

(mu/defn sorted-implicit-fields-for-table :- mbql.s/Fields
  "For use when adding implicit Field IDs to a query. Return a sequence of field clauses, sorted by the rules listed
  in [[metabase.query-processor.sort]], for all the Fields in a given Table."
  [table-id :- ::lib.schema.id/table]
  (let [fields (table->sorted-fields table-id)]
    (when (empty? fields)
      (throw (ex-info (tru "No fields found for table {0}." (pr-str (:name (lib.metadata/table (qp.store/metadata-provider) table-id))))
                      {:table-id table-id
                       :type     qp.error-type/invalid-query})))
    (mapv
     (fn [field]
       ;; implicit datetime Fields get bucketing of `:default`. This is so other middleware doesn't try to give it
       ;; default bucketing of `:day`
       [:field (u/the-id field) nil])
     fields)))

(defn- multiply-bucketed-field-refs
  [source-metadata]
  (->> source-metadata
       (map :field_ref)
       (group-by #(some-> % (mbql.u/update-field-options dissoc :binning :temporal-unit :original-temporal-unit)))
       (reduce-kv (fn [duplicates ref-key field-refs]
                    (cond-> duplicates
                      (and ref-key (next field-refs))
                      (into (filter (comp (some-fn :binning :temporal-unit) #(get % 2))) field-refs)))
                  #{})))

(mu/defn- source-metadata->fields :- mbql.s/Fields
  "Get implicit Fields for a query with a `:source-query` that has `source-metadata`."
  [source-metadata :- [:sequential {:min 1} ::mbql.s/legacy-column-metadata]]
  ;; We want to allow columns to be bucketed or binned in several different ways.
  ;; Such columns would be collapsed into a single column if referenced by ID,
  ;; so we make sure that they get a reference by name, which is unique.
  (let [multiply-bucketed-refs (multiply-bucketed-field-refs source-metadata)]
    (distinct
     (for [{field-name               :name
            base-type                :base_type
            field-id                 :id
            [ref-type :as field-ref] :field_ref
            unit                     :unit
            coercion-strategy        :coercion_strategy} source-metadata]
       ;; return field-ref directly if it's a `:field` clause already. It might include important info such as
       ;; `:join-alias` or `:source-field`. Remove binning/temporal bucketing info. The Field should already be getting
       ;; bucketed in the source query; don't need to apply bucketing again in the parent query. Mark the field as
       ;; `qp/ignore-coercion` here so that it doesn't happen again in the parent query.
       (let [not-multiply-bracketed? (not (contains? multiply-bucketed-refs field-ref))]
         (or (and not-multiply-bracketed?
                  (some-> (lib.util.match/match-one field-ref :field)
                          (mbql.u/update-field-options dissoc :binning :temporal-unit)
                          (cond->
                           (or coercion-strategy
                               (and (pos-int? field-id)
                                    (some-> (qp.store/metadata-provider)
                                            (lib.metadata/field field-id)
                                            :coercion-strategy)))
                            (mbql.u/assoc-field-options :qp/ignore-coercion true)

                            unit
                            (mbql.u/assoc-field-options :inherited-temporal-unit unit))))
             ;; otherwise construct a field reference that can be used to refer to this Field.
             ;; Force string id field if expression contains just field. See issue #28451.
             (if (and (not= ref-type :expression)
                      not-multiply-bracketed?
                      field-id)
               ;; If we have a Field ID, return a `:field` (id) clause
               [:field field-id (cond-> nil
                                  coercion-strategy (assoc :qp/ignore-coercion true)
                                  unit              (assoc :inherited-temporal-unit unit))]
               ;; otherwise return a `:field` (name) clause, e.g. for a Field that's the result of an aggregation or
               ;; expression. We don't need to mark as ignore-coercion here because these won't grab the field metadata
               [:field field-name {:base-type base-type}])))))))

(mu/defn- should-add-implicit-fields?
  "Whether we should add implicit Fields to this query. True if all of the following are true:

  *  The query has either a `:source-table`, *or* a `:source-query` with `:source-metadata` for it
  *  The query has no breakouts
  *  The query has no aggregations"
  [{:keys        [fields source-table source-query source-metadata]
    breakouts    :breakout
    aggregations :aggregation} :- mbql.s/MBQLQuery]
  ;; if someone is trying to include an explicit `source-query` but isn't specifiying `source-metadata` warn that
  ;; there's nothing we can do to help them
  (when (and source-query
             (empty? source-metadata)
             (qp.store/initialized?))
    ;; by 'caching' this result, this log message will only be shown once for a given QP run.
    (qp.store/cached [::should-add-implicit-fields-warning]
      (log/warn (str "Warning: cannot determine fields for an explicit `source-query` unless you also include"
                     " `source-metadata`.\n"
                     (format "Query: %s" (u/pprint-to-str source-query))))))
  ;; Determine whether we can add the implicit `:fields`
  (and (or source-table
           (and source-query (seq source-metadata)))
       (every? empty? [aggregations breakouts fields])))

(mu/defn- add-implicit-fields
  "For MBQL queries with no aggregation, add a `:fields` key containing all Fields in the source Table as well as any
  expressions definied in the query."
  [{source-table-id :source-table, :keys [expressions source-metadata], :as inner-query}]
  (if-not (should-add-implicit-fields? inner-query)
    inner-query
    (let [fields      (if source-table-id
                        (sorted-implicit-fields-for-table source-table-id)
                        (source-metadata->fields source-metadata))
          ;; generate a new expression ref clause for each expression defined in the query.
          expressions (for [[expression-name] expressions]
                        ;; TODO - we need to wrap this in `u/qualified-name` because `:expressions` uses
                        ;; keywords as keys. We can remove this call once we fix that.
                        [:expression (u/qualified-name expression-name)])]
      ;; if the Table has no Fields, throw an Exception, because there is no way for us to proceed
      (when-not (seq fields)
        (throw (ex-info (tru "Table ''{0}'' has no Fields associated with it."
                             (:name (lib.metadata/table (qp.store/metadata-provider) source-table-id)))
                        {:type qp.error-type/invalid-query})))
      ;; add the fields & expressions under the `:fields` clause
      (assoc inner-query :fields (vec (concat fields expressions))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                        Add Implicit Breakout Order Bys                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- fix-order-by-field-refs
  "This function transforms top level integer field refs in order by to corresponding string field refs from breakout
  if present.

  ## Context
  In current situation, ie. model as a source, then aggregation and breakout, and finally order by a breakout field,
  [[metabase.lib.order-by/orderable-columns]] returns field ref with integer id, while reference to same field, but
  with string id is present in breakout. Then, [[add-implicit-breakout-order-by]] adds the string ref to order by.

  Resulting query would contain both references, while integral is transformed differently -- it contains no casting.
  As that is not part of group by, the query would fail.

  Reference: https://github.com/metabase/metabase/issues/44653."
  [{:keys [breakout order-by] :as inner-query}]
  (if (or (empty? breakout) (empty? order-by))
    inner-query
    (let [name->breakout (into {}
                               (keep (fn [[tag id-or-name :as clause]]
                                       (when (and (= :field tag)
                                                  (string? id-or-name))
                                         [id-or-name clause])))
                               breakout)
          ref->maybe-field-name (fn [[tag id-or-name]]
                                  (when (and (= :field tag)
                                             (integer? id-or-name))
                                    ((some-fn :lib/desired-column-alias :name)
                                     (lib.metadata/field (qp.store/metadata-provider)
                                                         id-or-name))))
          maybe-convert-order-by-ref (fn [[dir ref :as order-by-elm]]
                                       (if-some [breakout (-> ref ref->maybe-field-name name->breakout)]
                                         [dir breakout]
                                         order-by-elm))]
      (update inner-query :order-by (partial mapv maybe-convert-order-by-ref)))))

(defn- has-window-function-aggregations? [inner-query]
  (or (lib.util.match/match (mapcat inner-query [:aggregation :expressions])
        #{:cum-sum :cum-count :offset}
        true)
      (when-let [source-query (:source-query inner-query)]
        (has-window-function-aggregations? source-query))))

(mu/defn- add-implicit-breakout-order-by :- mbql.s/MBQLQuery
  "Fields specified in `breakout` should add an implicit ascending `order-by` subclause *unless* that Field is already
  *explicitly* referenced in `order-by`."
  [inner-query :- mbql.s/MBQLQuery]
  ;; Add a new [:asc <breakout-field>] clause for each breakout. The cool thing is `add-order-by-clause` will
  ;; automatically ignore new ones that are reference Fields already in the order-by clause
  (let [{breakouts :breakout, :as inner-query} (fix-order-by-field-refs inner-query)]
    (reduce mbql.u/add-order-by-clause inner-query (when-not (has-window-function-aggregations? inner-query)
                                                     (for [breakout breakouts]
                                                       [:asc breakout])))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                   Middleware                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn add-implicit-mbql-clauses
  "Add implicit clauses such as `:fields` and `:order-by` to an 'inner' MBQL query as needed."
  [form]
  (walk/postwalk
   (fn [form]
     ;; add implicit clauses to any 'inner query', except for joins themselves (we should still add implicit clauses
     ;; like `:fields` to source queries *inside* joins)
     (if (and (map? form)
              ((some-fn :source-table :source-query) form)
              (not (:condition form)))
       (-> form add-implicit-breakout-order-by add-implicit-fields)
       form))
   form))

;;; TODO (Cam 7/25/25) -- once this is converted to use Lib we can
;;; remove [[metabase.query-processor.middleware.ensure-joins-use-source-query/ensure-joins-use-source-query]]
(defn add-implicit-clauses
  "Add an implicit `fields` clause to queries with no `:aggregation`, `breakout`, or explicit `:fields` clauses.
   Add implicit `:order-by` clauses for fields specified in a `:breakout`."
  [{query-type :type, :as query}]
  (if (= query-type :native)
    query
    (update query :query add-implicit-mbql-clauses)))
