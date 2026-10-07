#!/usr/bin/env bash
# ==============================================================================
# Tests for scripts/read_changelog.sh
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
READ_SCRIPT="${SCRIPT_DIR}/read_changelog.sh"

PASSED_COUNT=0
FAILED_COUNT=0

log_info() {
    echo -e "\n\033[1;34m[INFO]\033[0m $*"
}

log_success() {
    echo -e "  \033[1;32m✓\033[0m $*"
    PASSED_COUNT=$((PASSED_COUNT + 1))
}

log_fail() {
    echo -e "  \033[1;31m✗\033[0m $*" >&2
    FAILED_COUNT=$((FAILED_COUNT + 1))
}

assert_equals() {
    local test_name="$1"
    local expected="$2"
    local actual="$3"

    if [ "$expected" = "$actual" ]; then
        log_success "$test_name"
    else
        log_fail "$test_name"
        echo "Expected:"
        echo "---"
        echo "$expected"
        echo "---"
        echo "Actual:"
        echo "---"
        echo "$actual"
        echo "---"
    fi
}

TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT

log_info "Running test suite for read_changelog.sh..."

# ------------------------------------------------------------------------------
# Test 1: Release with only empty sections should return empty output
# ------------------------------------------------------------------------------
log_info "Test 1: Release with completely empty sections returns empty string"
EMPTY_CHANGELOG="$TEST_DIR/EMPTY_CHANGELOG.md"
cat << 'EOF' > "$EMPTY_CHANGELOG"
# Changelog

## [UNRELEASED]

### Added

### Fixed

### Changed

EOF
OUTPUT=$("$READ_SCRIPT" "UNRELEASED" "$EMPTY_CHANGELOG")
assert_equals "UNRELEASED returns empty string when all sections are empty" "" "$OUTPUT"

# ------------------------------------------------------------------------------
# Test 1b: UNRELEASED on repository CHANGELOG.md filters empty sections
# ------------------------------------------------------------------------------
log_info "Test 1b: UNRELEASED on repository CHANGELOG.md"
OUTPUT=$("$READ_SCRIPT" "UNRELEASED")
if [ -n "$OUTPUT" ]; then
    if ! echo "$OUTPUT" | grep -q "^### Added" && ! echo "$OUTPUT" | grep -q "^### Changed"; then
        log_success "Repository UNRELEASED excludes empty ### Added / ### Changed"
    else
        log_fail "Repository UNRELEASED includes empty sections"
    fi
else
    log_success "Repository UNRELEASED is empty (all sections empty)"
fi


# ------------------------------------------------------------------------------
# Test 2: Existing release v0.3.1 has only Fixed (Added/Changed omitted)
# ------------------------------------------------------------------------------
log_info "Test 2: v0.3.1 on repository CHANGELOG.md"
OUTPUT=$("$READ_SCRIPT" "v0.3.1")
if echo "$OUTPUT" | grep -q "^### Fixed" && ! echo "$OUTPUT" | grep -q "^### Added" && ! echo "$OUTPUT" | grep -q "^### Changed"; then
    log_success "v0.3.1 includes ### Fixed and excludes ### Added / ### Changed"
else
    log_fail "v0.3.1 output did not match expected section filtering"
fi

# ------------------------------------------------------------------------------
# Test 3: v0.0.1 preamble without subsections
# ------------------------------------------------------------------------------
log_info "Test 3: v0.0.1 on repository CHANGELOG.md"
OUTPUT=$("$READ_SCRIPT" "v0.0.1")
assert_equals "v0.0.1 preserves preamble content" "- Initial release. First version of the CLI." "$OUTPUT"

# ------------------------------------------------------------------------------
# Test 4: Custom changelog with dynamic section names and empty sections
# ------------------------------------------------------------------------------
log_info "Test 4: Custom changelog with dynamic section names"
CUSTOM_CHANGELOG="$TEST_DIR/CHANGELOG_CUSTOM.md"
cat << 'EOF' > "$CUSTOM_CHANGELOG"
# Changelog

## [UNRELEASED]

### Added

### Security
- Patched critical vulnerability

### Performance

### Deprecated

### Fixed
- Fixed bug 123

## [v1.0.0] - 2026-01-01

- Initial stable release
EOF

OUTPUT=$(CHANGELOG_FILE="$CUSTOM_CHANGELOG" "$READ_SCRIPT" "UNRELEASED")
EXPECTED=$(cat << 'EOF'
### Security
- Patched critical vulnerability

### Fixed
- Fixed bug 123
EOF
)
assert_equals "Dynamic empty sections (Added, Performance, Deprecated) are filtered out" "$EXPECTED" "$OUTPUT"

# ------------------------------------------------------------------------------
# Test 5: Preamble combined with non-empty and empty sections
# ------------------------------------------------------------------------------
log_info "Test 5: Preamble with mixed sections"
cat << 'EOF' > "$CUSTOM_CHANGELOG"
# Changelog

## [v2.0.0] - 2026-02-01

This is a major release with breaking changes.
Please read the upgrade guide.

### Added

### Features
- New pipeline feature

### Removed

EOF

OUTPUT=$(CHANGELOG_FILE="$CUSTOM_CHANGELOG" "$READ_SCRIPT" "v2.0.0")
EXPECTED=$(cat << 'EOF'
This is a major release with breaking changes.
Please read the upgrade guide.

### Features
- New pipeline feature
EOF
)
assert_equals "Preamble and non-empty dynamic sections are preserved; empty sections filtered" "$EXPECTED" "$OUTPUT"

# ------------------------------------------------------------------------------
# Test 6: Unknown version returns empty output
# ------------------------------------------------------------------------------
log_info "Test 6: Non-existent version"
OUTPUT=$(CHANGELOG_FILE="$CUSTOM_CHANGELOG" "$READ_SCRIPT" "v999.9.9")
assert_equals "Non-existent version returns empty output" "" "$OUTPUT"

# ------------------------------------------------------------------------------
# Test 7: Section with sub-headings (####) and blank lines preserved
# ------------------------------------------------------------------------------
log_info "Test 7: Section with sub-headings (####)"
cat << 'EOF' > "$CUSTOM_CHANGELOG"
# Changelog

## [v3.0.0] - 2026-03-01

### Features
#### CLI
- Feature in CLI

#### Core
- Feature in Core

### EmptySection

EOF

OUTPUT=$("$READ_SCRIPT" "v3.0.0" "$CUSTOM_CHANGELOG")
EXPECTED=$(cat << 'EOF'
### Features
#### CLI
- Feature in CLI

#### Core
- Feature in Core
EOF
)
assert_equals "Subheadings (####) are treated as section content; empty section dropped" "$EXPECTED" "$OUTPUT"

# ------------------------------------------------------------------------------
# Test 8: Passing version without 'v' prefix matches 'v' version in changelog
# ------------------------------------------------------------------------------
log_info "Test 8: Version without 'v' prefix"
OUTPUT=$("$READ_SCRIPT" "3.0.0" "$CUSTOM_CHANGELOG")
assert_equals "Version without 'v' prefix matches v3.0.0" "$EXPECTED" "$OUTPUT"

# ------------------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------------------
echo ""
echo "=============================================================================="
echo "Tests Passed: $PASSED_COUNT"
echo "Tests Failed: $FAILED_COUNT"
echo "=============================================================================="

if [ "$FAILED_COUNT" -gt 0 ]; then
    exit 1
fi
