(ns metabase.bookmarks.models.bookmark
  (:require
   [clojure.string :as str]
   [metabase.app-db.core :as mdb]
   [metabase.queries.schema :as queries.schema]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

(methodical/defmethod t2/table-name :model/CardBookmark       [_model] :card_bookmark)
(methodical/defmethod t2/table-name :model/DashboardBookmark  [_model] :dashboard_bookmark)
(methodical/defmethod t2/table-name :model/CollectionBookmark [_model] :collection_bookmark)
(methodical/defmethod t2/table-name :model/BookmarkOrdering   [_model] :bookmark_ordering)

(derive :model/CardBookmark :metabase/model)
(derive :model/DashboardBookmark :metabase/model)
(derive :model/CollectionBookmark :metabase/model)
(derive :model/BookmarkOrdering :metabase/model)

(defn- unqualify-key
  [k]
  (-> (str/split (name k) #"\.") peek keyword))

(def BookmarkResult
  "Shape of a bookmark returned for user. Id is a string because it is a concatenation of the model and the model's
  id. This is required for the frontend entity loading system and does not refer to any particular bookmark id,
  although the compound key can be inferred from it."
  [:map {:closed true}
   [:id                               :string]
   [:type                             [:enum "card" "collection" "dashboard"]]
   [:item_id                          ms/PositiveInt]
   [:name                             ms/NonBlankString]
   [:authority_level {:optional true} [:maybe :string]]
   [:card_type       {:optional true} [:maybe ::queries.schema/card-type]]
   [:description     {:optional true} [:maybe :string]]
   [:display         {:optional true} [:maybe :string]]])

(mu/defn- normalize-bookmark-result :- BookmarkResult
  "Normalizes bookmark results. Bookmarks are left joined against the card, collection, and dashboard tables, but only
  points to one of them. Normalizes it so it has just the desired fields."
  [result]
  (let [result            (cond-> (into {} (remove (comp nil? second) result))
                            ;; If not a collection then remove collection properties
                            ;; to avoid shadowing the "real" properties.
                            (not= (:type result) "collection")
                            (dissoc :collection.description :collection.name))
        normalized-result (zipmap (map unqualify-key (keys result)) (vals result))
        id-str            (str (:type normalized-result) "-" (:item_id normalized-result))
        normalized-result (cond-> normalized-result
                            (:card_type normalized-result) (update :card_type keyword))]
    (-> normalized-result
        (select-keys [:item_id :type :name :card_type :description :display
                      :authority_level])
        (assoc :id id-str))))

(defn- bookmarks-union-query
  [user-id]
  (let [as-null (when (= (mdb/db-type) :postgres) (h2x/->integer nil))]
    {:union-all [{:select [:card_id
                           [as-null :dashboard_id]
                           [as-null :collection_id]
                           [:card_id :item_id]
                           [(h2x/literal "card") :type]
                           :created_at]
                  :from   [:card_bookmark]
                  :where  [:= :user_id user-id]}
                 {:select [[as-null :card_id]
                           :dashboard_id
                           [as-null :collection_id]
                           [:dashboard_id :item_id]
                           [(h2x/literal "dashboard") :type]
                           :created_at]
                  :from   [:dashboard_bookmark]
                  :where  [:= :user_id user-id]}
                 {:select [[as-null :card_id]
                           [as-null :dashboard_id]
                           :collection_id
                           [:collection_id :item_id]
                           [(h2x/literal "collection") :type]
                           :created_at]
                  :from   [:collection_bookmark]
                  :where  [:= :user_id user-id]}]}))

(mu/defn bookmarks-for-user :- [:sequential BookmarkResult]
  "Get all bookmarks for a user. Each bookmark will have a string id made of the model and model-id, a type, and
  item_id, name, and description from the underlying bookmarked item."
  [user-id]
  (->> (mdb/query
        {:select    [[:bookmark.created_at        :created_at]
                     [:bookmark.type              :type]
                     [:bookmark.item_id           :item_id]
                     [:card.name                  (mdb/qualify :model/Card :name)]
                     [:card.type                  (mdb/qualify :model/Card :card_type)]
                     [:card.display               (mdb/qualify :model/Card :display)]
                     [:card.description           (mdb/qualify :model/Card :description)]
                     [:card.archived              (mdb/qualify :model/Card :archived)]
                     [:dashboard.name             (mdb/qualify :model/Dashboard :name)]
                     [:dashboard.description      (mdb/qualify :model/Dashboard :description)]
                     [:dashboard.archived         (mdb/qualify :model/Dashboard :archived)]
                     [:collection.name            (mdb/qualify :model/Collection  :name)]
                     [:collection.authority_level (mdb/qualify :model/Collection :authority_level)]
                     [:collection.description     (mdb/qualify :model/Collection :description)]
                     [:collection.archived        (mdb/qualify :model/Collection :archived)]]
         :from      [[(bookmarks-union-query user-id) :bookmark]]
         :left-join [[:report_card :card]                    [:= :bookmark.card_id :card.id]
                     [:report_dashboard :dashboard]          [:= :bookmark.dashboard_id :dashboard.id]
                     ;; use of [[h2x/identifier]] here is a workaround for https://github.com/seancorfield/honeysql/issues/450
                     [:collection :collection]               [:in :collection.id [(h2x/identifier :field :bookmark :collection_id)
                                                                                  (h2x/identifier :field :dashboard :collection_id)]]
                     [:bookmark_ordering :bookmark_ordering] [:and
                                                              [:= :bookmark_ordering.user_id user-id]
                                                              [:= :bookmark_ordering.type :bookmark.type]
                                                              [:= :bookmark_ordering.item_id :bookmark.item_id]]]
         :where     (into [:and]
                          (for [table [:card :dashboard :collection]
                                :let  [field (keyword (str (name table) "." "archived"))]]
                            [:or [:= field false] [:= field nil]]))
         :order-by  [[:bookmark_ordering.ordering (case (mdb/db-type)
                                                    ;; NULLS LAST is not supported by MySQL, but this is default
                                                    ;; behavior for MySQL anyway
                                                    (:postgres :h2) :asc-nulls-last
                                                    :mysql          :asc)]
                     [:created_at :desc]]})
       (map normalize-bookmark-result)))

(defn save-ordering!
  "Saves a bookmark ordering of shape `[{:type, :item_id}]`
   Deletes all existing orderings for user so should be given a total ordering."
  [user-id orderings]
  (t2/delete! :model/BookmarkOrdering :user_id user-id)
  (t2/insert! :model/BookmarkOrdering (->> orderings
                                           (map #(select-keys % [:type :item_id]))
                                           (map-indexed #(assoc %2 :user_id user-id :ordering %1)))))
