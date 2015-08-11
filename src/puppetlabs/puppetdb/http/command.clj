(ns puppetlabs.puppetdb.http.command
  (:require [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [net.cgrand.moustache :as moustache]
            [puppetlabs.puppetdb.middleware :as mid]
            [compojure.core :as compojure]))

(def min-supported-commands
  {"replace catalog" 6
   "replace facts" 4
   "store report" 5
   "deactivate node" 3})

(def valid-commands-str (str/join ", " (sort (vals command-names))))

(defn validate-command-version
  [app]
  (fn [{:keys [body-string] :as req}]
    (let [{:keys [command version]} (json/parse-string body-string true)
          min-supported (get min-supported-commands command ::invalid)]
      (cond
        (= ::invalid min-supported)
        (http/bad-request-response
          (format "Supported commands are %s. Received '%s'."
                  valid-commands-str command))

        (< version min-supported)
        (http/bad-request-response
          (format "%s version %s is retired. The minimum supported version is %s."
                  command version min-supported))

        :else (app req)))))

(defn enqueue-command-handler
  "Enqueues the command in request and returns a UUID"
  [get-command-mq enqueue-fn]
  (fn [{:keys [body-string] :as request}]
    (let [{:keys [connection endpoint]} (get-command-mq)
          uuid (enqueue-fn connection endpoint body-string)]
      (http/json-response {:uuid uuid}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(defn command-app
  [get-authorizer get-command-mq get-shared-globals enqueue-fn]
  (-> (moustache/app
       ["v1" &] {:any (enqueue-command-handler get-command-mq enqueue-fn)})
      validate-command-version
      mid/verify-accepts-json
      mid/verify-checksum
      (mid/validate-query-params {:optional ["checksum"]})
      mid/payload-to-body-string
      (mid/verify-content-type ["application/json"])
      (mid/wrap-with-puppetdb-middleware get-authorizer)
      (mid/wrap-with-metrics (atom {}) http/leading-uris)
      (mid/wrap-with-globals get-shared-globals)))

(defprotocol PuppetDBCommand
  (submit-command [this command version payload]))

(defservice puppetdb-command-service
  PuppetDBCommand
  [[:PuppetDBServer shared-globals]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBCommandDispatcher enqueue-command enqueue-raw-command]]
  (start [this context]
         (log/info "Starting command service")
         context)
  (submit-command [this command version payload]
    (let [{{:keys [connection endpoint]} :command-mq} (shared-globals)]
      (enqueue-command connection endpoint
                       (command-names command) version payload))))
