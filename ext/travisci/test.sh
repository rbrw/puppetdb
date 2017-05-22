#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

cat /etc/hosts
hostname --fqdn

run-unit-tests()
(
  pgdir="$(pwd)/test-resources/var/pg"

  if test "$PDB_TEST_PG" = 10; then

      for minor in {1..6}; do
        #sudo -i service postgresql stop "9.$minor"
        sudo -i apt-get remove -ym postgresql-"9.$minor"
      done

      sudo -i service postgresql stop
      ps aux | grep postgres
      ls -l /var/lib/dpkg/info/*postgres*.list

      sudo -i apt-get install wget ca-certificates
      echo "deb http://apt.postgresql.org/pub/repos/apt/ $(lsb_release -cs)-pgdg main 10" \
           | sudo -i bash -c 'cat > /etc/apt/sources.list.d/pgdg.list'
      wget --quiet -O - 'https://www.postgresql.org/media/keys/ACCC4CF8.asc' \
          | sudo -i apt-key add -
      sudo -i apt-get update
      ps aux | grep postgres
      sudo -i apt-get install -y --purge \
           postgresql-10 postgresql-client-10 postgresql-contrib-10
      sudo -i service postgresql start 10
  fi

  export PGHOST=127.0.0.1
  export PGPORT=5432
  ext/bin/setup-pdb-pg "$pgdir"
  ext/bin/pdb-test-env "$pgdir" lein test "$PDB_TEST_SELECTOR"
)

echo FINALLY TESTING
date

case "$PDB_TEST_LANG" in
  clojure)
    java -version
    ruby -v
    gem install bundler
    bundle install --without acceptance
    lein install-gems
    run-unit-tests
    ;;
  ruby)
    ruby -v
    gem install bundler
    bundle install --without acceptance
    cd puppet
    bundle exec rspec spec/
    ;;
  *)
    echo "Invalid language: $PDB_TEST_LANG" 1>&2
    exit 1
    ;;
esac
