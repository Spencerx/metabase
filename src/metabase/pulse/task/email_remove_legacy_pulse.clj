(ns metabase.pulse.task.email-remove-legacy-pulse
  (:require
   [clojurewerkz.quartzite.jobs :as jobs]
   [clojurewerkz.quartzite.triggers :as triggers]
   [metabase.channel.email :as email]
   [metabase.channel.settings :as channel.settings]
   [metabase.channel.template.core :as channel.template]
   [metabase.channel.urls :as urls]
   [metabase.task.core :as task]
   [metabase.util.log :as log]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- has-legacy-pulse? []
  (pos? (t2/count :model/Pulse :dashboard_id nil :alert_condition nil :archived false)))

(def ^:private template-path "metabase/channel/email/warn_deprecate_pulse.hbs")

(defn- email-remove-legacy-pulse []
  (when (and (channel.settings/email-configured?)
             (has-legacy-pulse?))
    (log/info "Sending email to admins about removal of legacy pulses")
    (let [legacy-pulse (->> (t2/select :model/Pulse :dashboard_id nil :alert_condition nil :archived false)
                            (map #(assoc % :url (urls/legacy-pulse-url (:id %)))))]
      (doseq [admin (t2/select :model/User :is_superuser true)]
        (email/send-email-retrying!
         {:recipients   [(:email admin)]
          :message-type :html
          :subject      "[Metabase] Removal of legacy pulses in upcoming Metabase release"
          :message      (channel.template/render template-path {:userName    (:common_name admin)
                                                                :pulses      legacy-pulse
                                                                :instanceURL (urls/site-url)})})))))

(task/defjob ^{:doc "Send email to admins and warn about removal of Pulse in 49, This job will only run once."}
  EmailRemoveLegacyPulse [_ctx]
  (email-remove-legacy-pulse))

(defmethod task/init! ::SendWarnPulseRemovalEmail [_job-name]
  (let [job     (jobs/build
                 (jobs/of-type EmailRemoveLegacyPulse)
                 (jobs/with-identity (jobs/key "metabase.task.email-remove-legacy-pulse.job"))
                 (jobs/store-durably))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key "metabase.task.email-remove-legacy-pulse.trigger"))
                 (triggers/start-now))]
    (task/schedule-task! job trigger)))
