(ns puppetlabs.puppetdb.cli.import
  "Import utility

   This is a command-line tool for importing data into PuppetDB. It expects
   as input a tarball generated by the PuppetDB `export` command-line tool."
  (:import  [puppetlabs.puppetdb.archive TarGzReader]
            [org.apache.commons.compress.archivers.tar TarArchiveEntry])
  (:require [fs.core :as fs]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils
             :refer [base-url-schema export-root-dir]]
            [puppetlabs.kitchensink.core :refer [cli!]]
            [puppetlabs.puppetdb.cli.export :refer [export-metadata-file-name]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(def cli-description "Import PuppetDB catalog data from a backup file")

(defn check-import
  "Checks for the existence of a file. If filename exists, does nothing.
  If filename does not exist print a message and execute f"
  [filename f]
  (if (fs/exists? filename)
    true
    (do
      (println (format "Import from %s failed. File not found." filename))
      (f))))

(defn parse-metadata
  "Parses the export metadata file to determine, e.g., what versions of the
  commands should be used during import."
  [tarball]
  {:post [(map? %)
          (contains? % :command-versions)]}
  (check-import tarball #(System/exit 0))
  (let [metadata-path (.getPath (io/file export-root-dir export-metadata-file-name))]
    (with-open [tar-reader (archive/tarball-reader tarball)]
      (when-not (archive/find-entry tar-reader metadata-path)
        (throw (IllegalStateException.
                (format "Unable to find export metadata file '%s' in archive '%s'"
                        metadata-path
                        tarball))))
      (json/parse-string (archive/read-entry-content tar-reader) true))))


(defn-validated process-tar-entry
  "Determine the type of an entry from the exported archive, and process it
  accordingly."
  [^TarGzReader tar-reader :- TarGzReader
   ^TarArchiveEntry tar-entry :- TarArchiveEntry
   dest :- base-url-schema
   metadata :- {s/Any s/Any}]
  (let [path    (.getName tar-entry)
        catalog-pattern (str "^" (.getPath (io/file export-root-dir "catalogs" ".*\\.json")) "$")
        report-pattern (str "^" (.getPath (io/file export-root-dir "reports" ".*\\.json")) "$")
        facts-pattern (str "^" (.getPath (io/file export-root-dir "facts" ".*\\.json")) "$")]
    (when (re-find (re-pattern catalog-pattern) path)
      (println (format "Importing catalog from archive entry '%s'" path))
      ;; NOTE: these submissions are async and we have no guarantee that they
      ;;   will succeed. We might want to add something at the end of the import
      ;;   that polls puppetdb until the command queue is empty, then does a
      ;;   query to the /nodes endpoint and shows the set difference between
      ;;   the list of nodes that we submitted and the output of that query
      (client/submit-catalog dest
                             (get-in metadata [:command-versions :replace-catalog])
                             (archive/read-entry-content tar-reader)))
    (when (re-find (re-pattern report-pattern) path)
      (println (format "Importing report from archive entry '%s'" path))
      (client/submit-report dest
                            (get-in metadata [:command-versions :store-report])
                            (archive/read-entry-content tar-reader)))
    (when (re-find (re-pattern facts-pattern) path)
      (println (format "Importing facts from archive entry '%s'" path))
      (client/submit-facts dest
                           (get-in metadata [:command-versions :replace-facts])
                           (archive/read-entry-content tar-reader)))))

(defn- validate-cli!
  [args]
  (let [specs    [["-i" "--infile INFILE" "Path to backup file (required)"]
                  ["-H" "--host HOST" "Hostname of PuppetDB server" :default "localhost"]
                  ["-p" "--port PORT" "Port to connect to PuppetDB server (HTTP protocol only)" :parse-fn #(Integer. %) :default 8080]
                  ["" "--url-prefix PREFIX" "Server prefix (HTTP protocol only)"
                   :default ""]]
        required [:infile]]
    (try+
     (cli! args specs required)
     (catch map? m
       (println (:message m))
       (case (:type m)
         :puppetlabs.kitchensink.core/cli-error (System/exit 1)
         :puppetlabs.kitchensink.core/cli-help  (System/exit 0))))))

(defn- main
  [& args]
  (let [[{:keys [infile host port url-prefix]} _] (validate-cli! args)
        dest {:protocol "http" :host host :port port :prefix url-prefix}
        _ (when-let [why (utils/describe-bad-base-url dest)]
            (throw+ {:type ::invalid-url :utils/exit-status 1}
                    (format "Invalid destination (%s)" why)))
        metadata                       (parse-metadata infile)]
    ;; TODO: do we need to deal with SSL or can we assume this only works over a plaintext port?
    (with-open [tar-reader (archive/tarball-reader infile)]
      (doseq [tar-entry (archive/all-entries tar-reader)]
        (process-tar-entry tar-reader tar-entry dest metadata)))))

(def -main (utils/wrap-main main))
