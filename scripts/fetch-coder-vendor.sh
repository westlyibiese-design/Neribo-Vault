#!/usr/bin/env bash
# Downloads the browser builds of React 18 and Babel 7 that Coder bundles, so React previews work offline.
# Runs in GitHub Actions before Gradle. Needs Node and npm (both are preinstalled on ubuntu-latest) and internet.
set -euo pipefail

ROOT="$(pwd)"
DEST="$ROOT/app/src/main/assets/coder/vendor"
REACT_VERSION="18.3.1"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$DEST"

BABEL_VERSION="$(npm view '@babel/standalone@7' version --json | node -e '
  let s = "";
  process.stdin.on("data", d => s += d).on("end", () => {
    const v = JSON.parse(s);
    console.log(Array.isArray(v) ? v[v.length - 1] : v);
  });
')"
echo "Using React ${REACT_VERSION} and Babel ${BABEL_VERSION}"

cd "$WORK"
npm pack --silent "react@${REACT_VERSION}" "react-dom@${REACT_VERSION}" "@babel/standalone@${BABEL_VERSION}" > /dev/null

for pkg in react react-dom babel-standalone; do
  mkdir "x-${pkg}"
  tar -xzf ${pkg}-[0-9]*.tgz -C "x-${pkg}"
done

cp "x-react/package/umd/react.development.js" "$DEST/react.development.js"
cp "x-react-dom/package/umd/react-dom.development.js" "$DEST/react-dom.development.js"
cp "x-babel-standalone/package/babel.min.js" "$DEST/babel.min.js"

require_size() {
  local file="$1" minimum="$2" size
  size="$(stat -c%s "$file")"
  if [ "$size" -lt "$minimum" ]; then
    echo "Downloaded file is too small: $file ($size bytes, expected at least $minimum)"
    exit 1
  fi
}
require_size "$DEST/react.development.js" 20000
require_size "$DEST/react-dom.development.js" 500000
require_size "$DEST/babel.min.js" 1000000

{
  echo "Coder preview libraries. Unmodified copies from the npm registry; do not edit."
  echo "Fetched by scripts/fetch-coder-vendor.sh during the build."
  echo
  for entry in "react.development.js|react@${REACT_VERSION}" "react-dom.development.js|react-dom@${REACT_VERSION}" "babel.min.js|@babel/standalone@${BABEL_VERSION}"; do
    file="${entry%%|*}"; source="${entry##*|}"
    echo "${file}  (${source}, MIT license)  sha256=$(sha256sum "$DEST/$file" | cut -d' ' -f1)  bytes=$(stat -c%s "$DEST/$file")"
  done
} > "$DEST/VENDOR.txt"
cat "$DEST/VENDOR.txt"
