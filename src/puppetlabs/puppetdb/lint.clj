(ns puppetlabs.puppetdb.lint)

(defmacro ignore-value [& body]
  `(let [x# (do ~@body)]
     (assert (or x# (not x#)))
     nil))
