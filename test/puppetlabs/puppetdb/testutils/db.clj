(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc :as sql]
            [environ.core :refer [env]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils :refer [clear-db-for-testing!]]))

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

(defn with-antonym-test-database
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
