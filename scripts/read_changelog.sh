#!/usr/bin/env bash

# Script to read a specific version's changelog section from CHANGELOG.md

if [ -z "$1" ]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 UNRELEASED"
  echo "Example: $0 v0.0.1"
  exit 1
fi

VERSION=$1
CHANGELOG_FILE="${CHANGELOG_FILE:-${2:-CHANGELOG.md}}"

if [ ! -f "$CHANGELOG_FILE" ]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
  if [ -f "${REPO_ROOT}/CHANGELOG.md" ]; then
    CHANGELOG_FILE="${REPO_ROOT}/CHANGELOG.md"
  else
    echo "Error: $CHANGELOG_FILE not found."
    exit 1
  fi
fi

# We use awk to extract the release section and filter empty subsections.
# 1. Matches release header: "## [<version>]" (also supports version without "v" prefix).
# 2. Tracks preamble lines before any subsection ("### ...").
# 3. Dynamically identifies any subsection ("### ...") and buffers lines until the next subsection or release.
# 4. Filters out any subsections that contain no non-blank content.
# 5. Formats the output with clean markdown spacing, omitting empty sections entirely.

awk -v version="$VERSION" '
  BEGIN {
    found = 0
    preamble_len = 0
    sec_count = 0
    in_section = 0
  }

  index($0, "## [" version "]") == 1 || index($0, "## [v" version "]") == 1 {
    found = 1
    next
  }

  found && /^## \[/ {
    exit
  }

  found {
    if ($0 ~ "^###[ \t]+") {
      in_section = 1
      sec_count++
      sec_header[sec_count] = $0
      sec_lines[sec_count] = 0
      sec_has_content[sec_count] = 0
      next
    }

    if (!in_section) {
      preamble_len++
      preamble_line[preamble_len] = $0
    } else {
      sec_lines[sec_count]++
      sec_body[sec_count, sec_lines[sec_count]] = $0
      if ($0 ~ /[^ \t\r\n]/) {
        sec_has_content[sec_count] = 1
      }
    }
  }

  END {
    # Trim leading and trailing blank lines in preamble
    p_start = 1
    while (p_start <= preamble_len && preamble_line[p_start] ~ /^[ \t\r\n]*$/) {
      p_start++
    }
    p_end = preamble_len
    while (p_end >= p_start && preamble_line[p_end] ~ /^[ \t\r\n]*$/) {
      p_end--
    }

    has_output = 0

    if (p_start <= p_end) {
      for (i = p_start; i <= p_end; i++) {
        print preamble_line[i]
      }
      has_output = 1
    }

    for (s = 1; s <= sec_count; s++) {
      if (sec_has_content[s]) {
        if (has_output) {
          print ""
        }
        print sec_header[s]

        b_start = 1
        while (b_start <= sec_lines[s] && sec_body[s, b_start] ~ /^[ \t\r\n]*$/) {
          b_start++
        }
        b_end = sec_lines[s]
        while (b_end >= b_start && sec_body[s, b_end] ~ /^[ \t\r\n]*$/) {
          b_end--
        }

        for (j = b_start; j <= b_end; j++) {
          print sec_body[s, j]
        }
        has_output = 1
      }
    }
  }
' "$CHANGELOG_FILE" | sed -e :a -e '/^\n*$/{$d;N;};/\n$/ba'

