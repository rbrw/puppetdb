(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc :as sql]
            [environ.core :refer [env]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :refer [transform-data]]))

(defn valid-sql-id? [id]
  (re-matches #"[a-zA-Z][a-zA-Z0-9_]*" id))

(def test-env
  (let [user (env :pdb-test-db-user (env :puppetdb-dbuser "puppetdb"))
        admin (env :pdb-test-db-admin "pdb_test_admin")]
    ;; Since we're going to use these in raw SQL later (i.e. not via ?).
    (doseq [[who name] [[:user user] [:admin admin]]]
      (when-not (valid-sql-id? name)
        (binding [*out* *err*]
          (println (format "Invalid test %s name %s" who (pr-str name)))
          (flush))
        (System/exit 1)))
    {:host (env :pdb-test-db-host "127.0.0.1")
     :port (env :pdb-test-db-port 5432)
     :user {:name user
            :password (env :pdb-test-db-user-password "puppetdb")}
     :admin {:name admin
             :password (env :pdb-test-db-admin-password "pdb_test_admin")}}))

(def sample-db-config
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (env :puppetdb-dbsubname "//127.0.0.1:5432/foo")
   :user "puppetdb"
   :password "xyzzy"})

(defn db-admin-config
  ([] (db-admin-config "template1"))
  ([database]
    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname (format "//%s:%s/%s" (:host test-env) (:port test-env) database)
     :user (get-in test-env [:admin :name])
     :password (get-in test-env [:admin :password])}))

(defn db-user-config
  [database]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (format "//%s:%s/%s" (:host test-env) (:port test-env) database)
   :user (get-in test-env [:user :name])
   :password (get-in test-env [:user :password])})

(defn validated-subname-db [subname]
  (let [sep (.lastIndexOf subname "/")]
    (assert (pos? sep))
    (let [name (subs subname (inc sep))]
      (assert (valid-sql-id? name))
      name)))

(defn init-db [db read-only?]
  (jdbc/with-db-connection db (migrate! db))
  (jdbc/pooled-datasource (assoc db :read-only? read-only?)))

(defn drop-table!
  "Drops a table from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [table-name]
  (jdbc/do-commands (format "DROP TABLE IF EXISTS %s CASCADE" table-name)))

(defn drop-sequence!
  "Drops a sequence from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [sequence-name]
  (jdbc/do-commands (format "DROP SEQUENCE IF EXISTS %s" sequence-name)))

(defn clear-db-for-testing!
  "Completely clears the database specified by config (or the current
  database), dropping all puppetdb tables and other objects that exist
  within it. Expects to be called from within a db binding.  You
  Exercise extreme caution when calling this function!"
  ([config]
   (jdbc/with-db-connection config (clear-db-for-testing!)))
  ([]
   (jdbc/do-commands "DROP SCHEMA IF EXISTS pdbtestschema CASCADE")
   (doseq [table-name (cons "test" (sutils/sql-current-connection-table-names))]
     (drop-table! table-name))
   (doseq [sequence-name (cons "test" (sutils/sql-current-connection-sequence-names))]
     (drop-sequence! sequence-name))))

(def ^:private templates-created (atom false))

(defn- full-sql-exception-msg [ex]
  (apply str (take-while identity (iterate #(.getNextException %) ex))))

(defn- ensure-pdb-db-templates-exist []
  (locking ensure-pdb-db-templates-exist
    (when-not @templates-created
      (jdbc/with-db-connection (db-admin-config)
        (let [conn (doto (:connection (jdbc/db)) (.setAutoCommit true))
              ex (fn [cmd] (-> conn .createStatement (.execute cmd)))]
          (ex (format "drop database if exists pdb_test_template"))
          (ex (format "create database pdb_test_template"))))
      (let [cfg (db-user-config "pdb_test_template")]
        (jdbc/with-db-connection cfg
          (clear-db-for-testing!)
          (migrate! cfg)))
      (reset! templates-created true))))

(def ^{:private true} test-db-counter (atom 0))

(defn create-temp-db []
  (ensure-pdb-db-templates-exist)
  (let [n (swap! test-db-counter inc)
        db-name (str "pdb_test_" n)]
    (jdbc/with-db-connection (db-admin-config)
      (jdbc/do-commands-outside-txn
       (format "drop database if exists %s" db-name)
       (format "create database %s" db-name)))
    (db-user-config db-name)))

(def ^:dynamic *db* nil)

(defn call-with-db-info-on-failure-or-drop [db-config f]
  "Calls (f), and then if there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
  (let [before @clojure.test/*report-counters*]
    (try
      (f)
      (finally
        (let [after @clojure.test/*report-counters*]
          (if (and (= (:error before) (:error after))
                   (= (:fail before) (:fail after)))
            (jdbc/with-db-connection (db-admin-config)
              (jdbc/do-commands-outside-txn
               (format "drop database if exists %s"
                       (validated-subname-db (:subname db-config)))))
            (binding [*out* *err*]
              (println "Leaving test database intact:" (:subname *db*)))))))))

(defmacro with-db-info-on-failure-or-drop [db-config & body]
  "Evaluates body in the context of call-with-db-info-on-failure-or-drop."
  `(call-with-db-info-on-failure-or-drop ~db-config (fn [] ~@body)))

(defn with-db-metadata
  "A fixture to collect DB type and version information before a test."
  [f]
  (binding [*db* (create-temp-db)] ;; FIXME: do we really want a new DB?
    (with-db-info-on-failure-or-drop *db*
      (jdbc/with-db-connection *db*
        (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
          (f))))))

(defn call-with-test-db [f]
  "Binds *db* to a clean, migrated test database, opens a connection
  to it, and calls (f).  If there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
  (binding [*db* (create-temp-db)]
    (with-db-info-on-failure-or-drop *db*
      (jdbc/with-db-connection *db*
        (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
          (migrate! *db*)
          (f))))))

(defmacro with-test-db [& body]
  `(call-with-test-db (fn [] ~@body)))

(defn without-db-var
  "Binds the java.jdbc dtabase connection to nil. When running a unit
   test using `call-with-test-db`, jint/*db* will be bound. If the routes
   being tested don't explicitly bind the db connection, it will use
   one bound in call-with-test-db. This causes a problem at runtime that
   won't show up in the unit tests. This fixture can be used around
   route testing code to ensure that the route has it's own db
   connection."
  [f]
  (binding [jdbc/*db* nil]
    (f)))

(defn defaulted-write-db-config
  "Defaults and converts `db-config` from the write database INI
  format to the internal write database format"
  [db-config]
  (transform-data conf/write-database-config-in
                  conf/write-database-config-out
                  db-config))

(defn defaulted-read-db-config
  "Defaults and converts `db-config` from the read-database INI format
  to the internal read database format"
  [db-config]
  (transform-data conf/database-config-in
                  conf/database-config-out
                  db-config))

(def ^:dynamic *db-spec* nil)

(def antonym-data {"absence"    "presence"
                   "abundant"   "scarce"
                   "accept"     "refuse"
                   "accurate"   "inaccurate"
                   "admit"      "deny"
                   "advance"    "retreat"
                   "advantage"  "disadvantage"
                   "alive"      "dead"
                   "always"     "never"
                   "ancient"    "modern"
                   "answer"     "question"
                   "approval"   "disapproval"
                   "arrival"    "departure"
                   "artificial" "natural"
                   "ascend"     "descend"
                   "blandness"  "zest"
                   "lethargy"   "zest"})

(defn insert-map [data]
  (apply (partial jdbc/insert! :test [:key :value]) data))

(defn call-with-antonym-test-database
  [function]
  (let [db (create-temp-db)]
    (binding [*db-spec* db]
      (jdbc/with-db-connection db
        (jdbc/with-db-transaction []
          (jdbc/do-commands
           (sql/create-table-ddl :test
                                 [:key "VARCHAR(256)" "PRIMARY KEY"]
                                 [:value "VARCHAR(256)" "NOT NULL"]))
          (insert-map antonym-data))
        (function)))))
