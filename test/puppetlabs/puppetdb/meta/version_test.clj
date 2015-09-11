(ns puppetlabs.puppetdb.meta.version-test
  (:require [puppetlabs.puppetdb.meta.version :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer [with-test-db *db-spec*]]))

(deftest check-for-updates!-test
  (testing "check-for-updates! swallows exceptions"
    (testing "can't connect to server"
      (with-test-db
        (fn []
          (is (nil? (check-for-updates! "http://updates.foo.com" *db-spec*))))))
    (testing "can't connect to database"
      (is (nil? (check-for-updates! "http://updates.puppetlabs.com" {:invalid :connection}))))))
