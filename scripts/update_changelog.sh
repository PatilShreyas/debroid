#!/usr/bin/env bash

# Script to update CHANGELOG.md during a PR when version bumps
# It renames "## [UNRELEASED]" to "## [v<version>] - <date>"
# and adds a new "## [UNRELEASED]" section at the top.

if [ -z "$1" ]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 v0.0.1"
  exit 1
fi

VERSION=$1
CHANGELOG_FILE="CHANGELOG.md"
DATE=$(date +%Y-%m-%d)

if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "Error: $CHANGELOG_FILE not found."
  exit 1
fi

# Strip the leading 'v' if present for standardizing
if [[ "$VERSION" == v* ]]; then
  VERSION="${VERSION:1}"
fi

# The new content to insert
NEW_SECTION="## [UNRELEASED]\n\n### Added\n\n### Fixed\n\n### Changed\n\n"
RELEASE_SECTION="## [v$VERSION] - $DATE"

# We use awk to process the file and replace the UNRELEASED header
# and inject the new empty UNRELEASED header.

awk -v new_sec="$NEW_SECTION" -v rel_sec="$RELEASE_SECTION" '
  /^## \[UNRELEASED\]/ {
    print new_sec rel_sec
    next
  }
  { print }
' "$CHANGELOG_FILE" > "$CHANGELOG_FILE.tmp" && mv "$CHANGELOG_FILE.tmp" "$CHANGELOG_FILE"

echo "Changelog updated for v$VERSION on $DATE"
