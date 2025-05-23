(ns metabase.query-processor.middleware.reconcile-breakout-and-order-by-bucketing
  "SQL places restrictions when using a `GROUP BY` clause (MBQL `:breakout`) in combination with an `ORDER BY`
  clause (MBQL `:order-by`) -- columns that appear in the `ORDER BY` must appear in the `GROUP BY`. When we apply
  datetime or binning bucketing in a breakout, for example `cast(x AS DATE)` (`:field` `:temporal-unit`), we need
  to apply the same bucketing to instances of that Field in the `order-by` clause. In other words:

  Bad:

    SELECT count(*)
    FROM table
    GROUP BY CAST(x AS date)
    ORDER BY x ASC

  (MBQL)

     {:source-table 1
      :breakout     [[:field 1 {:temporal-unit :day}]]
      :order-by     [[:asc [:field 1 nil]]]}

  Good:

    SELECT count(*)
    FROM table
    GROUP BY CAST(x AS date)
    ORDER BY CAST(x AS date) ASC

  (MBQL)

    {:source-table 1
     :breakout     [[:field 1 {:temporal-unit :day}]]
     :order-by     [[:asc [:field 1 {:temporal-unit :day}]]]}

  The frontend, on the rare occasion it generates a query that explicitly specifies an `order-by` clause, usually will
  generate one that directly corresponds to the bad example above. This middleware finds these cases and rewrites the
  query to look like the good example."
  (:require
   [metabase.legacy-mbql.schema :as mbql.s]
   [metabase.lib.util.match :as lib.util.match]
   [metabase.util.malli :as mu]))

(mu/defn- reconcile-bucketing :- mbql.s/Query
  [{{breakouts :breakout} :query, :as query} :- :map]
  ;; Look for bucketed fields in the `breakout` clause and build a map of unbucketed reference -> bucketed reference,
  ;; like:
  ;;
  ;;    {[:field 1 nil] [:field 1 {:temporal-unit :day}]}
  ;;
  ;; In cases where a Field is broken out more than once, prefer the bucketing used by the first breakout; accomplish
  ;; this by reversing the sequence of matches below, meaning the first match will get merged into the map last,
  ;; overwriting later matches
  (let [unbucketed-ref->bucketed-ref (into {} (reverse (lib.util.match/match breakouts
                                                         [:field id-or-name opts]
                                                         [[:field id-or-name (not-empty (dissoc opts :temporal-unit :binning :original-temporal-unit))]
                                                          &match]

                                                         [:expression id-or-name opts]
                                                         [[:expression id-or-name (not-empty (dissoc opts :temporal-unit :binning :original-temporal-unit))]
                                                          &match])))]
    ;; rewrite order-by clauses as needed...
    (-> (lib.util.match/replace-in query [:query :order-by]
          ;; if order by is already bucketed, nothing to do
          [:field id-or-name (_ :guard (some-fn :temporal-unit :binning))]
          &match

          ;; if we run into a field that wasn't matched by the last pattern, see if there's an unbucketed reference
          ;; -> bucketed reference from earlier
          :field
          (if-let [bucketed-reference (unbucketed-ref->bucketed-ref &match)]
            ;; if there is, replace it with the bucketed reference
            bucketed-reference
            ;; if there's not, again nothing to do.
            &match)

          ;; if order by is already bucketed, nothing to do
          [:expression id-or-name (_ :guard (some-fn :temporal-unit :binning))]
          &match

          ;; if we run into an expression that wasn't matched by the last pattern, see if there's an unbucketed reference
          ;; -> bucketed reference from earlier
          :expression
          (if-let [bucketed-reference (unbucketed-ref->bucketed-ref &match)]
            ;; if there is, replace it with the bucketed reference
            bucketed-reference
            ;; if there's not, again nothing to do.
            &match))
        ;; now remove any duplicate order-by clauses we may have introduced, as those are illegal in MBQL 2000
        (update-in [:query :order-by] (comp vec distinct)))))

(defn reconcile-breakout-and-order-by-bucketing
  "Replace any unbucketed `:field` or `:expression` clauses (anything without `:temporal-unit` or `:bucketing` options) in the `order-by`
  clause with corresponding bucketed clauses used for the same Field/Expression in the `breakout` clause.

   {:query {:breakout [[:field 1 {:temporal-unit :day}]]
            :order-by [[:asc [:field 1 nil]]]}}
   ->
   {:query {:breakout [[:field 1 {:temporal-unit :day}]]
            :order-by [[:asc [:field 1 {:temporal-unit :day}]]]}}"
  [{{breakouts :breakout, order-bys :order-by} :query, :as query}]
  (if (or
       ;; if there's no breakouts bucketed by a datetime-field or binning-strategy...
       (empty? (lib.util.match/match breakouts
                 [:field _ (_ :guard (some-fn :temporal-unit :binning))]
                 &match

                 [:expression _ (_ :guard (some-fn :temporal-unit :binning))]
                 &match))
       ;; or if there's no order-bys that are *not* bucketed...
       (empty? (lib.util.match/match order-bys
                 [:field _ (_ :guard (some-fn :temporal-unit :binning))]
                 nil

                 [:expression _ (_ :guard (some-fn :temporal-unit :binning))]
                 nil

                 :field
                 &match

                 :expression
                 &match)))
    ;; return query as is
    query
    ;; otherwise, time to bucket
    (reconcile-bucketing query)))
