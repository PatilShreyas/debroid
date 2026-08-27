#!/usr/bin/env bash
# ==============================================================================
# Debroid CLI Installer
# ==============================================================================
# This script installs the 'debroid' CLI utility onto the user's system.
#
# Intelligence / Mode Selection:
# ------------------------------
# 1. LOCAL BUILD MODE:
#    If the script is executed inside a cloned 'debroid' repository (detected by
#    the presence of './gradlew' and 'cli/build.gradle.kts'), it will compile
#    the Fat JAR from local source using Gradle and construct the binary stub.
#
# 2. REMOTE DOWNLOAD MODE:
#    If executed outside a repo (e.g. via 'curl -fsSL .../install.sh | bash'),
#    it automatically detects the absence of local build files and downloads
#    the latest pre-built 'debroid' binary directly from GitHub Releases.
#
# 3. EXPLICIT FLAGS:
#    Pass '--remote' or '-r' to force downloading the release binary.
#    Pass '--local' or '-l' to force compiling from local source.
# ==============================================================================

set -e

# Repository configuration
REPO_OWNER="PatilShreyas"
REPO_NAME="debroid"
BINARY_NAME="debroid"
INSTALL_DIR="${HOME}/.local/bin"
RELEASE_URL="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/latest/download/${BINARY_NAME}"

# Parse explicit command-line flags
FORCE_MODE=""
for arg in "$@"; do
    case $arg in
        --remote|-r|--download)
            FORCE_MODE="remote"
            shift
            ;;
        --local|-l|--build)
            FORCE_MODE="local"
            shift
            ;;
    esac
done

# Determine installation mode intelligently
INSTALL_MODE=""

if [ "$FORCE_MODE" = "remote" ]; then
    INSTALL_MODE="remote"
elif [ "$FORCE_MODE" = "local" ]; then
    INSTALL_MODE="local"
else
    # Auto-detection: Check if running inside the Debroid source tree
    if [ -f "./gradlew" ] && [ -f "cli/build.gradle.kts" ] && [ -f "version.txt" ]; then
        echo "🔍 Local Debroid repository detected. Selecting LOCAL BUILD mode."
        INSTALL_MODE="local"
    else
        echo "🌐 No local repository found. Selecting REMOTE DOWNLOAD mode."
        INSTALL_MODE="remote"
    fi
fi

# ==============================================================================
# Execution Step 1: Obtain Binary (Build or Download)
# ==============================================================================

if [ "$INSTALL_MODE" = "remote" ]; then
    echo "⬇️  Downloading pre-built Debroid binary from GitHub Releases..."
    echo "    URL: $RELEASE_URL"

    # Download binary using curl or wget depending on environment availability
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$BINARY_NAME" "$RELEASE_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$BINARY_NAME" "$RELEASE_URL"
    else
        echo "❌ Error: Neither 'curl' nor 'wget' was found in your PATH."
        echo "   Please install one of them to proceed with the remote installation."
        exit 1
    fi

    chmod +x "$BINARY_NAME"
    echo "✅ Download complete."

else
    # Local build mode
    if [ ! -f "./gradlew" ]; then
        echo "❌ Error: Local build requested, but './gradlew' was not found in the current directory."
        echo "   Please run this script from the root of the Debroid project repository."
        exit 1
    fi

    VERSION=$(cat version.txt | xargs)
    echo "🔨 Building Debroid CLI v$VERSION from local source..."

    if ./gradlew :cli:jar --quiet --console=plain; then
        echo "✅ CLI build successful!"
    else
        echo "❌ Error: Gradle CLI build failed!"
        exit 1
    fi

    JAR_FILE="cli/build/libs/debroid-$VERSION.jar"
    if [ ! -f "$JAR_FILE" ]; then
        echo "❌ Error: Compiled JAR file was not found at $JAR_FILE"
        exit 1
    fi

    echo "📦 Packaging standalone executable binary..."

    # Create the bash launcher stub that executes the Fat JAR with JDI module flags
    cat << 'EOF' > stub.sh
#!/usr/bin/env bash
USER_HOME="${HOME:-$(eval echo ~)}"
exec java --enable-native-access=ALL-UNNAMED --add-exports=jdk.jdi/com.sun.tools.example.debug.expr=ALL-UNNAMED -Duser.home="$USER_HOME" -jar "$0" "$@"
EOF

    # Concatenate launcher stub + Fat JAR into a single self-contained executable
    cat stub.sh "$JAR_FILE" > "$BINARY_NAME"
    chmod +x "$BINARY_NAME"
    rm stub.sh
fi

# ==============================================================================
# Execution Step 2: Install Binary into User Local Directory
# ==============================================================================

mkdir -p "$INSTALL_DIR"
echo "🚀 Installing '${BINARY_NAME}' into ${INSTALL_DIR}..."
mv "$BINARY_NAME" "${INSTALL_DIR}/${BINARY_NAME}"

# ==============================================================================
# Execution Step 3: Initialize State & AI Skills
# ==============================================================================

echo "🚀 Initializing Debroid state and extracting AI skills..."
"${INSTALL_DIR}/${BINARY_NAME}" --help >/dev/null 2>&1 || true

# ==============================================================================
# Helper Functions: Shell Configuration Automation
# ==============================================================================

path_has_dir() {
    case ":$PATH:" in *":$1:"*) return 0 ;; *) return 1 ;; esac
}

configure_shell_path() {
    local install_dir="$1"

    if path_has_dir "$install_dir"; then
        echo "✅ '$install_dir' is already in your PATH."
        return 0
    fi

    local user_shell
    user_shell="$(basename "${SHELL:-}")"
    local config_file=""

    case "$user_shell" in
        bash) config_file="$HOME/.bashrc" ;;
        zsh)  config_file="$HOME/.zshrc" ;;
        fish) config_file="$HOME/.config/fish/config.fish" ;;
    esac

    if [ -z "$config_file" ]; then
        echo "⚠️  Could not automatically detect a supported shell profile (detected: '${user_shell:-unknown}')."
        echo "   Please add '$install_dir' to your PATH manually:"
        echo "   export PATH=\"$install_dir:\$PATH\""
        return 0
    fi

    mkdir -p "$(dirname "$config_file")"

    # Resolve symlinks so tmp+mv rewrites the stow/dotfiles target, not the link itself.
    if [ -e "$config_file" ] || [ -L "$config_file" ]; then
        local _cf="$config_file"
        local _depth=0
        while [ -L "$_cf" ] && [ "$_depth" -lt 40 ]; do
            local _link
            _link="$(readlink "$_cf")" || break
            case "$_link" in
                /*) _cf="$_link" ;;
                *)  _cf="$(cd "$(dirname "$_cf")" && pwd -P)/$_link" ;;
            esac
            _depth=$((_depth + 1))
        done
        if [ ! -L "$_cf" ]; then
            config_file="$(cd "$(dirname "$_cf")" && pwd -P)/$(basename "$_cf")"
        fi
        unset _cf _link _depth
    fi

    # Build the new installer block
    local new_block
    if [ "$user_shell" = "fish" ]; then
        new_block="# >>> debroid installer >>>
fish_add_path $install_dir
# <<< debroid installer <<<"
    else
        new_block="# >>> debroid installer >>>
export PATH=\"$install_dir:\$PATH\"
# <<< debroid installer <<<"
    fi

    if grep -qs "debroid installer" "$config_file" 2>/dev/null; then
        # Replace existing block in-place
        local tmp="$config_file.tmp.$$"
        awk '
            /# >>> debroid installer >>>/ { skip=1; next }
            /# <<< debroid installer <<</ { skip=0; next }
            !skip { print }
        ' "$config_file" > "$tmp" && mv "$tmp" "$config_file"
    elif grep -qs "\.local/bin" "$config_file" 2>/dev/null; then
        echo "✅ '$install_dir' is already configured in $config_file."
        return 0
    else
        [ -f "$config_file" ] && cp "$config_file" "$config_file.bak.$(date +%s)"

        # macOS bash: ensure bash_profile sources bashrc
        if [ "$user_shell" = "bash" ] && [ "$(uname -s)" = "Darwin" ]; then
            if [ -f "$HOME/.bash_profile" ] && ! grep -qs "source ~/.bashrc" "$HOME/.bash_profile"; then
                printf '\n[[ -r ~/.bashrc ]] && source ~/.bashrc\n' >> "$HOME/.bash_profile"
            fi
        fi
    fi

    printf '\n%s\n' "$new_block" >> "$config_file"
    echo "✅ Automatically added '$install_dir' to PATH in $config_file"
    echo "   (Restart your shell or run 'source $config_file' to apply changes in this window)"
}

# ==============================================================================
# Execution Step 4: Configure Shell PATH & Verify Java Runtime Environment
# ==============================================================================

echo ""
echo "🔍 Checking Java runtime..."
if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    JAVA_VERSION_STR=$(java -version 2>&1 | head -n 1)
    echo "✅ Java runtime detected: $JAVA_VERSION_STR"
else
    echo "⚠️  Warning: Java runtime not found or unconfigured in PATH!"
    echo "   Debroid requires Java 11 or higher (JDK 11 / 17 / 21) to debug Android apps."
    echo "   Please install OpenJDK or set JAVA_HOME in your shell profile."
fi

echo ""
echo "⚙️  Configuring shell PATH..."
configure_shell_path "$INSTALL_DIR"

echo "=============================================================================="
echo "🎉 Debroid installed successfully!"
echo "   Binary Location: ${INSTALL_DIR}/${BINARY_NAME}"
echo "   AI Skills:       ${HOME}/.debroid/skills/debroid-cli/SKILL.md"
if path_has_dir "$INSTALL_DIR"; then
    echo "   Test it:         debroid --help"
else
    echo "   Test it:         debroid --help (after restarting terminal or sourcing config)"
fi
echo "=============================================================================="
