#!/usr/bin/env bash
# ==============================================================================
# Integration Tests for Debroid install.sh
# ==============================================================================
# This script executes install.sh across various simulated shell environments
# (bash, zsh, fish) inside isolated temporary directories, verifying:
# 1. Binary is installed and executable at ~/.local/bin/debroid.
# 2. System PATH is registered in the shell profile (.bashrc, .zshrc, config.fish).
# 3. 'debroid --version' can be invoked directly after applying shell config.
# 4. AI Skills are extracted to ~/.debroid/skills/debroid-cli/SKILL.md.
# 5. Idempotent re-runs replace existing blocks without duplicating exports.
# 6. Symlinked dotfiles (GNU Stow / Chezmoi) are preserved without breaking links.
# 7. Already-configured PATH (runtime or file) is gracefully handled.
# 8. Unknown / unsupported shells fall back to manual instructions gracefully.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERSION="$(cat "${REPO_ROOT}/version.txt" | xargs)"
TEST_WORK_DIR="${REPO_ROOT}/build/test_install_workspace"

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

# Clean PATH by removing any existing ~/.local/bin or test directory entries
sanitize_path() {
    local clean_path=""
    IFS=':' read -ra ADDR <<< "$PATH"
    for p in "${ADDR[@]}"; do
        if [[ "$p" != *".local/bin"* && "$p" != *"/test_install_workspace/"* ]]; then
            if [ -z "$clean_path" ]; then
                clean_path="$p"
            else
                clean_path="${clean_path}:$p"
            fi
        fi
    done
    echo "$clean_path"
}

BASE_TEST_PATH="$(sanitize_path)"

# ==============================================================================
# Cleanup & Lifecycle Management
# ==============================================================================

cleanup_suite() {
    if [ -d "$TEST_WORK_DIR" ]; then
        rm -rf "$TEST_WORK_DIR"
    fi
}

trap cleanup_suite EXIT INT TERM

setup_test_env() {
    mkdir -p "$TEST_WORK_DIR"
    mktemp -d "${TEST_WORK_DIR}/env.XXXXXX"
}

teardown_test_env() {
    local tmp_dir="$1"
    if [ -d "$tmp_dir" ]; then
        rm -rf "$tmp_dir"
    fi
}

# ==============================================================================
# Invariant Assertions
# ==============================================================================

verify_installation_invariants() {
    local test_home="$1"

    # 1. Verify binary location and execution permissions
    local binary_path="${test_home}/.local/bin/debroid"
    if [ -f "$binary_path" ] && [ -x "$binary_path" ]; then
        log_success "Binary installed and executable at ${binary_path}"
    else
        log_fail "Binary missing or not executable at ${binary_path}"
    fi

    # 2. Verify AI skills extraction
    local skill_path="${test_home}/.debroid/skills/debroid-cli/SKILL.md"
    if [ -f "$skill_path" ] && [ -s "$skill_path" ]; then
        log_success "AI skills extracted to ${skill_path} ($(wc -c < "$skill_path" | xargs) bytes)"
    else
        log_fail "AI skills missing or empty at ${skill_path}"
    fi
}

# ------------------------------------------------------------------------------
# Test 1: Bash Environment
# ------------------------------------------------------------------------------
test_bash_installation() {
    log_info "Running Test: Bash environment installation"
    local test_home
    test_home="$(setup_test_env)"

    HOME="$test_home" SHELL="/bin/bash" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local >/dev/null

    verify_installation_invariants "$test_home"

    # Verify .bashrc content
    local bashrc="${test_home}/.bashrc"
    if [ -f "$bashrc" ] && grep -q "debroid installer" "$bashrc"; then
        log_success ".bashrc configured with installer block"
    else
        log_fail ".bashrc was not configured"
    fi

    # Verify direct invocation of debroid --version via bash subshell
    local version_output
    version_output="$(HOME="$test_home" PATH="$BASE_TEST_PATH" bash -c "source \"${bashrc}\" && debroid --version" 2>&1)"
    if [[ "$version_output" == *"debroid version ${VERSION}"* ]]; then
        log_success "Direct execution 'debroid --version' succeeded via configured bash (${version_output})"
    else
        log_fail "Direct execution failed or returned unexpected output: '${version_output}'"
    fi

    # Test Idempotency: Re-running install.sh should not duplicate blocks
    HOME="$test_home" SHELL="/bin/bash" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local >/dev/null
    local block_markers
    block_markers="$(grep -c "debroid installer" "$bashrc" || true)"
    if [ "$block_markers" -eq 2 ]; then
        log_success "Idempotency verified: exactly 1 installer block present after re-install"
    else
        log_fail "Idempotency failed: found ${block_markers} marker lines in ${bashrc}"
    fi

    teardown_test_env "$test_home"
}

# ------------------------------------------------------------------------------
# Test 2: Zsh Environment
# ------------------------------------------------------------------------------
test_zsh_installation() {
    log_info "Running Test: Zsh environment installation"

    local zsh_bin
    zsh_bin="$(command -v zsh || true)"
    if [ -z "$zsh_bin" ]; then
        echo "  [SKIPPED] zsh binary not found on this system"
        return 0
    fi

    local test_home
    test_home="$(setup_test_env)"

    HOME="$test_home" SHELL="$zsh_bin" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local >/dev/null

    verify_installation_invariants "$test_home"

    # Verify .zshrc content
    local zshrc="${test_home}/.zshrc"
    if [ -f "$zshrc" ] && grep -q "debroid installer" "$zshrc"; then
        log_success ".zshrc configured with installer block"
    else
        log_fail ".zshrc was not configured"
    fi

    # Verify direct invocation of debroid --version via zsh subshell
    local version_output
    version_output="$(HOME="$test_home" PATH="$BASE_TEST_PATH" zsh -c "source \"${zshrc}\" && debroid --version" 2>&1)"
    if [[ "$version_output" == *"debroid version ${VERSION}"* ]]; then
        log_success "Direct execution 'debroid --version' succeeded via configured zsh (${version_output})"
    else
        log_fail "Direct execution failed or returned unexpected output: '${version_output}'"
    fi

    teardown_test_env "$test_home"
}

# ------------------------------------------------------------------------------
# Test 3: Fish Environment
# ------------------------------------------------------------------------------
test_fish_installation() {
    log_info "Running Test: Fish environment installation"

    local fish_bin
    fish_bin="$(command -v fish || true)"
    if [ -z "$fish_bin" ]; then
        echo "  [SKIPPED] fish binary not found on this system"
        return 0
    fi

    local test_home
    test_home="$(setup_test_env)"

    HOME="$test_home" SHELL="$fish_bin" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local >/dev/null

    verify_installation_invariants "$test_home"

    # Verify config.fish content
    local config_fish="${test_home}/.config/fish/config.fish"
    if [ -f "$config_fish" ] && grep -q "fish_add_path" "$config_fish"; then
        log_success "config.fish configured with fish_add_path"
    else
        log_fail "config.fish was not configured with fish_add_path"
    fi

    # Verify direct invocation of debroid --version via fish subshell
    local version_output
    version_output="$(HOME="$test_home" PATH="$BASE_TEST_PATH" fish -c "source \"${config_fish}\" && debroid --version" 2>&1)"
    if [[ "$version_output" == *"debroid version ${VERSION}"* ]]; then
        log_success "Direct execution 'debroid --version' succeeded via configured fish (${version_output})"
    else
        log_fail "Direct execution failed or returned unexpected output: '${version_output}'"
    fi

    teardown_test_env "$test_home"
}

# ------------------------------------------------------------------------------
# Test 4: Symlinked Dotfiles (GNU Stow / Chezmoi)
# ------------------------------------------------------------------------------
test_symlinked_dotfiles() {
    log_info "Running Test: Symlinked dotfiles (GNU Stow / Chezmoi resolution)"
    local test_home
    test_home="$(setup_test_env)"

    # Create external dotfiles repo and symlink ~/.bashrc to it
    local dotfiles_dir="${test_home}/dotfiles"
    mkdir -p "$dotfiles_dir"
    echo "# Custom dotfile" > "${dotfiles_dir}/bashrc"
    ln -s "${dotfiles_dir}/bashrc" "${test_home}/.bashrc"

    HOME="$test_home" SHELL="/bin/bash" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local >/dev/null

    # Assert ~/.bashrc is still a symbolic link
    if [ -L "${test_home}/.bashrc" ]; then
        log_success "~/.bashrc remained a valid symbolic link"
    else
        log_fail "~/.bashrc was replaced with a regular file"
    fi

    # Assert the target dotfile was modified
    if grep -q "debroid installer" "${dotfiles_dir}/bashrc"; then
        log_success "Underlying target dotfile was updated with installer block"
    else
        log_fail "Underlying target dotfile was not updated"
    fi

    teardown_test_env "$test_home"
}

# ------------------------------------------------------------------------------
# Test 5: Pre-configured .local/bin in shell config
# ------------------------------------------------------------------------------
test_preconfigured_shell_config() {
    log_info "Running Test: Pre-existing .local/bin in shell config file"
    local test_home
    test_home="$(setup_test_env)"

    # Pre-populate .bashrc with custom .local/bin PATH export
    echo 'export PATH="$HOME/.local/bin:$PATH"' > "${test_home}/.bashrc"

    HOME="$test_home" SHELL="/bin/bash" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local >/dev/null

    verify_installation_invariants "$test_home"

    # Should NOT have injected an extra # >>> debroid installer >>> block
    if ! grep -q "debroid installer" "${test_home}/.bashrc"; then
        log_success "Did not append duplicate block when .local/bin was already configured in .bashrc"
    else
        log_fail "Unexpected installer block appended to already-configured .bashrc"
    fi

    teardown_test_env "$test_home"
}

# ------------------------------------------------------------------------------
# Test 6: Unknown / Unsupported Shell Fallback
# ------------------------------------------------------------------------------
test_unsupported_shell_fallback() {
    log_info "Running Test: Unsupported shell fallback handling"
    local test_home
    test_home="$(setup_test_env)"

    local output
    output="$(HOME="$test_home" SHELL="/bin/customsh" PATH="$BASE_TEST_PATH" "${REPO_ROOT}/install.sh" --local 2>&1)"

    verify_installation_invariants "$test_home"

    if [[ "$output" == *"Could not automatically detect a supported shell profile"* ]]; then
        log_success "Handled unsupported shell gracefully with manual instructions"
    else
        log_fail "Did not print unsupported shell fallback message"
    fi

    teardown_test_env "$test_home"
}

# ------------------------------------------------------------------------------
# Main Test Runner
# ------------------------------------------------------------------------------
main() {
    echo "=============================================================================="
    echo "🧪 Running Debroid install.sh Integration Test Suite"
    echo "   Target Version: ${VERSION}"
    echo "=============================================================================="

    # Pre-suite cleanup
    cleanup_suite
    mkdir -p "$TEST_WORK_DIR"

    # Pre-build Gradle Fat JAR once to speed up tests
    echo "📦 Building Debroid CLI once for test suite..."
    (cd "$REPO_ROOT" && ./gradlew :cli:jar --quiet --console=plain)

    test_bash_installation
    test_zsh_installation
    test_fish_installation
    test_symlinked_dotfiles
    test_preconfigured_shell_config
    test_unsupported_shell_fallback

    echo ""
    echo "=============================================================================="
    echo "📊 Test Summary:"
    echo "   Passed: ${PASSED_COUNT}"
    echo "   Failed: ${FAILED_COUNT}"
    echo "=============================================================================="

    # Post-suite cleanup
    cleanup_suite

    if [ "$FAILED_COUNT" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
