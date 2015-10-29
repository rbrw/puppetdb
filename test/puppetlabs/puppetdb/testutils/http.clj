(ns puppetlabs.puppetdb.testutils.http
  (:require [clj-http.client :as client]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.middleware
             :refer [wrap-with-puppetdb-middleware]]
            [puppetlabs.puppetdb.cheshire :as json]))

(defn pdb-get
  "Makes a GET reqeust using to the PuppetDB instance at `base-url`
  with `url-suffix`. Will parse the body of the response if it has a
  json content type."
  [base-url url-suffix]
  (let [resp (client/get (str (utils/base-url->str base-url)
                              url-suffix)
                         {:throw-exceptions false})]
    (if (tu/json-content-type? resp)
      (update resp :body #(json/parse-string % true))
      resp)))

(defn vector-param
  [method order-by]
  (if (= :get method)
    (json/generate-string order-by)
    order-by))

(def ^:dynamic *app* nil)

(defn query-response
  ([method endpoint]      (query-response method endpoint nil))
  ([method endpoint query] (query-response method endpoint query {}))
  ([method endpoint query params]
   (*app* (tu/query-request method endpoint query {:params params}))))

(defn ordered-query-result
  ([method endpoint] (ordered-query-result method endpoint nil))
  ([method endpoint query] (ordered-query-result method endpoint query {}))
  ([method endpoint query params & optional-handlers]
   (let [handlers (or optional-handlers [identity])
         handle-fn (apply comp (vec handlers))
         response (query-response method endpoint query params)]
     (is (= http/status-ok (:status response)))
     (-> response
         :body
         slurp
         (json/parse-string true)
         vec
         handle-fn))))

(defn query-result
  ([method endpoint] (query-result method endpoint nil))
  ([method endpoint query] (query-result method endpoint query {}))
  ([method endpoint query params & optional-handlers]
   (apply #(ordered-query-result method endpoint query params set %)
          (or optional-handlers [identity]))))

(defn call-with-http-app
  "Builds an HTTP app and make it available as *app* during the
  execution of (f)."
  ([f]
   (call-with-http-app {} f))
  ([globals-overrides f]
   (let [get-shared-globals #(merge {:scf-read-db *db*
                                     :scf-write-db *db*
                                     :url-prefix ""}
                                    globals-overrides)]
     (binding [*app* (wrap-with-puppetdb-middleware
                      (server/build-app get-shared-globals)
                      nil)]
       (f)))))

(defmacro with-http-app
  ([maybe-globals-overrides & body]
   (if (map? maybe-globals-overrides)
     `(call-with-http-app maybe-globals-overrides (fn [] ~@body))
     `(call-with-http-app (fn [] ~@body)))))

(defmacro deftest-http-app [name bindings & body]
  (let [case-versions (remove keyword? (take-nth 2 bindings))]
    `(deftest ~name
       (tu/dotestseq ~bindings
         (with-test-db
           (with-http-app (fn [] ~@body)))))))

(defmacro deftest-command-app [name bindings & body]
  (let [case-versions (remove keyword? (take-nth 2 bindings))]
    `(deftest ~name
       (tu/dotestseq ~bindings
         (with-test-db
           (tu/call-with-test-mq
             (fn [] (tu/call-with-command-app (fn [] ~@body)))))))))
