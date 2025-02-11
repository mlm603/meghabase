(ns metabase.query-analysis
  "This module handles the analysis of queries, which determines their data dependencies.
  It also is used to audit these dependencies for issues - for example, making use of column that no longer exists.
  Analysis is typically performed on a background worker thread, and the [[analyze-async!]] method is used to add cards
  to the corresponding queue."
  (:require
   [clojure.set :as set]
   [medley.core :as m]
   [metabase.config :as config]
   [metabase.legacy-mbql.util :as mbql.u]
   [metabase.lib.core :as lib]
   [metabase.lib.util :as lib.util]
   [metabase.models.query-field :as query-field]
   [metabase.public-settings :as public-settings]
   [metabase.query-analysis.native-query-analyzer :as nqa]
   [metabase.query-analysis.native-query-analyzer.replacement :as nqa.replacement]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.queue :as queue]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private realtime-queue-capacity
  "The maximum number of cards which can be queued for async analysis. When exceeded, additional cards will be dropped."
  1000)

(def ^:private worker-queue
  "The in-memory queue used to throttle analysis and reduce the chance of race conditions."
  (queue/bounded-transfer-queue realtime-queue-capacity {:dedupe? false}))

(def ^:dynamic *analyze-execution-in-dev?*
  "Managing a background thread in the REPL is likely to confuse and infuriate, especially when running tests.
  For this reason, we run analysis on the main thread by default."
  ::immediate)

(def ^:dynamic *analyze-execution-in-test?*
  "A card's query is normally analyzed on every create/update.
  For most tests, this is an unnecessary expense; hence we disable analysis by default."
  ::disabled)

(defmacro with-execution*
  "Override the default execution mode, except in prod."
  [execution & body]
  (assert (not config/is-prod?))
  `(binding [*analyze-execution-in-dev?*  ~execution
             *analyze-execution-in-test?* ~execution]
     ~@body))

(defmacro with-queued-analysis
  "Override the default execution mode to always use the queue. Does nothing in prod - only use this in tests."
  [& body]
  `(with-execution* ::queued ~@body))

(defmacro with-immediate-analysis
  "Override the default execution mode to always use the current thread. Does nothing in prod - only use this in tests."
  [& body]
  `(with-execution* ::immediate ~@body))

(defmacro without-analysis
  "Override the default execution mode to always use the current thread. Does nothing in prod - only use this in tests."
  [& body]
  `(with-execution* ::disabled ~@body))

(defn- execution
  "The execution strategy for analysis, which can be overridden in dev and tests. In production, it is always async."
  []
  (case config/run-mode
    :prod ::queued
    :dev  *analyze-execution-in-dev?*
    :test *analyze-execution-in-test?*))

(defn enabled-type?
  "Is analysis of the given query type enabled?"
  [query-type]
  (and (public-settings/query-analysis-enabled)
       (case query-type
         :native (public-settings/sql-parsing-enabled)
         :query true
         :mbql/query true
         false)))

(defn- query-field-ids
  "Find out ids of all fields used in a query. Conforms to the same protocol as [[query-analyzer/field-ids-for-sql]],
  so returns `{:explicit #{...int ids}}` map.

  Does not track wildcards for queries rendered as tables afterward."
  ([query]
   (query-field-ids query (lib/normalized-query-type query)))
  ([query query-type]
   (case query-type
     :native (try
               (nqa/field-ids-for-native query)
               (catch Exception e
                 (log/error e "Error parsing SQL" query)))
     :query {:explicit (mbql.u/referenced-field-ids query)}
     :mbql/query {:explicit (lib.util/referenced-field-ids query)})))

(defn update-query-analysis-for-card!
  "Clears QueryFields associated with this card and creates fresh, up-to-date-ones.

  Returns `nil` (and logs the error) if there was a parse error.
  Returns `nil` and leaves the database records as-is if analysis is disabled for the given query type."
  [{card-id :id, query :dataset_query}]
  (let [query-type (lib/normalized-query-type query)]
    (when (enabled-type? query-type)
      (let [{:keys [explicit implicit]} (query-field-ids query)
            id->row          (fn [explicit? field-id]
                               {:card_id            card-id
                                :field_id           field-id
                                :explicit_reference explicit?})
            query-field-rows (concat
                              (map (partial id->row true) explicit)
                              (map (partial id->row false) implicit))]
        (query-field/update-query-fields-for-card! card-id query-field-rows)))))

(defn- replaced-inner-query-for-native-card
  "Substitute new references for certain fields and tables, based upon the given mappings."
  [query {:keys [fields tables] :as _replacement-ids}]
  (let [keyvals-set         #(set/union (set (keys %))
                                        (set (vals %)))
        id->field           (if (empty? fields)
                              {}
                              (m/index-by :id
                                          (t2/query {:select [[:f.id :id]
                                                              [:f.name :column]
                                                              [:t.name :table]
                                                              [:t.schema :schema]]
                                                     :from   [[:metabase_field :f]]
                                                     :join   [[:metabase_table :t] [:= :f.table_id :t.id]]
                                                     :where  [:in :f.id (keyvals-set fields)]})))
        id->table           (if (empty? tables)
                              {}
                              (m/index-by :id
                                          (t2/query {:select [[:t.id :id]
                                                              [:t.name :table]
                                                              [:t.schema :schema]]
                                                     :from   [[:metabase_table :t]]
                                                     :where  [:in :t.id (keyvals-set tables)]})))
        remove-id           #(select-keys % [:column :table :schema])
        get-or-throw-from   (fn [m] (fn [k] (if (contains? m k)
                                              (remove-id (get m k))
                                              (throw (ex-info "ID not found" {:id k :available m})))))
        ids->replacements   (fn [id->replacement-id id->row row->identifier]
                              (-> id->replacement-id
                                  (u/update-keys-vals (get-or-throw-from id->row))
                                  (update-vals row->identifier)))
        ;; Note: we are naively providing unqualified new identifier names as the replacements.
        ;; this will break if previously unambiguous identifiers become ambiguous due to the replacements
        column-replacements (ids->replacements fields id->field :column)
        table-replacements  (ids->replacements tables id->table :table)]
    (nqa.replacement/replace-names query {:columns column-replacements
                                          :tables  table-replacements})))

(defn replace-fields-and-tables
  "Given a card and a map of the form

  {:fields {1 2, 3 4}
   :tables {100 101}}

  Update the card so that its references to the Field with ID 1 are replaced by Field 2, etc."
  [{card-type :query_type, q :dataset_query :as card} replacements]
  (case card-type
    :native (replaced-inner-query-for-native-card q replacements)
    (throw (ex-info "We don't (yet) support replacing field and table refs in cards with MBQL queries"
                    {:card card :replacements replacements}))))

(defn ->analyzable
  "Given a partial card or its id, ensure that we have all the fields required for analysis."
  [card-or-id]
  (if (and (map? card-or-id) (every? (partial contains? card-or-id) [:id :archived :dataset_query]))
    card-or-id
    (t2/select-one [:model/Card :id :archived :dataset_query] (u/the-id card-or-id))))

(defn analyze-card!
  "Update the analysis for a given card if it is active. Should only be called from [[metabase.task.analyze-queries]]."
  [card-or-id]
  (let [card    (->analyzable card-or-id)
        card-id (:id card)]
      (cond
        (not card)       (log/warnf "Card not found: %s" card-id)
        (:archived card) (log/warnf "Skipping archived card: %s" card-id)
        :else            (log/infof "Performing query analysis for card %s" card-id))
      (when (and card (not (:archived card)))
        (update-query-analysis-for-card! card))))

(defn next-card-id!
  "Get the id of the next card id to be analyzed. May block indefinitely, relies on producer.
  Should only be called from [[metabase.task.analyze-queries]]."
  ([]
   (next-card-id! worker-queue))
  ([queue]
   (next-card-id! queue Long/MAX_VALUE))
  ([queue timeout]
   (queue/blocking-take! queue timeout)))

(defn- queue-or-analyze!
  "Indirection used to modify the execution strategy for analysis in dev and tests."
  [offer-fn! card-or-id]
  (case (execution)
    ::immediate (analyze-card! (u/the-id card-or-id))
    ::queued    (offer-fn! (u/the-id card-or-id))
    ::disabled  nil))

(defn analyze-async!
  "Asynchronously hand-off the given card for analysis, at a high priority. This is typically the method you want."
  ([card-or-id]
   (analyze-async! worker-queue card-or-id))
  ([queue card-or-id]
   (queue-or-analyze! (partial queue/maybe-put! queue) card-or-id)))

(defn analyze-sync!
  "Synchronously hand-off the given card for analysis, at a low priority. May block indefinitely, relies on consumer."
  ([card-or-id]
   (analyze-sync! card-or-id worker-queue))
  ([card-or-id queue]
   (analyze-sync! card-or-id queue Long/MAX_VALUE))
  ([card-or-id queue timeout]
   (queue-or-analyze! (partial queue/blocking-put! queue timeout) card-or-id)))
