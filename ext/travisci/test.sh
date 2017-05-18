#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

cat /etc/hosts
hostname --fqdn

run-unit-tests()
(
  pgdir="$(pwd)/test-resources/var/pg"
  readonly pgdir

  if test "$PDB_TEST_PG" = 10; then
      sudo -i apt-get purge postgresql-9.6 postgresql-9.6-client postgresql-contrib-9.6

      sudo -i 'echo "deb http://apt.postgresql.org/pub/repos/apt/ $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
      sudo -i apt-get install -y --purge \
           postgresql-10 postgresql-10-client postgresql-contrib-10
  fi

  export PGHOST=127.0.0.1
  export PGPORT=5432
  ext/bin/setup-pdb-pg "$pgdir"
  ext/bin/pdb-test-env "$pgdir" lein test "$PDB_TEST_SELECTOR"
)

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
