#!/usr/bin/env bash
set -euo pipefail

# Verify that a release tag matches build.gradle version and README.md references.
# Usage: check-version.sh <tag>   (e.g. check-version.sh v4.0)

TAG="${1:?Usage: check-version.sh <tag>}"
TAG_VERSION="${TAG#v}"

GRADLE_VERSION=$(grep -oP "^version = '\K[^']+" build.gradle)
if [ "$GRADLE_VERSION" != "$TAG_VERSION" ]; then
  echo "ERROR: build.gradle version '$GRADLE_VERSION' does not match tag '$TAG' (expected '$TAG_VERSION')"
  exit 1
fi

README_COUNT=$(grep -c "$TAG" README.md || true)
if [ "$README_COUNT" -ne 9 ]; then
  echo "ERROR: Expected 9 occurrences of '$TAG' in README.md, found $README_COUNT"
  grep -n "$TAG" README.md || true
  exit 1
fi

echo "Version check passed: build.gradle=$GRADLE_VERSION, README.md occurrences=$README_COUNT"
