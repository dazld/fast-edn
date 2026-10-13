#!/bin/bash
# classes/ is checked in; fail when it differs from a fresh compile of src/.
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

fresh=$(mktemp -d)
trap 'rm -rf "$fresh"' EXIT
clojure -T:build compile :class-dir "\"$fresh\""
if ! diff -r classes "$fresh" > /dev/null; then
  echo "classes/ is stale: run clojure -T:build compile and commit classes/" >&2
  exit 1
fi
