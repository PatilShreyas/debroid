#!/usr/bin/env bash

# Script to read a specific version's changelog section from CHANGELOG.md

if [ -z "$1" ]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 UNRELEASED"
  echo "Example: $0 v0.0.1"
  exit 1
fi

VERSION=$1
CHANGELOG_FILE="CHANGELOG.md"

if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "Error: $CHANGELOG_FILE not found."
  exit 1
fi

# We use awk to extract the section.
# We look for a line starting with "## [$VERSION]"
# We print lines until we see the next line starting with "## ["

awk -v version="$VERSION" '
  $0 ~ "^## \\[" version "\\]" { 
    found = 1; 
    print; 
    next; 
  }
  /^## \[/ { 
    if (found) { 
      exit; 
    } 
  }
  found { 
    print; 
  }
' "$CHANGELOG_FILE" | sed -e :a -e '/^\n*$/{$d;N;};/\n$/ba'
# The sed command trims trailing blank lines
