(ns metabase.driver.presto-jdbc
  "Presto JDBC driver. See https://prestodb.io/docs/current/ for complete dox."
  (:require
   [buddy.core.codecs :as codecs]
   [clojure.java.jdbc :as jdbc]
   [clojure.set :as set]
   [clojure.string :as str]
   [honey.sql :as sql]
   [honey.sql.helpers :as sql.helpers]
   [java-time.api :as t]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.execute.legacy-impl :as sql-jdbc.legacy]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-jdbc.sync.describe-database :as sql-jdbc.describe-database]
   [metabase.driver.sql.parameters.substitution :as sql.params.substitution]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.util :as sql.u]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log])
  (:import
   (com.facebook.presto.jdbc PrestoConnection)
   (com.mchange.v2.c3p0 C3P0ProxyConnection)
   (java.sql
    Connection
    PreparedStatement
    ResultSet
    ResultSetMetaData
    Types)
   (java.time
    LocalDateTime
    LocalTime
    OffsetDateTime
    OffsetTime
    ZonedDateTime)
   (java.time.format DateTimeFormatter)
   (java.time.temporal ChronoField Temporal)))

(set! *warn-on-reflection* true)

(driver/register! :presto-jdbc, :parent #{:sql-jdbc
                                          ::sql-jdbc.legacy/use-legacy-classes-for-read-and-set})

(doseq [[feature supported?] {:basic-aggregations              true
                              :binning                         true
                              :expression-aggregations         true
                              :expression-literals             true
                              :expressions                     true
                              :native-parameters               true
                              :now                             true
                              :set-timezone                    true
                              :standard-deviation-aggregations true
                              :metadata/key-constraints        false
                              :database-routing                false}]
  (defmethod driver/database-supports? [:presto-jdbc feature] [_driver _feature _db] supported?))

;;; Presto API helpers

(def ^:private ^{:arglists '([column-type])} presto-type->base-type
  "Function that returns a `base-type` for the given `presto-type` (can be a keyword or string)."
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)boolean"                    :type/Boolean]
    [#"(?i)tinyint"                    :type/Integer]
    [#"(?i)smallint"                   :type/Integer]
    [#"(?i)integer"                    :type/Integer]
    [#"(?i)bigint"                     :type/BigInteger]
    [#"(?i)real"                       :type/Float]
    [#"(?i)double"                     :type/Float]
    [#"(?i)decimal.*"                  :type/Decimal]
    [#"(?i)varchar.*"                  :type/Text]
    [#"(?i)char.*"                     :type/Text]
    [#"(?i)varbinary.*"                :type/*]
    [#"(?i)json"                       :type/Text] ; TODO - this should probably be Dictionary or something
    [#"(?i)date"                       :type/Date]
    [#"(?i)^timestamp$"                :type/DateTime]
    [#"(?i)^timestamp with time zone$" :type/DateTimeWithTZ]
    [#"(?i)^time$"                     :type/Time]
    [#"(?i)^time with time zone$"      :type/TimeWithTZ]
    #_[#"(?i)time.+"                     :type/DateTime] ; TODO - get rid of this one?
    [#"(?i)array"                      :type/Array]
    [#"(?i)map"                        :type/Dictionary]
    [#"(?i)row.*"                      :type/*] ; TODO - again, but this time we supposedly have a schema
    [#".*"                             :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :presto-jdbc [_driver database-type]
  (presto-type->base-type database-type))

(defn- date-add [unit amount expr]
  (let [amount (if (number? amount)
                 [:inline amount]
                 amount)]
    (cond-> [:date_add (h2x/literal unit) amount expr]
      (h2x/database-type expr)
      (h2x/with-database-type-info (h2x/database-type expr)))))

(defmethod sql.qp/add-interval-honeysql-form :presto-jdbc
  [_driver expr amount unit]
  (date-add unit amount expr))

(defn- describe-catalog-sql
  "The SHOW SCHEMAS statement that will list all schemas for the given `catalog`."
  {:added "0.39.0"}
  [driver catalog]
  (str "SHOW SCHEMAS FROM " (sql.u/quote-name driver :database catalog)))

(defn- describe-schema-sql
  "The SHOW TABLES statement that will list all tables for the given `catalog` and `schema`."
  {:added "0.39.0"}
  [driver catalog schema]
  (str "SHOW TABLES FROM " (sql.u/quote-name driver :schema catalog schema)))

(defn- describe-table-sql
  "The DESCRIBE statement that will list information about the given `table`, in the given `catalog` and schema`."
  {:added "0.39.0"}
  [driver catalog schema table]
  (str "DESCRIBE " (sql.u/quote-name driver :table catalog schema table)))

(def ^:private excluded-schemas
  "The set of schemas that should be excluded when querying all schemas."
  #{"information_schema"})

(defmethod driver/db-start-of-week :presto-jdbc
  [_]
  :monday)

(defmethod sql.qp/cast-temporal-string [:presto-jdbc :Coercion/ISO8601->DateTime]
  [_driver _semantic_type expr]
  (h2x/->timestamp [:replace expr "T" " "]))

(defmethod sql.qp/cast-temporal-string [:presto-jdbc :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_ _coercion-strategy expr]
  [:date_parse expr (h2x/literal "%Y%m%d%H%i%s")])

(defmethod sql.qp/cast-temporal-byte [:presto-jdbc :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/YYYYMMDDHHMMSSString->Temporal
                               [:from_utf8 expr]))

(defmethod sql.qp/cast-temporal-byte [:presto-jdbc :Coercion/ISO8601Bytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/ISO8601->DateTime
                               [:from_utf8 expr]))

(defmethod sql.qp/->honeysql [:presto-jdbc ::sql.qp/cast-to-text]
  [driver [_ expr]]
  (sql.qp/->honeysql driver [::sql.qp/cast expr "varchar"]))

(defmethod sql.qp/->honeysql [:presto-jdbc Boolean]
  [_ bool]
  [:raw (if bool "TRUE" "FALSE")])

(defmethod sql.qp/->honeysql [:presto-jdbc (Class/forName "[B")]
  [_driver bs]
  [:from_base64 (u/encode-base64-bytes bs)])

(defmethod sql.qp/->honeysql [:presto-jdbc :time]
  [_ [_ t]]
  (h2x/cast :time (u.date/format-sql (t/local-time t))))

(defmethod sql.qp/->honeysql [:presto-jdbc :regex-match-first]
  [driver [_ arg pattern]]
  [:regexp_extract (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)])

(defmethod sql.qp/->honeysql [:presto-jdbc :median]
  [driver [_ arg]]
  [:approx_percentile (sql.qp/->honeysql driver arg) 0.5])

(defmethod sql.qp/->honeysql [:presto-jdbc :percentile]
  [driver [_ arg p]]
  [:approx_percentile (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver p)])

;;; Presto mod is a function like mod(x, y) rather than an operator like x mod y
(defn- format-mod
  [_fn [x y]]
  (let [[x-sql & x-args] (sql/format-expr x {:nested true})
        [y-sql & y-args] (sql/format-expr y {:nested true})]
    (into [(format "mod(%s, %s)" x-sql y-sql)]
          cat
          [x-args
           y-args])))

(sql/register-fn! ::mod #'format-mod)

(def ^:dynamic ^:private *inline-param-style*
  "How we should include inline params when compiling SQL. `:friendly` (the default) or `:paranoid`. `:friendly` makes a
  best-effort attempt to escape strings and generate SQL that is nice to look at, but should not be considered safe
  against all SQL injection -- use this for 'convert to SQL' functionality. `:paranoid` hex-encodes strings so SQL
  injection is impossible; this isn't nice to look at, so use this for actually running a query."
  :friendly)

(defmethod sql.qp/inline-value [:presto-jdbc String]
  [_ ^String s]
  (case *inline-param-style*
    :friendly (str \' (sql.u/escape-sql s :ansi) \')
    :paranoid (format "from_utf8(from_hex('%s'))" (codecs/bytes->hex (.getBytes s "UTF-8")))))

;; See https://prestodb.io/docs/current/functions/datetime.html

(defmethod sql.qp/inline-value [:presto-jdbc OffsetTime]
  [_driver t]
  (format "time '%s %s'" (t/local-time t) (t/zone-offset t)))

(defmethod sql.qp/inline-value [:presto-jdbc OffsetDateTime]
  [_driver t]
  (format "timestamp '%s %s %s'" (t/local-date t) (t/local-time t) (t/zone-offset t)))

(defmethod sql.qp/inline-value [:presto-jdbc ZonedDateTime]
  [_driver t]
  (format "timestamp '%s %s %s'" (t/local-date t) (t/local-time t) (t/zone-id t)))

;;; `:sql-driver` methods

(defn- format-row-number-over
  [_tag [subquery]]
  (let [[subquery-sql & subquery-args] (sql/format-expr subquery)]
    (into [(format "row_number() OVER %s" subquery-sql)]
          subquery-args)))

(sql/register-fn! ::row-number-over #'format-row-number-over)

(defmethod sql.qp/apply-top-level-clause [:presto-jdbc :page]
  [_driver _top-level-clause honeysql-query {{:keys [items page]} :page}]
  {:pre [(pos-int? items) (pos-int? page)]}
  (let [offset (* (dec page) items)]
    (if (zero? offset)
      ;; if there's no offset we can simply use limit
      (sql.helpers/limit honeysql-query [:inline items])
      ;; if we need to do an offset we have to do nesting to generate a row number and where on that
      (let [over-clause [::row-number-over (select-keys honeysql-query [:order-by])]]
        (-> (apply sql.helpers/select (map last (:select honeysql-query)))
            (sql.helpers/from [(sql.helpers/select honeysql-query [over-clause :__rownum__])])
            (sql.helpers/where [:> :__rownum__ [:inline offset]])
            (sql.helpers/limit [:inline items]))))))

(defmethod sql.qp/current-datetime-honeysql-form :presto-jdbc
  [_driver]
  (h2x/with-database-type-info :%now "timestamp with time zone"))

(defn- date-diff [unit a b] [:date_diff (h2x/literal unit) a b])
(defn- date-trunc [unit x] [:date_trunc (h2x/literal unit) x])

(defmethod sql.qp/date [:presto-jdbc :default]         [_ _ expr] expr)
(defmethod sql.qp/date [:presto-jdbc :minute]          [_ _ expr] (date-trunc :minute expr))
(defmethod sql.qp/date [:presto-jdbc :minute-of-hour]  [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:presto-jdbc :hour]            [_ _ expr] (date-trunc :hour expr))
(defmethod sql.qp/date [:presto-jdbc :hour-of-day]     [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:presto-jdbc :day]             [_ _ expr] (date-trunc :day expr))
(defmethod sql.qp/date [:presto-jdbc :day-of-month]    [_ _ expr] [:day expr])
(defmethod sql.qp/date [:presto-jdbc :day-of-year]     [_ _ expr] [:day_of_year expr])

(defmethod sql.qp/date [:presto-jdbc :day-of-week]
  [driver _ expr]
  (sql.qp/adjust-day-of-week driver [:day_of_week expr]))

(defmethod sql.qp/date [:presto-jdbc :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver (partial date-trunc :week) expr))

(defmethod sql.qp/date [:presto-jdbc :month]           [_ _ expr] (date-trunc :month expr))
(defmethod sql.qp/date [:presto-jdbc :month-of-year]   [_ _ expr] [:month expr])
(defmethod sql.qp/date [:presto-jdbc :quarter]         [_ _ expr] (date-trunc :quarter expr))
(defmethod sql.qp/date [:presto-jdbc :quarter-of-year] [_ _ expr] [:quarter expr])
(defmethod sql.qp/date [:presto-jdbc :year]            [_ _ expr] (date-trunc :year expr))

(defmethod sql.qp/unix-timestamp->honeysql [:presto-jdbc :seconds]
  [_driver _seconds-or-milliseconds expr]
  [:from_unixtime expr])

(defn ->date
  "Same as [[h2x/->date]], but truncates `x` to the date in the results time zone."
  [x]
  (h2x/->date (h2x/at-time-zone x (driver-api/results-timezone-id))))

(defmethod sql.qp/datetime-diff [:presto-jdbc :year]    [_driver _unit x y] (date-diff :year (->date x) (->date y)))
(defmethod sql.qp/datetime-diff [:presto-jdbc :quarter] [_driver _unit x y] (date-diff :quarter (->date x) (->date y)))
(defmethod sql.qp/datetime-diff [:presto-jdbc :month]   [_driver _unit x y] (date-diff :month (->date x) (->date y)))
(defmethod sql.qp/datetime-diff [:presto-jdbc :week]    [_driver _unit x y] (date-diff :week (->date x) (->date y)))
(defmethod sql.qp/datetime-diff [:presto-jdbc :day]     [_driver _unit x y] (date-diff :day (->date x) (->date y)))
(defmethod sql.qp/datetime-diff [:presto-jdbc :hour]    [_driver _unit x y] (date-diff :hour x y))
(defmethod sql.qp/datetime-diff [:presto-jdbc :minute]  [_driver _unit x y] (date-diff :minute x y))
(defmethod sql.qp/datetime-diff [:presto-jdbc :second]  [_driver _unit x y] (date-diff :second x y))

(defmethod driver/db-default-timezone :presto-jdbc
  [driver database]
  (sql-jdbc.execute/do-with-connection-with-options
   driver database nil
   (fn [^java.sql.Connection conn]
     ;; TODO -- this is the session timezone, right? As opposed to the default timezone? Ick. Not sure how to get the
     ;; default timezone if session timezone is unspecified.
     (with-open [stmt (.prepareStatement conn "SELECT current_timezone()")
                 rset (.executeQuery stmt)]
       (when (.next rset)
         (.getString rset 1))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Custom HoneySQL Clause Impls                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ^:const timestamp-with-time-zone-db-type "timestamp with time zone")

(defmethod sql.qp/->honeysql [:presto-jdbc :log]
  [driver [_ field]]
  ;; recent Presto versions have a `log10` function (not `log`)
  [:log10 (sql.qp/->honeysql driver field)])

(defmethod sql.qp/->honeysql [:presto-jdbc :count-where]
  [driver [_ pred]]
  ;; Presto will use the precision given here in the final expression, which chops off digits
  ;; need to explicitly provide two digits after the decimal
  (sql.qp/->honeysql driver [:sum-where 1.00M pred]))

(defmethod sql.qp/->honeysql [:presto-jdbc :time]
  [_driver [_ t]]
  ;; make time in UTC to avoid any interpretation by Presto in the connection (i.e. report) time zone
  [:inline (t/offset-time (t/local-time t) 0)])

(defmethod sql.qp/->honeysql [:presto-jdbc ZonedDateTime]
  [_driver ^ZonedDateTime t]
  [:inline t])

(defmethod sql.qp/->honeysql [:presto-jdbc OffsetDateTime]
  [_driver ^OffsetDateTime t]
  [:inline t])

(defn- in-report-zone
  "Returns a HoneySQL form to interpret the `expr` (a temporal value) in the current report time zone, via Presto's
  `AT TIME ZONE` operator. See https://prestodb.io/docs/current/functions/datetime.html"
  [expr]
  (let [report-zone (driver-api/report-timezone-id-if-supported :presto-jdbc (driver-api/database (driver-api/metadata-provider)))
        ;; if the expression itself has type info, use that, or else use a parent expression's type info if defined
        type-info   (h2x/type-info expr)
        db-type     (h2x/type-info->db-type type-info)]
    (if (and ;; AT TIME ZONE is only valid on these Presto types; if applied to something else (ex: `date`), then
         ;; an error will be thrown by the query analyzer
         (contains? #{"timestamp" "timestamp with time zone" "time" "time with time zone"} db-type)
         ;; if one has already been set, don't do so again
         (not (::in-report-zone? (meta expr)))
         report-zone)
      (-> (h2x/with-database-type-info (h2x/at-time-zone expr report-zone) timestamp-with-time-zone-db-type)
          (vary-meta assoc ::in-report-zone? true))
      expr)))

;; most date extraction and bucketing functions need to account for report timezone

(defmethod sql.qp/date [:presto-jdbc :default]
  [_driver _unit expr]
  expr)

(defmethod sql.qp/date [:presto-jdbc :minute]
  [_driver _unit expr]
  [:date_trunc (h2x/literal :minute) (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :minute-of-hour]
  [_driver _unit expr]
  [:minute (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :hour]
  [_driver _unit expr]
  [:date_trunc (h2x/literal :hour) (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :hour-of-day]
  [_driver _unit expr]
  [:hour (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :day]
  [_driver _unit expr]
  [:date (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :day-of-week]
  [_driver _unit expr]
  (sql.qp/adjust-day-of-week :presto-jdbc [:day_of_week (in-report-zone expr)]))

(defmethod sql.qp/date [:presto-jdbc :day-of-month]
  [_driver _unit expr]
  [:day (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :day-of-year]
  [_driver _unit expr]
  [:day_of_year (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :week]
  [_driver _unit expr]
  (letfn [(truncate [x]
            [:date_trunc (h2x/literal :week) x])]
    (sql.qp/adjust-start-of-week :presto-jdbc truncate (in-report-zone expr))))

(defmethod sql.qp/date [:presto-jdbc :month]
  [_driver _unit expr]
  [:date_trunc (h2x/literal :month) (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :month-of-year]
  [_driver _unit expr]
  [:month (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :quarter]
  [_driver _unit expr]
  [:date_trunc (h2x/literal :quarter) (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :quarter-of-year]
  [_driver _unit expr]
  [:quarter (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :year]
  [_driver _unit expr]
  [:date_trunc (h2x/literal :year) (in-report-zone expr)])

(defmethod sql.qp/date [:presto-jdbc :year-of-era]
  [_driver _unit expr]
  [:year (in-report-zone expr)])

(defmethod sql.qp/unix-timestamp->honeysql [:presto-jdbc :seconds]
  [_driver _unit expr]
  (let [report-zone (driver-api/report-timezone-id-if-supported :presto-jdbc (driver-api/database (driver-api/metadata-provider)))]
    [:from_unixtime expr (h2x/literal (or report-zone "UTC"))]))

(defmethod sql.qp/unix-timestamp->honeysql [:presto-jdbc :milliseconds]
  [_driver _unit expr]
  ;; from_unixtime doesn't support milliseconds directly, but we can add them back in
  (let [report-zone (driver-api/report-timezone-id-if-supported :presto-jdbc (driver-api/database (driver-api/metadata-provider)))
        millis      [::mod expr [:inline 1000]]
        expr        [:from_unixtime [:/ expr [:inline 1000]] (h2x/literal (or report-zone "UTC"))]]
    (date-add :millisecond millis expr)))

(defmethod sql.qp/unix-timestamp->honeysql [:presto-jdbc :microseconds]
  [driver _seconds-or-milliseconds expr]
  ;; Presto can't even represent microseconds, so convert to millis and call that version
  (sql.qp/unix-timestamp->honeysql driver :milliseconds [:/ expr [:inline 1000]]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Connectivity                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; Kerberos related definitions
(def ^:private kerb-props->url-param-names
  {:kerberos-principal "KerberosPrincipal"
   :kerberos-remote-service-name "KerberosRemoteServiceName"
   :kerberos-use-canonical-hostname "KerberosUseCanonicalHostname"
   :kerberos-credential-cache-path "KerberosCredentialCachePath"
   :kerberos-keytab-path "KerberosKeytabPath"
   :kerberos-service-principal-pattern "KerberosServicePrincipalPattern"
   :kerberos-config-path "KerberosConfigPath"})

(defn- details->kerberos-url-params [details]
  (let [remove-blank-vals (fn [m] (into {} (remove (comp str/blank? val) m)))
        ks                (keys kerb-props->url-param-names)]
    (-> (select-keys details ks)
        remove-blank-vals
        (set/rename-keys kerb-props->url-param-names))))

(defn- append-additional-options [additional-options props]
  (let [opts-str (sql-jdbc.common/additional-opts->string :url props)]
    (if (str/blank? additional-options)
      opts-str
      (str additional-options "&" opts-str))))

(defn- prepare-addl-opts [{:keys [SSL kerberos] :as details}]
  (let [det (if kerberos
              (if-not SSL
                (throw (ex-info (trs "SSL must be enabled to use Kerberos authentication")
                                {:db-details details}))
                ;; convert Kerberos options map to URL string
                (update details
                        :additional-options
                        append-additional-options
                        (details->kerberos-url-params details)))
              details)]
    ;; in any case, remove the standalone Kerberos properties from details map
    (apply dissoc (cons det (keys kerb-props->url-param-names)))))

(defn- db-name
  "Creates a \"DB name\" for the given catalog `c` and (optional) schema `s`.  If both are specified, a slash is
  used to separate them.  See examples at:
  https://prestodb.io/docs/current/installation/jdbc.html#connecting"
  [c s]
  (cond
    (str/blank? c)
    ""

    (str/blank? s)
    c

    :else
    (str c "/" s)))

(defn- jdbc-spec
  "Creates a spec for `clojure.java.jdbc` to use for connecting to Presto via JDBC, from the given `opts`."
  [{:keys [host port catalog schema]
    :or   {host "localhost", port 5432, catalog ""}
    :as   details}]
  (-> details
      (merge {:classname   "com.facebook.presto.jdbc.PrestoDriver"
              :subprotocol "presto"
              :subname     (driver-api/make-subname host port (db-name catalog schema))})
      prepare-addl-opts
      (dissoc :host :port :db :catalog :schema :tunnel-enabled :engine :kerberos)
      sql-jdbc.common/handle-additional-options))

(defn- str->bool [v]
  (if (string? v)
    (Boolean/parseBoolean v)
    v))

(defn- get-valid-secret-file [details-map property-name]
  (let [file (driver-api/secret-value-as-file! :presto-jdbc details-map property-name)]
    (when-not file
      (throw (ex-info (format "Property %s should be defined" property-name)
                      {:connection-details details-map
                       :property-name property-name})))
    (.getCanonicalPath file)))

(defn- maybe-add-ssl-stores [details-map]
  (let [props
        (cond-> {}
          (str->bool (:ssl-use-keystore details-map))
          (assoc :SSLKeyStorePath (get-valid-secret-file details-map "ssl-keystore")
                 :SSLKeyStorePassword (driver-api/secret-value-as-string :presto-jdbc details-map "ssl-keystore-password"))
          (str->bool (:ssl-use-truststore details-map))
          (assoc :SSLTrustStorePath (get-valid-secret-file details-map "ssl-truststore")
                 :SSLTrustStorePassword (driver-api/secret-value-as-string :presto-jdbc details-map "ssl-truststore-password")))]
    (cond-> details-map
      (seq props)
      (update :additional-options append-additional-options props))))

(defmethod sql-jdbc.conn/connection-details->spec :presto-jdbc
  [_ details-map]
  (let [props (-> details-map
                  (update :port (fn [port]
                                  (if (string? port)
                                    (Integer/parseInt port)
                                    port)))
                  (update :ssl str->bool)
                  (update :kerberos str->bool)
                  (assoc :SSL (:ssl details-map))
                  maybe-add-ssl-stores
                  ;; remove any Metabase specific properties that are not recognized by the PrestoDB JDBC driver, which is
                  ;; very picky about properties (throwing an error if any are unrecognized)
                  ;; all valid properties can be found in the JDBC Driver source here:
                  ;; https://github.com/prestodb/presto/blob/master/presto-jdbc/src/main/java/com/facebook/presto/jdbc/ConnectionProperties.java
                  (select-keys (concat
                                [:host :port :catalog :schema :additional-options ; needed for `jdbc-spec`
                                 ;; JDBC driver specific properties
                                 :kerberos ; we need our boolean property indicating if Kerberos is enabled
                                           ; but the rest of them come from `kerb-props->url-param-names` (below)
                                 :user :password :socksProxy :httpProxy :applicationNamePrefix :disableCompression :SSL
                                 ;; Passing :SSLKeyStorePath :SSLKeyStorePassword :SSLTrustStorePath :SSLTrustStorePassword
                                 ;; in the properties map doesn't seem to work, they are included as additional options.
                                 :accessToken :extraCredentials :sessionProperties :protocols :queryInterceptors]
                                (keys kerb-props->url-param-names))))]
    (jdbc-spec props)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      Sync                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- have-select-privilege?
  "Checks whether the connected user has permission to select from the given `table-name`, in the given `schema`.
  Adapted from the legacy Presto driver implementation."
  [driver conn schema table-name]
  (try
    (let [sql (sql-jdbc.describe-database/simple-select-probe-query driver schema table-name)]
        ;; if the query completes without throwing an Exception, we can SELECT from this table
      (jdbc/reducible-query {:connection conn} sql)
      true)
    (catch Throwable _
      false)))

(defn- describe-schema
  "Gets a set of maps for all tables in the given `catalog` and `schema`. Adapted from the legacy Presto driver
  implementation."
  [driver conn catalog schema]
  (let [sql (describe-schema-sql driver catalog schema)]
    (log/tracef "Running statement in describe-schema: %s" sql)
    (into #{} (comp (filter (fn [{table-name :table}]
                              (have-select-privilege? driver conn schema table-name)))
                    (map (fn [{table-name :table}]
                           {:name        table-name
                            :schema      schema})))
          (jdbc/reducible-query {:connection conn} sql))))

(defn- all-schemas
  "Gets a set of maps for all tables in all schemas in the given `catalog`. Adapted from the legacy Presto driver
  implementation."
  [driver conn catalog]
  (let [sql (describe-catalog-sql driver catalog)]
    (log/tracef "Running statement in all-schemas: %s" sql)
    (into []
          (map (fn [{:keys [schema]}]
                 (when-not (contains? excluded-schemas schema)
                   (describe-schema driver conn catalog schema))))
          (jdbc/reducible-query {:connection conn} sql))))

(defmethod driver/describe-database :presto-jdbc
  [driver {{:keys [catalog schema] :as _details} :details :as database}]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (let [schemas (if schema #{(describe-schema driver conn catalog schema)}
                       (all-schemas driver conn catalog))]
       {:tables (reduce set/union #{} schemas)}))))

(defmethod driver/describe-table :presto-jdbc
  [driver {{:keys [catalog] :as _details} :details :as database} {schema :schema, table-name :name}]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   database
   nil
   (fn [^Connection conn]
     (let [sql (describe-table-sql driver catalog schema table-name)]
       (log/tracef "Running statement in describe-table: %s" sql)
       {:schema schema
        :name   table-name
        :fields (into
                 #{}
                 (map-indexed (fn [idx {:keys [column type] :as _col}]
                                {:name              column
                                 :database-type     type
                                 :base-type         (presto-type->base-type type)
                                 :database-position idx}))
                 (jdbc/reducible-query {:connection conn} sql))}))))

;;; The Presto JDBC driver DOES NOT support the `.getImportedKeys` method so just return `nil` here so the `:sql-jdbc`
;;; implementation doesn't try to use it.
#_{:clj-kondo/ignore [:deprecated-var]}
(defmethod driver/describe-table-fks :presto-jdbc
  [_driver _database _table]
  nil)

(defmethod driver/can-connect? :presto-jdbc
  [driver {:keys [catalog], :as details}]
  (and ((get-method driver/can-connect? :sql-jdbc) driver details)
       (sql-jdbc.conn/with-connection-spec-for-testing-connection [spec [driver details]]
         ;; jdbc/query is used to see if we throw, we want to ignore the results
         (jdbc/query spec (format "SHOW SCHEMAS FROM %s" catalog))
         true)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            sql-jdbc implementations                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.execute/prepared-statement :presto-jdbc
  [driver ^Connection conn ^String sql params]
  ;; with Presto JDBC driver, result set holdability must be HOLD_CURSORS_OVER_COMMIT
  ;; defining this method simply to omit setting the holdability
  (let [stmt (.prepareStatement conn
                                sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY)]
    (try
      (try
        (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
        (catch Throwable e
          (log/debug e "Error setting prepared statement fetch direction to FETCH_FORWARD")))
      (sql-jdbc.execute/set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

(defmethod sql-jdbc.execute/statement :presto-jdbc
  [_ ^Connection conn]
  ;; and similarly for statement (do not set holdability)
  (let [stmt (.createStatement conn
                               ResultSet/TYPE_FORWARD_ONLY
                               ResultSet/CONCUR_READ_ONLY)]
    (try
      (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
      (catch Throwable e
        (log/debug e "Error setting statement fetch direction to FETCH_FORWARD")))
    stmt))

(defn- pooled-conn->presto-conn
  "Unwraps the C3P0 `pooled-conn` and returns the underlying `PrestoConnection` it holds."
  ^PrestoConnection [^C3P0ProxyConnection pooled-conn]
  (.unwrap pooled-conn PrestoConnection))

;;; for some insane reason Presto's JDBC driver does not seem to work properly when reusing a Connection to execute
;;; multiple PreparedStatements... this breaks the test-data loading code. To work around that, we'll track the original
;;; Connection spec from the top-level call, and if the `:presto-jdbc/force-fresh?` is passed in to recursive calls
;;; we'll create a NEW connection using the original spec every time. See for example the code
;;; in [[metabase.test.data.presto-jdbc]]
(def ^:dynamic ^:private *original-connection-spec* nil)

(defn- set-connection-options! [driver ^java.sql.Connection conn {:keys [^String session-timezone write?], :as _options}]
  (let [underlying-conn (pooled-conn->presto-conn conn)]
    (sql-jdbc.execute/set-best-transaction-level! driver conn)
    (when-not (str/blank? session-timezone)
      ;; set session time zone if defined
      (.setTimeZoneId underlying-conn session-timezone))
    ;; as with statement and prepared-statement, cannot set holdability on the connection level
    (let [read-only? (not write?)]
      (try
        (.setReadOnly conn read-only?)
        (catch Throwable e
          (log/debugf e "Error setting connection read-only to %s" (pr-str read-only?)))))))

(defmethod sql-jdbc.execute/do-with-connection-with-options :presto-jdbc
  [driver db-or-id-or-spec options f]
  ;; Presto supports setting the session timezone via a `PrestoConnection` instance method. Under the covers,
  ;; this is equivalent to the `X-Presto-Time-Zone` header in the HTTP request.
  (cond
    (nil? *original-connection-spec*)
    (binding [*original-connection-spec* db-or-id-or-spec]
      (sql-jdbc.execute/do-with-connection-with-options driver db-or-id-or-spec options f))

    (:presto-jdbc/force-fresh? options)
    (sql-jdbc.execute/do-with-connection-with-options driver *original-connection-spec* (dissoc options :presto-jdbc/force-fresh?) f)

    :else
    (sql-jdbc.execute/do-with-resolved-connection
     driver
     db-or-id-or-spec
     (dissoc options :session-timezone)
     (fn [^java.sql.Connection conn]
       (when-not (sql-jdbc.execute/recursive-connection?)
         (set-connection-options! driver conn options))
       (f conn)))))

(defn- date-time->substitution [ts-str]
  (sql.params.substitution/make-stmt-subs "from_iso8601_timestamp(?)" [ts-str]))

(defmethod sql.params.substitution/->prepared-substitution [:presto-jdbc ZonedDateTime]
  [_ ^ZonedDateTime t]
  ;; for native query parameter substitution, in order to not conflict with the `PrestoConnection` session time zone
  ;; (which was set via report time zone), it is necessary to use the `from_iso8601_timestamp` function on the string
  ;; representation of the `ZonedDateTime` instance, but converted to the report time zone
  #_(date-time->substitution (.format (t/offset-date-time (t/local-date-time t) (t/zone-offset 0)) DateTimeFormatter/ISO_OFFSET_DATE_TIME))
  (let [report-zone       (driver-api/report-timezone-id-if-supported :presto-jdbc (driver-api/database (driver-api/metadata-provider)))
        ^ZonedDateTime ts (if (str/blank? report-zone) t (t/with-zone-same-instant t (t/zone-id report-zone)))]
    ;; the `from_iso8601_timestamp` only accepts timestamps with an offset (not a zone ID), so only format with offset
    (date-time->substitution (.format ts DateTimeFormatter/ISO_OFFSET_DATE_TIME))))

(defmethod sql.params.substitution/->prepared-substitution [:presto-jdbc LocalDateTime]
  [_ ^LocalDateTime t]
  ;; similar to above implementation, but for `LocalDateTime`
  ;; when Presto parses this, it will account for session (report) time zone
  (date-time->substitution (.format t DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

(defmethod sql.params.substitution/->prepared-substitution [:presto-jdbc OffsetDateTime]
  [_ ^OffsetDateTime t]
  ;; similar to above implementation, but for `ZonedDateTime`
  ;; when Presto parses this, it will account for session (report) time zone
  (date-time->substitution (.format t DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn- set-time-param
  "Converts the given instance of `java.time.temporal`, assumed to be a time (either `LocalTime` or `OffsetTime`)
  into a `java.sql.Time`, including milliseconds, and sets the result as a parameter of the `PreparedStatement` `ps`
  at index `i`."
  [^PreparedStatement ps ^Integer i ^Temporal t]
  ;; for some reason, `java-time` can't handle passing millis to java.sql.Time, so this is the most straightforward way
  ;; I could find to do it
  ;; reported as https://github.com/dm3/clojure.java-time/issues/74
  (let [millis-of-day (.get t ChronoField/MILLI_OF_DAY)]
    ;; TODO -- why the HECK are we using `java.sql.Time` here!!!!!
    (.setTime ps i (java.sql.Time. millis-of-day))))

(defmethod sql-jdbc.execute/set-parameter [:presto-jdbc OffsetTime]
  [_ ^PreparedStatement ps ^Integer i t]
  ;; necessary because `PrestoPreparedStatement` does not implement the `setTime` overload having the final `Calendar`
  ;; param
  (let [adjusted-tz (t/with-offset-same-instant t (t/zone-offset 0))]
    (set-time-param ps i adjusted-tz)))

(defmethod sql-jdbc.execute/set-parameter [:presto-jdbc LocalTime]
  [_ ^PreparedStatement ps ^Integer i t]
  ;; same rationale as above
  (set-time-param ps i t))

(defn- sql-time->local-time
  "Converts the given instance of `java.sql.Time` into a `java.time.LocalTime`, including milliseconds. Needed for
  similar reasons as `set-time-param` above."
  ^LocalTime [^java.sql.Time sql-time]
  ;; Java 11 adds a simpler `ofInstant` method, but since we need to run on JDK 8, we can't use it
  ;; https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/time/LocalTime.html#ofInstant(java.time.Instant,java.time.ZoneId)
  ;;
  ;; TODO -- we run on Java 21+ now!!! FIXME !!!!!
  (let [^LocalTime lt (t/local-time sql-time)
        ^Long millis  (mod (.getTime sql-time) 1000)]
    (.with lt ChronoField/MILLI_OF_SECOND millis)))

(defmethod sql-jdbc.execute/read-column-thunk [:presto-jdbc Types/TIME]
  [_ ^ResultSet rs ^ResultSetMetaData rs-meta ^Integer i]
  (let [type-name  (.getColumnTypeName rs-meta i)
        base-type  (presto-type->base-type type-name)
        with-tz?   (isa? base-type :type/TimeWithTZ)]
    (fn []
      (let [local-time (-> (.getTime rs i)
                           sql-time->local-time)]
        ;; for both `time` and `time with time zone`, the JDBC type reported by the driver is `Types/TIME`, hence
        ;; we also need to check the column type name to differentiate between them here
        (if with-tz?
          ;; even though this value is a `LocalTime`, the base-type is time with time zone, so we need to shift it back
          ;; to the UTC (0) offset
          (t/offset-time
           local-time
           (t/zone-offset 0))
          ;; else the base-type is time without time zone, so just return the local-time value
          local-time)))))

(defn- rs->presto-conn
  "Returns the `PrestoConnection` associated with the given `ResultSet` `rs`."
  ^PrestoConnection [^ResultSet rs]
  (-> (.. rs getStatement getConnection)
      pooled-conn->presto-conn))

(defmethod sql-jdbc.execute/read-column-thunk [:presto-jdbc Types/TIMESTAMP]
  [_driver ^ResultSet rset _rsmeta ^Integer i]
  (let [zone     (.getTimeZoneId (rs->presto-conn rset))]
    (fn []
      (when-let [s (.getString rset i)]
        (when-let [t (u.date/parse s)]
          (cond
            (or (instance? OffsetDateTime t)
                (instance? ZonedDateTime t))
            (-> (t/offset-date-time t)
              ;; tests are expecting this to be in the UTC offset, so convert to that
                (t/with-offset-same-instant (t/zone-offset 0)))

            ;; presto "helpfully" returns local results already adjusted to session time zone offset for us, e.g.
            ;; '2021-06-15T00:00:00' becomes '2021-06-15T07:00:00' if the session timezone is US/Pacific. Undo the
            ;; madness and convert back to UTC
            zone
            (-> (t/zoned-date-time t zone)
                (u.date/with-time-zone-same-instant "UTC")
                t/local-date-time)
            :else
            t))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Other Driver Method Impls                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(prefer-method driver/database-supports? [:presto-jdbc :set-timezone] [:sql-jdbc :set-timezone])

(defmethod driver/escape-alias :presto-jdbc
  [_driver s]
  ((get-method driver/escape-alias :sql-jdbc)
   :presto-jdbc
   ;; Source of the pattern:
   ;; https://github.com/prestodb/presto/blob/b73ab7df31e4d969c44fd953e5cb8e36a18eb55b/presto-parser/src/main/java/com/facebook/presto/sql/tree/Identifier.java#L26
   (str/replace s #"(^[^a-zA-Z_])|([^a-zA-Z0-9_:@])" "_")))
