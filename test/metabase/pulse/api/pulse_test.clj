(ns ^:mb/driver-tests metabase.pulse.api.pulse-test
  "Tests for /api/pulse endpoints."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase.api.response :as api.response]
   [metabase.channel.api.channel-test :as api.channel-test]
   [metabase.channel.impl.http-test :as channel.http-test]
   [metabase.channel.render.style :as style]
   [metabase.channel.settings :as channel.settings]
   [metabase.driver :as driver]
   [metabase.notification.test-util :as notification.tu]
   [metabase.permissions.models.permissions :as perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   ^{:clj-kondo/ignore [:deprecated-namespace]}
   [metabase.pulse.api.pulse :as api.pulse]
   [metabase.pulse.models.pulse-channel :as pulse-channel]
   [metabase.pulse.models.pulse-test :as pulse-test]
   [metabase.pulse.test-util :as pulse.test-util]
   [metabase.queries.api.card-test :as api.card-test]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.test.http-client :as client]
   [metabase.test.mock.util :refer [pulse-channel-defaults]]
   [metabase.util :as u]
   [toucan2.core :as t2]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Helper Fns & Macros                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- user-details [user]
  (select-keys
   user
   [:email :first_name :last_login :is_qbnewb :is_superuser :id :last_name :date_joined :common_name :locale :tenant_id]))

(defn- pulse-card-details [card]
  (-> (select-keys card [:id :collection_id :name :description :display])
      (update :display name)
      (update :collection_id boolean)
      ;; why? these fields in this last assoc are from the PulseCard model and this function takes the Card model
      ;; because PulseCard is somewhat hidden behind the scenes
      (assoc :include_csv false :include_xls false :dashboard_card_id nil :dashboard_id nil
             :format_rows true :pivot_results false
             :parameter_mappings nil)))

(defn- pulse-channel-details [channel]
  (select-keys channel [:schedule_type :schedule_details :channel_type :updated_at :details :pulse_id :id :enabled
                        :created_at]))

(defn- pulse-details [pulse]
  (merge
   (select-keys
    pulse
    [:id :name :created_at :updated_at :creator_id :collection_id :collection_position :entity_id :archived
     :skip_if_empty :dashboard_id :parameters])
   {:creator  (user-details (t2/select-one 'User :id (:creator_id pulse)))
    :cards    (map pulse-card-details (:cards pulse))
    :channels (map pulse-channel-details (:channels pulse))}))

(defn- pulse-response [{:keys [created_at updated_at], :as pulse}]
  (-> pulse
      (dissoc :id)
      (assoc :created_at (some? created_at)
             :updated_at (some? updated_at))
      (update :collection_id boolean)
      (update :entity_id boolean)
      (update :cards #(for [card %]
                        (update card :collection_id boolean)))))

(defn- do-with-pulses-in-a-collection! [grant-collection-perms-fn! pulses-or-ids f]
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp [:model/Collection collection]
      (grant-collection-perms-fn! (perms-group/all-users) collection)
      ;; use db/execute! instead of t2/update! so the updated_at field doesn't get automatically updated!
      (when (seq pulses-or-ids)
        (t2/query-one {:update :pulse
                       :set    {:collection_id (u/the-id collection)}
                       :where  [:in :id (set (map u/the-id pulses-or-ids))]}))
      (f))))

(defmacro ^:private with-pulses-in-nonreadable-collection! [pulses-or-ids & body]
  `(do-with-pulses-in-a-collection! (constantly nil) ~pulses-or-ids (fn [] ~@body)))

(defmacro ^:private with-pulses-in-readable-collection! [pulses-or-ids & body]
  `(do-with-pulses-in-a-collection! perms/grant-collection-read-permissions! ~pulses-or-ids (fn [] ~@body)))

(defmacro ^:private with-pulses-in-writeable-collection! [pulses-or-ids & body]
  `(do-with-pulses-in-a-collection! perms/grant-collection-readwrite-permissions! ~pulses-or-ids (fn [] ~@body)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       /api/pulse/* AUTHENTICATION Tests                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; We assume that all endpoints for a given context are enforced by the same middleware, so we don't run the same
;; authentication test on every single individual endpoint

(deftest authentication-test
  (is (= (:body api.response/response-unauthentic) (client/client :get 401 "pulse")))
  (is (= (:body api.response/response-unauthentic) (client/client :put 401 "pulse/13"))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                POST /api/pulse                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private default-post-card-ref-validation-error
  {:errors
   {:cards (str "one or more value must be a map with the following keys "
                "`(collection_id, description, display, id, include_csv, include_xls, name, dashboard_id, parameter_mappings)`, "
                "or value must be a map with the keys `id`, `include_csv`, `include_xls`, and `dashboard_card_id`., "
                "or value must be a map with the keys `include_csv`, `include_xls`, and `dashboard_card_id`.")}})

(deftest create-pulse-validation-test
  (doseq [[input expected-error]
          {{}
           {:errors {:name "value must be a non-blank string."}
            :specific-errors {:name ["missing required key, received: nil"]}}

           {:name "abc"}
           default-post-card-ref-validation-error

           {:name  "abc"
            :cards "foobar"}
           default-post-card-ref-validation-error

           {:name  "abc"
            :cards ["abc"]}
           default-post-card-ref-validation-error

           {:name  "abc"
            :cards [{:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                    {:id 200, :include_csv false, :include_xls false, :dashboard_card_id nil}]}
           {:errors {:channels "one or more map"}}

           {:name     "abc"
            :cards    [{:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                       {:id 200, :include_csv false, :include_xls false, :dashboard_card_id nil}]
            :channels "foobar"}
           {:errors {:channels "one or more map"}}

           {:name     "abc"
            :cards    [{:id 100, :include_csv false, :include_xls false, :dashboard_card_id nil}
                       {:id 200, :include_csv false, :include_xls false, :dashboard_card_id nil}]
            :channels ["abc"]}
           {:errors {:channels "one or more map"}}}]
    (testing (pr-str input)
      (is (=? expected-error
              (mt/user-http-request :rasta :post 400 "pulse" input))))))

(defn- remove-extra-channels-fields [channels]
  (for [channel channels]
    (-> channel
        (dissoc :id :pulse_id :created_at :updated_at)
        (update :entity_id boolean))))

(def pulse-defaults
  {:collection_id       nil
   :collection_position nil
   :created_at          true
   :skip_if_empty       false
   :updated_at          true
   :archived            false
   :dashboard_id        nil
   :entity_id           true
   :parameters          []})

(def ^:private daily-email-channel
  {:enabled       true
   :channel_type  "email"
   :schedule_type "daily"
   :schedule_hour 12
   :schedule_day  nil
   :recipients    []})

(def pulse-channel-email-default
  {:enabled        true
   :channel_type   "email"
   :channel_id     nil
   :schedule_type  "hourly"})

(def pulse-channel-slack-test
  {:enabled        true
   :channel_type   "slack"
   :channel_id     nil
   :schedule_type  "hourly"
   :details        {:channels "#general"}})

(deftest create-test
  (testing "POST /api/pulse"
    (testing "legacy pulse"
      (mt/with-temp [:model/Card card-1 {}
                     :model/Card card-2 {}
                     :model/Dashboard _ {:name "Birdcage KPIs"}
                     :model/Collection collection {}]
        (api.card-test/with-cards-in-readable-collection! [card-1 card-2]
          (mt/with-model-cleanup [:model/Pulse]
            (is (= (merge
                    pulse-defaults
                    {:name          "A Pulse"
                     :creator_id    (mt/user->id :rasta)
                     :creator       (user-details (mt/fetch-user :rasta))
                     :cards         (for [card [card-1 card-2]]
                                      (assoc (pulse-card-details card)
                                             :collection_id true))
                     :channels      [(merge pulse-channel-defaults
                                            {:channel_type  "email"
                                             :schedule_type "daily"
                                             :schedule_hour 12
                                             :recipients    []})]
                     :collection_id true})
                   (-> (mt/user-http-request :rasta :post 200 "pulse" {:name          "A Pulse"
                                                                       :collection_id (u/the-id collection)
                                                                       :cards         [{:id                (u/the-id card-1)
                                                                                        :include_csv       false
                                                                                        :include_xls       false
                                                                                        :dashboard_card_id nil}
                                                                                       {:id                (u/the-id card-2)
                                                                                        :include_csv       false
                                                                                        :include_xls       false
                                                                                        :dashboard_card_id nil}]
                                                                       :channels      [daily-email-channel]
                                                                       :skip_if_empty false})
                       pulse-response
                       (update :channels remove-extra-channels-fields))))))))
    (testing "dashboard subscriptions"
      (mt/with-temp
        [:model/Collection collection                   {}
         :model/Card       card-1                       {}
         :model/Card       card-2                       {}
         :model/Dashboard  {permitted-dashboard-id :id} {:name "Birdcage KPIs" :collection_id (u/the-id collection)}
         :model/Dashboard  {blocked-dashboard-id :id}   {:name "[redacted]"}]
        (let [filter-params [{:id "abc123" :name "test" :type "date"}]
              payload       {:name          "A Pulse"
                             :collection_id (u/the-id collection)
                             :cards         [{:id                (u/the-id card-1)
                                              :include_csv       false
                                              :include_xls       false
                                              :dashboard_card_id nil}
                                             {:id                (u/the-id card-2)
                                              :include_csv       false
                                              :include_xls       false
                                              :dashboard_card_id nil}]
                             :channels      [daily-email-channel]
                             :dashboard_id  permitted-dashboard-id
                             :skip_if_empty false
                             :parameters filter-params}]
          (api.card-test/with-cards-in-readable-collection! [card-1 card-2]
            (mt/with-model-cleanup [:model/Pulse]
              (testing "successful creation"
                (is (= (merge
                        pulse-defaults
                        {:name          "A Pulse"
                         :creator_id    (mt/user->id :rasta)
                         :creator       (user-details (mt/fetch-user :rasta))
                         :cards         (for [card [card-1 card-2]]
                                          (assoc (pulse-card-details card)
                                                 :collection_id true))
                         :channels      [(merge pulse-channel-defaults
                                                {:channel_type  "email"
                                                 :schedule_type "daily"
                                                 :schedule_hour 12
                                                 :recipients    []})]
                         :collection_id true
                         :dashboard_id  permitted-dashboard-id
                         :parameters  filter-params})
                       (-> (mt/user-http-request :rasta :post 200 "pulse" payload)
                           pulse-response
                           (update :channels remove-extra-channels-fields)))))
              (testing "authorization"
                (is (= "You don't have permissions to do that."
                       (mt/user-http-request :rasta :post 403 "pulse" (assoc payload :dashboard_id blocked-dashboard-id))))))))))))

(deftest create-with-hybrid-pulse-card-test
  (testing "POST /api/pulse"
    (testing "Create a pulse with a HybridPulseCard and a CardRef, PUT accepts this format, we should make sure POST does as well"
      (mt/with-temp [:model/Card card-1 {}
                     :model/Card card-2 {:name        "The card"
                                         :description "Info"
                                         :display     :table}]
        (api.card-test/with-cards-in-readable-collection! [card-1 card-2]
          (mt/with-temp [:model/Collection collection]
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
            (mt/with-model-cleanup [:model/Pulse]
              (is (= (merge
                      pulse-defaults
                      {:name          "A Pulse"
                       :creator_id    (mt/user->id :rasta)
                       :creator       (user-details (mt/fetch-user :rasta))
                       :cards         (for [card [card-1 card-2]]
                                        (assoc (pulse-card-details card)
                                               :collection_id true))
                       :channels      [(merge pulse-channel-defaults
                                              {:channel_type  "email"
                                               :schedule_type "daily"
                                               :schedule_hour 12
                                               :recipients    []})]
                       :collection_id true})
                     (-> (mt/user-http-request :rasta :post 200 "pulse" {:name          "A Pulse"
                                                                         :collection_id (u/the-id collection)
                                                                         :cards         [{:id                (u/the-id card-1)
                                                                                          :include_csv       false
                                                                                          :include_xls       false
                                                                                          :dashboard_card_id nil}
                                                                                         (-> card-2
                                                                                             (select-keys [:id :name :description :display :collection_id])
                                                                                             (assoc :include_csv false, :include_xls false, :dashboard_id nil,
                                                                                                    :dashboard_card_id nil, :parameter_mappings nil))]
                                                                         :channels      [daily-email-channel]
                                                                         :skip_if_empty false})
                         pulse-response
                         (update :channels remove-extra-channels-fields)))))))))))

(deftest create-csv-xls-test
  (testing "POST /api/pulse"
    (testing "Create a pulse with a csv and xls"
      (mt/with-temp [:model/Card card-1 {}
                     :model/Card card-2] {}
        (mt/with-non-admin-groups-no-root-collection-perms
          (mt/with-temp [:model/Collection collection]
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
            (mt/with-model-cleanup [:model/Pulse]
              (api.card-test/with-cards-in-readable-collection! [card-1 card-2]
                (is (= (merge
                        pulse-defaults
                        {:name          "A Pulse"
                         :creator_id    (mt/user->id :rasta)
                         :creator       (user-details (mt/fetch-user :rasta))
                         :cards         [(assoc (pulse-card-details card-1) :include_csv true, :include_xls true, :collection_id true, :dashboard_card_id nil)
                                         (assoc (pulse-card-details card-2) :collection_id true)]
                         :channels      [(merge pulse-channel-defaults
                                                {:channel_type  "email"
                                                 :schedule_type "daily"
                                                 :schedule_hour 12
                                                 :recipients    []})]
                         :collection_id true})
                       (-> (mt/user-http-request :rasta :post 200 "pulse" {:name          "A Pulse"
                                                                           :collection_id (u/the-id collection)
                                                                           :cards         [{:id                (u/the-id card-1)
                                                                                            :include_csv       true
                                                                                            :include_xls       true
                                                                                            :format_rows       true
                                                                                            :dashboard_card_id nil}
                                                                                           {:id                (u/the-id card-2)
                                                                                            :include_csv       false
                                                                                            :include_xls       false
                                                                                            :format_rows       true
                                                                                            :dashboard_card_id nil}]
                                                                           :channels      [daily-email-channel]
                                                                           :skip_if_empty false})
                           pulse-response
                           (update :channels remove-extra-channels-fields))))))))))))

(deftest create-with-collection-position-test
  (testing "POST /api/pulse"
    (testing "Make sure we can create a Pulse with a Collection position"
      (mt/with-model-cleanup [:model/Pulse]
        (letfn [(create-pulse! [expected-status-code pulse-name card collection]
                  (let [response (mt/user-http-request :rasta :post expected-status-code "pulse"
                                                       {:name                pulse-name
                                                        :cards               [{:id                (u/the-id card)
                                                                               :include_csv       false
                                                                               :include_xls       false
                                                                               :dashboard_card_id nil}]
                                                        :channels            [daily-email-channel]
                                                        :skip_if_empty       false
                                                        :collection_id       (u/the-id collection)
                                                        :collection_position 1})]
                    (testing "response"
                      (is (= nil
                             (:errors response))))))]
          (let [pulse-name (mt/random-name)]
            (mt/with-temp [:model/Card       card {}
                           :model/Collection collection] {}
              (api.card-test/with-cards-in-readable-collection! [card]
                (create-pulse! 200 pulse-name card collection)
                (is (= {:collection_id (u/the-id collection), :collection_position 1}
                       (mt/derecordize (t2/select-one [:model/Pulse :collection_id :collection_position] :name pulse-name)))))))

          (testing "...but not if we don't have permissions for the Collection"
            (mt/with-non-admin-groups-no-root-collection-perms
              (let [pulse-name (mt/random-name)]
                (mt/with-temp [:model/Card       card {}
                               :model/Collection collection] {}
                  (create-pulse! 403 pulse-name card collection)
                  (is (= nil
                         (t2/select-one [:model/Pulse :collection_id :collection_position] :name pulse-name))))))))))))

(deftest validate-email-domains-test
  (mt/when-ee-evailable
   (mt/with-model-cleanup [:model/Pulse]
     (mt/with-premium-features #{:email-allow-list}
       (mt/with-temporary-setting-values [subscription-allowed-domains "example.com"]
         (mt/with-temp [:model/Dashboard {dashboard-id :id} {:name "Test Dashboard"}
                        :model/Card      {card-id :id} {}]
           (let [pulse {:name          "Test Pulse"
                        :dashboard_id  dashboard-id
                        :cards         [{:id                card-id
                                         :include_csv       false
                                         :include_xls       false
                                         :dashboard_card_id nil}]
                        :channels      [{:channel_type  "email"
                                         :schedule_type "daily"
                                         :schedule_hour 12
                                         :recipients    []
                                         :enabled       true}]}
                 failed-recipients [{:email "ngoc@metabase.com"}
                                    {:email "ngoc@metaba.be"}]
                 success-recipients [{:email "ngoc@example.com"}]]
             (testing "on creation"
               (testing "fail if recipients does not match allowed domains"
                 (is (= "The following email addresses are not allowed: ngoc@metabase.com, ngoc@metaba.be"
                        (mt/user-http-request :crowberto :post 403 "pulse"
                                              (assoc-in pulse [:channels 0 :recipients] failed-recipients)))))

               (testing "success if recipients matches allowed domains"
                 (mt/user-http-request :crowberto :post 200 "pulse"
                                       (assoc-in pulse [:channels 0 :recipients] success-recipients))))

             (testing "on update"
               (mt/with-temp [:model/Pulse {pulse-id :id} {:name          "Test Pulse"
                                                           :dashboard_id  dashboard-id}]
                 (testing "fail if recipients does not match allowed domains"
                   (is (= "The following email addresses are not allowed: ngoc@metabase.com, ngoc@metaba.be"
                          (mt/user-http-request :crowberto :put 403 (format "pulse/%d" pulse-id)
                                                (assoc-in pulse [:channels 0 :recipients] failed-recipients)))))

                 (testing "success if recipients matches allowed domains"
                   (mt/user-http-request :crowberto :put 200 (format "pulse/%d" pulse-id)
                                         (assoc-in pulse [:channels 0 :recipients] success-recipients)))))

             (testing "on test send"
               (testing "fail if recipients does not match allowed domains"
                 (is (= "The following email addresses are not allowed: ngoc@metabase.com, ngoc@metaba.be"
                        (mt/user-http-request :crowberto :post 403 "pulse/test"
                                              (assoc-in pulse [:channels 0 :recipients] failed-recipients)))))

               (testing "success if recipients matches allowed domains"
                 (mt/user-http-request :crowberto :post 200 "pulse/test"
                                       (assoc-in pulse [:channels 0 :recipients] success-recipients)))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               PUT /api/pulse/:id                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private default-put-card-ref-validation-error
  {:errors
   {:cards (str "nullable one or more value must be a map with the following keys "
                "`(collection_id, description, display, id, include_csv, include_xls, name, dashboard_id, parameter_mappings)`, "
                "or value must be a map with the keys `id`, `include_csv`, `include_xls`, and `dashboard_card_id`., "
                "or value must be a map with the keys `include_csv`, `include_xls`, and `dashboard_card_id`.")}})

(deftest update-pulse-validation-test
  (testing "PUT /api/pulse/:id"
    (doseq [[input expected-error]
            {{:name 123}
             {:errors {:name "nullable value must be a non-blank string."},
              :specific-errors {:name ["should be a string, received: 123" "non-blank string, received: 123"]}}

             {:cards 123}
             default-put-card-ref-validation-error

             {:cards "foobar"}
             default-put-card-ref-validation-error

             {:cards ["abc"]}
             default-put-card-ref-validation-error

             {:channels 123}
             {:errors {:channels "nullable one or more map"}}

             {:channels "foobar"}
             {:errors {:channels "nullable one or more map"}}

             {:channels ["abc"]}
             {:errors {:channels "nullable one or more map"}}}]
      (testing (pr-str input)
        (is (=? expected-error
                (mt/user-http-request :rasta :put 400 "pulse/1" input)))))))

(deftest update-test
  (testing "PUT /api/pulse/:id"
    (mt/with-temp [:model/Pulse                 pulse {}
                   :model/PulseChannel          pc    {:pulse_id (u/the-id pulse)}
                   :model/PulseChannelRecipient _     {:pulse_channel_id (u/the-id pc) :user_id (mt/user->id :rasta)}
                   :model/Card                  card  {}]
      (let [filter-params [{:id "123abc", :name "species", :type "string"}]]
        (with-pulses-in-writeable-collection! [pulse]
          (api.card-test/with-cards-in-readable-collection! [card]
            (is (= (merge
                    pulse-defaults
                    {:name          "Updated Pulse"
                     :creator_id    (mt/user->id :rasta)
                     :creator       (user-details (mt/fetch-user :rasta))
                     :cards         [(assoc (pulse-card-details card)
                                            :collection_id true)]
                     :channels      [(merge pulse-channel-defaults
                                            {:channel_type  "slack"
                                             :schedule_type "hourly"
                                             :details       {:channels "#general"}
                                             :recipients    []})]
                     :collection_id true
                     :parameters    filter-params})
                   (-> (mt/user-http-request :rasta :put 200 (format "pulse/%d" (u/the-id pulse))
                                             {:name          "Updated Pulse"
                                              :cards         [{:id                (u/the-id card)
                                                               :include_csv       false
                                                               :include_xls       false
                                                               :dashboard_card_id nil}]
                                              :channels      [{:enabled       true
                                                               :channel_type  "slack"
                                                               :schedule_type "hourly"
                                                               :schedule_hour 12
                                                               :schedule_day  "mon"
                                                               :recipients    []
                                                               :details       {:channels "#general"}}]
                                              :skip_if_empty false
                                              :parameters    filter-params})
                       pulse-response
                       (update :channels remove-extra-channels-fields))))))))))

(deftest add-card-to-existing-test
  (testing "PUT /api/pulse/:id"
    (testing "Can we add a card to an existing pulse that has a card?"
      ;; Specifically this will include a HybridPulseCard (the original card associated with the pulse) and a CardRef
      ;; (the new card)
      (mt/with-temp [:model/Pulse                 pulse {:name "Original Pulse Name"}
                     :model/Card                  card-1 {:name        "Test"
                                                          :description "Just Testing"}
                     :model/PulseCard             _      {:card_id  (u/the-id card-1)
                                                          :pulse_id (u/the-id pulse)}
                     :model/Card                  card-2 {:name        "Test2"
                                                          :description "Just Testing2"}]
        (with-pulses-in-writeable-collection! [pulse]
          (api.card-test/with-cards-in-readable-collection! [card-1 card-2]
            ;; The FE will include the original HybridPulseCard, similar to how the API returns the card via GET
            (let [pulse-cards (:cards (mt/user-http-request :rasta :get 200 (format "pulse/%d" (u/the-id pulse))))]
              (is (= (merge
                      pulse-defaults
                      {:name          "Original Pulse Name"
                       :creator_id    (mt/user->id :rasta)
                       :creator       (user-details (mt/fetch-user :rasta))
                       :cards         (mapv (comp #(assoc % :collection_id true) pulse-card-details) [card-1 card-2])
                       :channels      []
                       :collection_id true})
                     (-> (mt/user-http-request :rasta :put 200 (format "pulse/%d" (u/the-id pulse))
                                               {:cards (concat pulse-cards
                                                               [{:id                (u/the-id card-2)
                                                                 :include_csv       false
                                                                 :include_xls       false
                                                                 :dashboard_card_id nil}])})
                         pulse-response
                         (update :channels remove-extra-channels-fields)))))))))))

(deftest update-collection-id-test
  (testing "Can we update *just* the Collection ID of a Pulse?"
    (mt/with-temp [:model/Pulse      pulse {}
                   :model/Collection collection] {}
      (mt/user-http-request :crowberto :put 200 (str "pulse/" (u/the-id pulse))
                            {:collection_id (u/the-id collection)})
      (is (= (t2/select-one-fn :collection_id :model/Pulse :id (u/the-id pulse))
             (u/the-id collection))))))

(deftest change-collection-test
  (testing "Can we change the Collection a Pulse is in (assuming we have the permissions to do so)?"
    (pulse-test/with-pulse-in-collection! [_db collection pulse]
      (mt/with-temp [:model/Collection new-collection]
        ;; grant Permissions for both new and old collections
        (doseq [coll [collection new-collection]]
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) coll))
        ;; now make an API call to move collections
        (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse)) {:collection_id (u/the-id new-collection)})
        ;; Check to make sure the ID has changed in the DB
        (is (= (t2/select-one-fn :collection_id :model/Pulse :id (u/the-id pulse))
               (u/the-id new-collection)))))

    (testing "...but if we don't have the Permissions for the old collection, we should get an Exception"
      (pulse-test/with-pulse-in-collection! [_db _collection pulse]
        (mt/with-temp [:model/Collection new-collection]
          ;; grant Permissions for only the *new* collection
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) new-collection)
          ;; now make an API call to move collections. Should fail
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :put 403 (str "pulse/" (u/the-id pulse)) {:collection_id (u/the-id new-collection)}))))))

    (testing "...and if we don't have the Permissions for the new collection, we should get an Exception"
      (pulse-test/with-pulse-in-collection! [_db collection pulse]
        (mt/with-temp [:model/Collection new-collection]
          ;; grant Permissions for only the *old* collection
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
          ;; now make an API call to move collections. Should fail
          (is (=? {:message "You do not have curate permissions for this Collection."}
                  (mt/user-http-request :rasta :put 403 (str "pulse/" (u/the-id pulse)) {:collection_id (u/the-id new-collection)}))))))))

(deftest update-collection-position-test
  (testing "Can we change the Collection position of a Pulse?"
    (pulse-test/with-pulse-in-collection! [_ collection pulse]
      (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
      (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                            {:collection_position 1})
      (is (= 1
             (t2/select-one-fn :collection_position :model/Pulse :id (u/the-id pulse)))))

    (testing "...and unset (unpin) it as well?"
      (pulse-test/with-pulse-in-collection! [_ collection pulse]
        (t2/update! :model/Pulse (u/the-id pulse) {:collection_position 1})
        (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
        (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                              {:collection_position nil})
        (is (= nil
               (t2/select-one-fn :collection_position :model/Pulse :id (u/the-id pulse))))))

    (testing "...we shouldn't be able to if we don't have permissions for the Collection"
      (pulse-test/with-pulse-in-collection! [_db _collection pulse]
        (mt/user-http-request :rasta :put 403 (str "pulse/" (u/the-id pulse))
                              {:collection_position 1})
        (is (= nil
               (t2/select-one-fn :collection_position :model/Pulse :id (u/the-id pulse))))

        (testing "shouldn't be able to unset (unpin) a Pulse"
          (t2/update! :model/Pulse (u/the-id pulse) {:collection_position 1})
          (mt/user-http-request :rasta :put 403 (str "pulse/" (u/the-id pulse))
                                {:collection_position nil})
          (is (= 1
                 (t2/select-one-fn :collection_position :model/Pulse :id (u/the-id pulse)))))))))

(deftest archive-test
  (testing "Can we archive a Pulse?"
    (pulse-test/with-pulse-in-collection! [_ collection pulse]
      (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
      (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                            {:archived true})
      (is (true?
           (t2/select-one-fn :archived :model/Pulse :id (u/the-id pulse)))))))

(deftest unarchive-test
  (testing "Can we unarchive a Pulse?"
    (pulse-test/with-pulse-in-collection! [_ collection pulse]
      (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
      (t2/update! :model/Pulse (u/the-id pulse) {:archived true})
      (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                            {:archived false})
      (is (= false
             (t2/select-one-fn :archived :model/Pulse :id (u/the-id pulse))))))

  (testing "Does unarchiving a Pulse affect its Cards & Recipients? It shouldn't. This should behave as a PATCH-style endpoint!"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp [:model/Collection            collection {}
                     :model/Pulse                 pulse {:collection_id (u/the-id collection)}
                     :model/PulseChannel          pc    {:pulse_id (u/the-id pulse)}
                     :model/PulseChannelRecipient pcr   {:pulse_channel_id (u/the-id pc) :user_id (mt/user->id :rasta)}
                     :model/Card                  _     {}]
        (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
        (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                              {:archived true})
        (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                              {:archived false})
        (is (t2/exists? :model/PulseChannel :id (u/the-id pc)))
        (is (t2/exists? :model/PulseChannelRecipient :id (u/the-id pcr)))))))

(deftest update-channels-no-op-test
  (testing "PUT /api/pulse/:id"
    (testing "If we PUT a Pulse with the same Channels, it should be a no-op"
      (mt/with-temp
        [:model/Pulse        {pulse-id :id} {}
         :model/PulseChannel pc             (assoc pulse-channel-email-default :pulse_id pulse-id)]
        (is (=? [(assoc pulse-channel-email-default :id (:id pc))]
                (:channels (mt/user-http-request :rasta :put 200 (str "pulse/" pulse-id)
                                                 {:channels [pc]}))))))))

(deftest update-channels-change-existing-channel-test
  (testing "PUT /api/pulse/:id"
    (testing "update the schedule of existing pulse channel"
      (mt/with-temp
        [:model/Pulse        {pulse-id :id} {}
         :model/PulseChannel pc             (assoc pulse-channel-email-default :pulse_id pulse-id)]
        (let [new-channel (assoc pulse-channel-email-default :id (:id pc) :schedule_type "daily" :schedule_hour 7)]
          (is (=? [new-channel]
                  (:channels (mt/user-http-request :rasta :put 200 (str "pulse/" pulse-id)
                                                   {:channels [new-channel]})))))))))

(deftest update-channels-add-new-channel-test
  (testing "PUT /api/pulse/:id"
    (testing "add a new pulse channel"
      (mt/with-temp
        [:model/Pulse        {pulse-id :id} {}
         :model/PulseChannel pc             (assoc pulse-channel-email-default :pulse_id pulse-id)]
        (is (=? [(assoc pulse-channel-email-default :id (:id pc))
                 pulse-channel-slack-test]
                (->> (mt/user-http-request :rasta :put 200 (str "pulse/" pulse-id)
                                           {:channels [pulse-channel-slack-test pc]})
                     :channels
                     (sort-by :channel_type))))))))

(deftest update-channels-add-multiple-channels-of-the-same-type-test
  (testing "PUT /api/pulse/:id"
    (testing "add multiple pulse channels of the same type and disable an existing channel"
      (mt/with-temp
        [:model/Channel      {chn-1 :id}    api.channel-test/default-test-channel
         :model/Channel      {chn-2 :id}    (assoc api.channel-test/default-test-channel :name "Test channel 2")
         :model/Pulse        {pulse-id :id} {}
         :model/PulseChannel pc-email       (assoc pulse-channel-email-default :pulse_id pulse-id)
         :model/PulseChannel pc-slack       (assoc pulse-channel-slack-test :pulse_id pulse-id)]
        (is (=? [(assoc pulse-channel-email-default :enabled false)
                 {:channel_type "http"
                  :channel_id   chn-1
                  :enabled      true
                  :schedule_type "hourly"}
                 {:channel_type "http"
                  :channel_id   chn-2
                  :enabled      true
                  :schedule_type "hourly"}
                 pulse-channel-slack-test]
                (->> (mt/user-http-request :rasta :put 200 (str "pulse/" pulse-id)
                                           {:channels [(assoc pc-email :enabled false)
                                                       pc-slack
                                                       {:channel_type "http"
                                                        :channel_id   chn-1
                                                        :enabled      true
                                                        :schedule_type "hourly"}
                                                       {:channel_type "http"
                                                        :channel_id   chn-2
                                                        :enabled      true
                                                        :schedule_type "hourly"}]})
                     :channels
                     (sort-by (juxt :channel_type :channel_id)))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                   UPDATING PULSE COLLECTION POSITIONS                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti ^:private move-pulse-test-action
  {:arglists '([action context & args])}
  (fn [action & _]
    action))

(defmethod move-pulse-test-action :move
  [_ context pulse & {:keys [collection position]}]
  (let [pulse    (get-in context [:pulse pulse])
        response (mt/user-http-request :rasta :put 200 (str "pulse/" (u/the-id pulse))
                                       (merge
                                        (when collection
                                          {:collection_id (u/the-id (get-in context [:collection collection]))})
                                        (when position
                                          {:collection_position position})))]
    (is (= nil
           (:errors response)))))

(defmethod move-pulse-test-action :insert-pulse
  [_ context collection & {:keys [position]}]
  (let [collection (get-in context [:collection collection])
        response   (mt/user-http-request :rasta :post 200 "pulse"
                                         (merge
                                          {:name          "x"
                                           :collection_id (u/the-id collection)
                                           :cards         [{:id                (u/the-id (get-in context [:card 1]))
                                                            :include_csv       false
                                                            :include_xls       false
                                                            :dashboard_card_id nil}]
                                           :channels      [daily-email-channel]
                                           :skip_if_empty false}
                                          (when position
                                            {:collection_position position})))]
    (is (= nil
           (:errors response)))))

(def ^:private move-test-definitions
  [{:message  "Check that we can update a Pulse's position in a Collection"
    :action   [:move :d :position 1]
    :expected {"d" 1
               "a" 2
               "b" 3
               "c" 4}}
   {:message  "Change the position of b to 4, will dec c and d"
    :action   [:move :b :position 4]
    :expected {"a" 1
               "c" 2
               "d" 3
               "b" 4}}
   {:message  "Change the position of d to 2, should inc b and c"
    :action   [:move :d :position 2]
    :expected {"a" 1
               "d" 2
               "b" 3
               "c" 4}}
   {:message  "Change the position of a to 4th, will decrement all existing items"
    :action   [:move :a :position 4]
    :expected {"b" 1
               "c" 2
               "d" 3
               "a" 4}}
   {:message  "Change the position of the d to the 1st, will increment all existing items"
    :action   [:move :d :position 1]
    :expected {"d" 1
               "a" 2
               "b" 3
               "c" 4}}
   {:message  (str "Check that no position change, but changing collections still triggers a fixup of both "
                   "collections Moving `c` from collection-1 to collection-2, `c` is now at position 3 in "
                   "collection 2")
    :action   [:move :c :collection 2]
    :expected [{"a" 1
                "b" 2
                "d" 3}
               {"e" 1
                "f" 2
                "c" 3
                "g" 4
                "h" 5}]}
   {:message  (str "Check that moving a pulse to another collection, with a changed position will fixup "
                   "both collections Moving `b` to collection 2, giving it a position of 1")
    :action   [:move :b :collection 2, :position 1]
    :expected [{"a" 1
                "c" 2
                "d" 3}
               {"b" 1
                "e" 2
                "f" 3
                "g" 4
                "h" 5}]}
   {:message "Add a new pulse at position 2, causing existing pulses to be incremented"
    :action [:insert-pulse 1 :position 2]
    :expected {"a" 1
               "x" 2
               "b" 3
               "c" 4
               "d" 5}}

   {:message  "Add a new pulse without a position, should leave existing positions unchanged"
    :action   [:insert-pulse 1]
    :expected {"x" nil
               "a" 1
               "b" 2
               "c" 3
               "d" 4}}])

(deftest move-pulse-test
  (testing "PUT /api/pulse/:id"
    (doseq [{:keys [message action expected]} move-test-definitions
            :let                              [expected (if (map? expected) [expected] expected)]]
      (testing (str "\n" message)
        (mt/with-temp [:model/Collection collection-1 {}
                       :model/Collection collection-2 {}
                       :model/Card       card-1  {}]
          (api.card-test/with-ordered-items collection-1 [:model/Pulse a
                                                          :model/Pulse b
                                                          :model/Pulse c
                                                          :model/Pulse d]
            (api.card-test/with-ordered-items collection-2 [:model/Card      e
                                                            :model/Card      f
                                                            :model/Dashboard g
                                                            :model/Dashboard h]
              (let [[action & args] action
                    context         {:pulse      {:a a, :b b, :c c, :d d, :e e, :f f, :g g, :h h}
                                     :collection {1 collection-1, 2 collection-2}
                                     :card       {1 card-1}}]
                (testing (str "\n" (pr-str (cons action args)))
                  (apply move-pulse-test-action action context args)))
              (testing "\nPositions after actions for"
                (testing "Collection 1"
                  (is (= (first expected)
                         (api.card-test/get-name->collection-position :rasta (u/the-id collection-1)))))
                (when (second expected)
                  (testing "Collection 2"
                    (is (= (second expected)
                           (api.card-test/get-name->collection-position :rasta (u/the-id collection-2))))))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 GET /api/pulse                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- filter-pulse-results
  "Filters a list of pulse results based on a set of expected values for a given field."
  [results field expected]
  (filter
   (fn [pulse] ((set expected) (field pulse)))
   results))

(deftest list-test
  (testing "GET /api/pulse"
    ;; pulse-1 => created by non-admin
    ;; pulse-2 => created by admin
    ;; pulse-3 => created by admin; non-admin recipient
    (mt/with-temp [:model/Dashboard             {dashboard-id :id} {}
                   :model/Pulse                 {pulse-1-id :id :as pulse-1} {:name         "ABCDEF"
                                                                              :dashboard_id dashboard-id
                                                                              :creator_id   (mt/user->id :rasta)}
                   :model/Pulse                 {pulse-2-id :id :as pulse-2} {:name         "GHIJKL"
                                                                              :dashboard_id dashboard-id
                                                                              :creator_id   (mt/user->id :crowberto)}
                   :model/Pulse                 {pulse-3-id :id :as pulse-3} {:name         "MNOPQR"
                                                                              :dashboard_id dashboard-id
                                                                              :creator_id   (mt/user->id :crowberto)}
                   :model/PulseChannel          pc {:pulse_id pulse-3-id}
                   :model/PulseChannelRecipient _  {:pulse_channel_id (u/the-id pc)
                                                    :user_id          (mt/user->id :rasta)}]
      (with-pulses-in-writeable-collection! [pulse-1 pulse-2 pulse-3]
        (testing "admins can see all pulses"
          (let [results (-> (mt/user-http-request :crowberto :get 200 "pulse")
                            (filter-pulse-results :id #{pulse-1-id pulse-2-id pulse-3-id}))]
            (is (= 3 (count results)))
            (is (partial=
                 [(assoc (pulse-details pulse-1) :can_write true, :collection_id true)
                  (assoc (pulse-details pulse-2) :can_write true, :collection_id true)
                  (assoc (pulse-details pulse-3) :can_write true, :collection_id true)]
                 (map #(update % :collection_id boolean) results)))))

        (testing "non-admins only see pulses they created by default"
          (let [results (-> (mt/user-http-request :rasta :get 200 "pulse")
                            (filter-pulse-results :id #{pulse-1-id pulse-2-id pulse-3-id}))]
            (is (= 1 (count results)))
            (is (partial=
                 [(assoc (pulse-details pulse-1) :can_write true, :collection_id true)]
                 (map #(update % :collection_id boolean) results)))))

        (testing "when `creator_or_recipient=true`, all users only see pulses they created or are a recipient of"
          (let [expected-pulse-shape (fn [pulse] (-> pulse
                                                     pulse-details
                                                     (assoc :can_write true, :collection_id true)
                                                     (dissoc :cards)))]
            (let [results (-> (mt/user-http-request :crowberto :get 200 "pulse?creator_or_recipient=true")
                              (filter-pulse-results :id #{pulse-1-id pulse-2-id pulse-3-id}))]
              (is (= 2 (count results)))
              (is (partial=
                   [(expected-pulse-shape pulse-2) (expected-pulse-shape pulse-3)]
                   (map #(update % :collection_id boolean) results))))

            (let [results (-> (mt/user-http-request :rasta :get 200 "pulse?creator_or_recipient=true")
                              (filter-pulse-results :id #{pulse-1-id pulse-2-id pulse-3-id}))]
              (is (= 2 (count results)))
              (is (partial=
                   [(expected-pulse-shape pulse-1)
                    (assoc (expected-pulse-shape pulse-3) :can_write false)]
                   (map #(update % :collection_id boolean) results)))))))

      (with-pulses-in-nonreadable-collection! [pulse-3]
        (testing "when `creator_or_recipient=true`, cards and recipients are not included in results if the user
                 does not have collection perms"
          (let [result (-> (mt/user-http-request :rasta :get 200 "pulse?creator_or_recipient=true")
                           (filter-pulse-results :id #{pulse-3-id})
                           first)]
            (is (nil? (:cards result)))
            (is (nil? (get-in result [:channels 0 :recipients])))))))

    (testing "should not return alerts"
      (mt/with-temp [:model/Pulse pulse-1 {:name "ABCDEF"}
                     :model/Pulse pulse-2 {:name "GHIJKL"}
                     :model/Pulse pulse-3 {:name            "AAAAAA"
                                           :alert_condition "rows"}]
        (with-pulses-in-readable-collection! [pulse-1 pulse-2 pulse-3]
          (is (= [(assoc (pulse-details pulse-1) :can_write true, :collection_id true)
                  (assoc (pulse-details pulse-2) :can_write true, :collection_id true)]
                 (for [pulse (-> (mt/user-http-request :rasta :get 200 "pulse")
                                 (filter-pulse-results :name #{"ABCDEF" "GHIJKL" "AAAAAA"}))]
                   (update pulse :collection_id boolean)))))))

    (testing "by default, archived Pulses should be excluded"
      (mt/with-temp [:model/Pulse not-archived-pulse {:name "Not Archived"}
                     :model/Pulse archived-pulse     {:name "Archived" :archived true}]
        (with-pulses-in-readable-collection! [not-archived-pulse archived-pulse]
          (is (= #{"Not Archived"}
                 (set (map :name (-> (mt/user-http-request :rasta :get 200 "pulse")
                                     (filter-pulse-results :name #{"Not Archived" "Archived"})))))))))

    (testing "can we fetch archived Pulses?"
      (mt/with-temp [:model/Pulse not-archived-pulse {:name "Not Archived"}
                     :model/Pulse archived-pulse     {:name "Archived" :archived true}]
        (with-pulses-in-readable-collection! [not-archived-pulse archived-pulse]
          (is (= #{"Archived"}
                 (set (map :name (-> (mt/user-http-request :rasta :get 200 "pulse?archived=true")
                                     (filter-pulse-results :name #{"Not Archived" "Archived"})))))))))

    (testing "excludes dashboard subscriptions associated with archived dashboards"
      (mt/with-temp [:model/Dashboard {dashboard-id :id} {:archived true}
                     :model/Pulse     {pulse-id :id} {:dashboard_id dashboard-id}]
        (is (= [] (-> (mt/user-http-request :rasta :get 200 "pulse")
                      (filter-pulse-results :id #{pulse-id}))))))))

(deftest get-pulse-test
  (testing "GET /api/pulse/:id"
    (mt/with-temp [:model/Pulse pulse]
      (with-pulses-in-readable-collection! [pulse]
        (is (= (assoc (pulse-details pulse)
                      :can_write     true
                      :collection_id true)
               (-> (mt/user-http-request :rasta :get 200 (str "pulse/" (u/the-id pulse)))
                   (update :collection_id boolean))))))

    (testing "cannot normally fetch a pulse without collection permissions"
      (mt/with-temp [:model/Pulse pulse {:creator_id (mt/user->id :crowberto)}]
        (with-pulses-in-nonreadable-collection! [pulse]
          (mt/user-http-request :rasta :get 403 (str "pulse/" (u/the-id pulse))))))

    (testing "can fetch a pulse without collection permissions if you are the creator or a recipient"
      (mt/with-temp [:model/Pulse pulse {:creator_id (mt/user->id :rasta)}]
        (with-pulses-in-nonreadable-collection! [pulse]
          (mt/user-http-request :rasta :get 200 (str "pulse/" (u/the-id pulse)))))

      (mt/with-temp [:model/Pulse                 pulse {:creator_id (mt/user->id :crowberto)}
                     :model/PulseChannel          pc    {:pulse_id (u/the-id pulse)}
                     :model/PulseChannelRecipient _     {:pulse_channel_id (u/the-id pc)
                                                         :user_id          (mt/user->id :rasta)}]
        (with-pulses-in-nonreadable-collection! [pulse]
          (mt/user-http-request :rasta :get 200 (str "pulse/" (u/the-id pulse))))))))

(deftest send-test-pulse-test
  ;; see [[metabase-enterprise.advanced-config.api.pulse-test/test-pulse-endpoint-should-respect-email-domain-allow-list-test]]
  ;; for additional EE-specific tests
  (testing "POST /api/pulse/test"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-fake-inbox
        (mt/dataset sad-toucan-incidents
          (mt/with-temp [:model/Collection collection {}
                         :model/Dashboard {dashboard-id :id} {:name       "Daily Sad Toucans"
                                                              :parameters [{:name    "X"
                                                                            :slug    "x"
                                                                            :id      "__X__"
                                                                            :type    "category"
                                                                            :default 3}]}
                         :model/Card       card  {:dataset_query (mt/mbql-query incidents {:aggregation [[:count]]})}]
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
            (api.card-test/with-cards-in-readable-collection! [card]
              (let [channel-messages (pulse.test-util/with-captured-channel-send-messages!
                                       (is (= {:ok true}
                                              (mt/user-http-request :rasta :post 200 "pulse/test"
                                                                    {:name          (mt/random-name)
                                                                     :dashboard_id  dashboard-id
                                                                     :cards         [{:id                (:id card)
                                                                                      :include_csv       false
                                                                                      :include_xls       false
                                                                                      :dashboard_card_id nil}]
                                                                     :channels      [{:enabled       true
                                                                                      :channel_type  "email"
                                                                                      :schedule_type "daily"
                                                                                      :schedule_hour 12
                                                                                      :schedule_day  nil
                                                                                      :recipients    [(mt/fetch-user :rasta)]}]
                                                                     :skip_if_empty false}))))]
                (is (= {:message [{"Daily Sad Toucans" true}
                                  pulse.test-util/png-attachment]
                        :message-type :attachments,
                        :recipients #{"rasta@metabase.com"}
                        :subject "Daily Sad Toucans"
                        :recipient-type nil}
                       (mt/summarize-multipart-single-email (-> channel-messages :channel/email first) #"Daily Sad Toucans")))))))))))

(deftest send-test-pulse-to-non-user-test
  (testing "sending test email to non user won't include unsubscribe link (#43391)"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-fake-inbox
        (mt/dataset sad-toucan-incidents
          (mt/with-temp [:model/Collection collection {}
                         :model/Dashboard {dashboard-id :id} {:name       "Unsaved Subscription Test"
                                                              :parameters [{:name    "X"
                                                                            :slug    "x"
                                                                            :id      "__X__"
                                                                            :type    "category"
                                                                            :default 3}]}
                         :model/Card       card  {:dataset_query (mt/mbql-query incidents {:aggregation [[:count]]})}]
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
            (api.card-test/with-cards-in-readable-collection! [card]
              (let [channel-messages (pulse.test-util/with-captured-channel-send-messages!
                                       (is (= {:ok true}
                                              (mt/user-http-request :rasta :post 200 "pulse/test"
                                                                    {:name          (mt/random-name)
                                                                     :dashboard_id  dashboard-id
                                                                     :cards         [{:id                (:id card)
                                                                                      :include_csv       false
                                                                                      :include_xls       false
                                                                                      :dashboard_card_id nil}]
                                                                     :channels      [{:enabled       true
                                                                                      :channel_type  "email"
                                                                                      :schedule_type "daily"
                                                                                      :schedule_hour 12
                                                                                      :schedule_day  nil
                                                                                      :recipients    [{:email "nonuser@metabase.com"}]}]
                                                                     :skip_if_empty false}))))]
                (is (= {:message [{"Unsaved Subscription Test" true, "Unsubscribe" false}
                                  pulse.test-util/png-attachment]
                        :message-type :attachments,
                        :recipients #{"nonuser@metabase.com"}
                        :subject "Unsaved Subscription Test"
                        :recipient-type nil}
                       (mt/summarize-multipart-single-email (-> channel-messages :channel/email first) #"Unsaved Subscription Test" #"Unsubscribe")))))))))))

(deftest send-test-alert-with-http-channel-test
  (testing "POST /api/pulse/test send test alert to a http channel"
    (notification.tu/with-send-notification-sync
      (let [requests (atom [])
            endpoint (channel.http-test/make-route
                      :post "/test"
                      (fn [req]
                        (swap! requests conj req)
                        {:status 200
                         :body   "ok"}))]
        (channel.http-test/with-server [url [endpoint]]
          (mt/with-temp
            [:model/Card    card    {:dataset_query (mt/mbql-query orders {:aggregation [[:count]]})}
             :model/Channel channel {:type    :channel/http
                                     :details {:url         (str url "/test")
                                               :auth-method :none}}]
            (mt/user-http-request :rasta :post 200 "pulse/test"
                                  {:name            (mt/random-name)
                                   :cards           [{:id                (:id card)
                                                      :include_csv       false
                                                      :include_xls       false
                                                      :dashboard_card_id nil}]
                                   :channels        [{:enabled       true
                                                      :channel_type  "http"
                                                      :channel_id    (:id channel)
                                                      :schedule_type "daily"
                                                      :schedule_hour 12
                                                      :schedule_day  nil
                                                      :recipients    []}]
                                   :alert_condition "rows"})
            (is (=? {:body {:alert_creator_id   (mt/user->id :rasta)
                            :alert_creator_name "Rasta Toucan"
                            :alert_id           nil
                            :data               {:question_id   (:id card)
                                                 :question_name (mt/malli=? string?)
                                                 :question_url  (mt/malli=? string?)
                                                 :raw_data      {:cols ["count"], :rows [[18760]]},
                                                 :type          "question"
                                                 :visualization (mt/malli=? [:fn #(str/starts-with? % "data:image/png;base64,")])}
                            :type               "alert"}}
                    (first @requests)))))))))

(deftest send-test-pulse-validate-emails-test
  (testing (str "POST /api/pulse/test should call " `pulse-channel/validate-email-domains)
    (mt/with-temp [:model/Card card {:dataset_query (mt/mbql-query venues)}]
      (with-redefs [pulse-channel/validate-email-domains (fn [& _]
                                                           (throw (ex-info "Nope!" {:status-code 403})))]
        ;; make sure we validate raw emails whether they're part of `:details` or part of `:recipients` -- we
        ;; technically allow either right now
        (doseq [channel [{:details {:emails ["test@metabase.com"]}}
                         {:recipients [{:email "test@metabase.com"}]
                          :details    {}}]
                :let    [pulse-name   (mt/random-name)
                         request-body {:name          pulse-name
                                       :cards         [{:id                (u/the-id card)
                                                        :include_csv       false
                                                        :include_xls       false
                                                        :dashboard_card_id nil}]
                                       :channels      [(assoc channel
                                                              :enabled       true
                                                              :channel_type  "email"
                                                              :schedule_type "daily"
                                                              :schedule_hour 12
                                                              :schedule_day  nil)]
                                       :skip_if_empty false}]]
          (testing (format "\nchannel =\n%s" (u/pprint-to-str channel))
            (mt/with-fake-inbox
              (is (= "Nope!"
                     (mt/user-http-request :rasta :post 403 "pulse/test" request-body)))
              (is (not (contains? (set (keys (mt/regex-email-bodies (re-pattern pulse-name))))
                                  "test@metabase.com"))))))))))

(deftest send-test-pulse-native-query-default-parameters-test
  (testing "POST /api/pulse/test should work with a native query with default parameters"
    (mt/with-temp [:model/Card {card-id :id} {:dataset_query {:database (mt/id)
                                                              :type     :native
                                                              :native   {:query         "SELECT {{x}}"
                                                                         :template-tags {"x" {:id           "abc"
                                                                                              :name         "x"
                                                                                              :display-name "X"
                                                                                              :type         :number
                                                                                              :required     true}}}}}
                   :model/Dashboard {dashboard-id :id} {:name       "Daily Sad Toucans"
                                                        :parameters [{:name    "X"
                                                                      :slug    "x"
                                                                      :id      "__X__"
                                                                      :type    "category"
                                                                      :default 3}]}
                   :model/DashboardCard _ {:card_id            card-id
                                           :dashboard_id       dashboard-id
                                           :parameter_mappings [{:parameter_id "__X__"
                                                                 :card_id      card-id
                                                                 :target       [:variable [:template-tag "x"]]}]}]
      (mt/with-fake-inbox
        (let [channel-messages (pulse.test-util/with-captured-channel-send-messages!
                                 (is (= {:ok true}
                                        (mt/user-http-request :rasta :post 200 "pulse/test"
                                                              {:name          (mt/random-name)
                                                               :dashboard_id  dashboard-id
                                                               :cards         [{:id                card-id
                                                                                :include_csv       false
                                                                                :include_xls       false
                                                                                :dashboard_card_id nil}]
                                                               :channels      [{:enabled       true
                                                                                :channel_type  "email"
                                                                                :schedule_type "daily"
                                                                                :schedule_hour 12
                                                                                :schedule_day  nil
                                                                                :recipients    [(mt/fetch-user :rasta)]}]
                                                               :skip_if_empty false}))))]
          (is (= {:message [{"Daily Sad Toucans" true}
                            pulse.test-util/png-attachment]
                  :message-type :attachments,
                  :recipients #{"rasta@metabase.com"}
                  :subject "Daily Sad Toucans"
                  :recipient-type nil}
                 (mt/summarize-multipart-single-email (-> channel-messages :channel/email first) #"Daily Sad Toucans"))))))))

(deftest array-query-can-be-emailed-test
  (mt/test-drivers (mt/normal-drivers-with-feature :test/arrays)
    (mt/with-temp [:model/Card {card-id :id} {:dataset_query (mt/native-query {:query (tx/native-array-query driver/*driver*)})}
                   :model/Dashboard {dashboard-id :id} {:name "Venues by Category"}
                   :model/DashboardCard _ {:card_id      card-id
                                           :dashboard_id dashboard-id}]
      (mt/with-fake-inbox
        (let [channel-messages (pulse.test-util/with-captured-channel-send-messages!
                                 (is (= {:ok true}
                                        (mt/user-http-request :rasta :post 200 "pulse/test"
                                                              {:name          (mt/random-name)
                                                               :dashboard_id  dashboard-id
                                                               :cards         [{:id                card-id
                                                                                :include_csv       false
                                                                                :include_xls       false}]
                                                               :channels      [{:channel_type  "email"
                                                                                :recipients    [(mt/fetch-user :rasta)]}]}))))]
          (is (= {:message [{"Venues by Category" true}
                            pulse.test-util/png-attachment]
                  :message-type :attachments,
                  :recipients #{"rasta@metabase.com"}
                  :subject "Venues by Category"
                  :recipient-type nil}
                 (mt/summarize-multipart-single-email (-> channel-messages :channel/email first) #"Venues by Category"))))))))

(deftest ^:parallel pulse-card-query-results-test
  (testing "viz-settings saved in the DB for a Card should be loaded"
    (is (some? (get-in (#'api.pulse/pulse-card-query-results
                        {:id            1
                         :dataset_query {:database (mt/id)
                                         :type     :query
                                         :query    {:source-table (mt/id :venues)
                                                    :limit        1}}})
                       [:data :viz-settings])))))

(deftest form-input-test
  (testing "GET /api/pulse/form_input"
    (mt/with-temporary-setting-values
      [channel.settings/slack-app-token "test-token"]
      (mt/with-temp [:model/Channel _ {:type :channel/http :details {:url "https://metabasetest.com" :auth-method "none"}}]
        (is (= {:channels {:email {:allows_recipients true
                                   :configured        false
                                   :name              "Email"
                                   :recipients        ["user" "email"]
                                   :schedules         ["hourly" "daily" "weekly" "monthly"]
                                   :type              "email"}
                           :http  {:allows_recipients false
                                   :configured        true
                                   :name              "Webhook"
                                   :schedules         ["hourly" "daily" "weekly" "monthly"]
                                   :type              "http"}
                           :slack {:allows_recipients false
                                   :configured        true
                                   :fields            [{:displayName "Post to"
                                                        :name        "channel"
                                                        :options     []
                                                        :required    true
                                                        :type        "select"}]
                                   :name             "Slack"
                                   :schedules        ["hourly" "daily" "weekly" "monthly"]
                                   :type             "slack"}}}
               (mt/user-http-request :rasta :get 200 "pulse/form_input")))))))

(deftest form-input-slack-test
  (testing "GET /api/pulse/form_input"
    (testing "Check that Slack channels come back when configured"
      (mt/with-temporary-setting-values [channel.settings/slack-channels-and-usernames-last-updated
                                         (t/zoned-date-time)

                                         channel.settings/slack-app-token "test-token"

                                         channel.settings/slack-cached-channels-and-usernames
                                         {:channels [{:type "channel"
                                                      :name "foo"
                                                      :display-name "#foo"
                                                      :id "CAAS3DD9XND"}
                                                     {:type "channel"
                                                      :name "general"
                                                      :display-name "#general"
                                                      :id "C3MJRZ9EUVA"}
                                                     {:type "user"
                                                      :name "user1"
                                                      :id "U1DYU9W3WZ2"
                                                      :display-name "@user1"}]}]
        (is (= [{:name "channel", :type "select", :displayName "Post to",
                 :options ["#foo" "#general" "@user1"], :required true}]
               (-> (mt/user-http-request :rasta :get 200 "pulse/form_input")
                   (get-in [:channels :slack :fields]))))))

    (testing "When slack is not configured, `form_input` returns no channels"
      (mt/with-temporary-setting-values [slack-token nil
                                         slack-app-token nil]
        (is (empty?
             (-> (mt/user-http-request :rasta :get 200 "pulse/form_input")
                 (get-in [:channels :slack :fields])
                 (first)
                 (:options))))))))

(deftest preview-pulse-test
  (testing "GET /api/pulse/preview_card/:id"
    (mt/with-temp [:model/Collection _ {}
                   :model/Card       card {:dataset_query (mt/mbql-query checkins {:limit 5})}]
      (letfn [(preview [expected-status-code & [width]]
                (let [url (str "pulse/preview_card_png/" (u/the-id card)
                               (when width (str "?width=" width)))]
                  (client/client-full-response (mt/user->credentials :rasta)
                                               :get expected-status-code url)))]
        (testing "Should be able to preview a Pulse"
          (let [{{:strs [Content-Type]} :headers, :keys [body]} (preview 200)]
            (is (= "image/png"
                   Content-Type))
            (is (some? body))))

        (testing "Should respect the width query parameter"
          (let [width 600
                resp1 (preview 200)
                resp2 (preview 200 width)]
            (is (= "image/png" (get-in resp2 [:headers "Content-Type"])))
            (is (not= (:body resp1) (:body resp2))) ;; crude check: different width should yield different PNG bytes
            (is (some? (:body resp2)))))

        (testing "If rendering a Pulse fails (e.g. because font registration failed) the endpoint should return the error message"
          (with-redefs [style/register-fonts-if-needed! (fn []
                                                          (throw (ex-info "Can't register fonts!"
                                                                          {}
                                                                          (NullPointerException.))))]
            (let [{{:strs [Content-Type]} :headers, :keys [body]} (preview 500)]
              (is (= "application/json; charset=utf-8"
                     Content-Type))
              (is (malli= [:map
                           [:message  [:= "Can't register fonts!"]]
                           [:trace    :any]
                           [:via      :any]]
                          body)))))))))

(deftest delete-subscription-test
  (testing "DELETE /api/pulse/:id/subscription"
    (mt/with-temp [:model/Pulse        {pulse-id :id}   {:name "Lodi Dodi" :creator_id (mt/user->id :crowberto)}
                   :model/PulseChannel {channel-id :id} {:pulse_id      pulse-id
                                                         :channel_type  "email"
                                                         :schedule_type "daily"
                                                         :details       {:other  "stuff"
                                                                         :emails ["foo@bar.com"]}}]
      (testing "Should be able to delete your own subscription"
        (mt/with-temp [:model/PulseChannelRecipient _ {:pulse_channel_id channel-id :user_id (mt/user->id :rasta)}]
          (is (= nil
                 (mt/user-http-request :rasta :delete 204 (str "pulse/" pulse-id "/subscription"))))))

      (testing "Users can't delete someone else's pulse subscription"
        (mt/with-temp [:model/PulseChannelRecipient _ {:pulse_channel_id channel-id :user_id (mt/user->id :rasta)}]
          (is (= "Not found."
                 (mt/user-http-request :lucky :delete 404 (str "pulse/" pulse-id "/subscription")))))))))
