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
INSTALL_DIR="/usr/local/bin"
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
exec java --enable-native-access=ALL-UNNAMED --add-exports=jdk.jdi/com.sun.tools.example.debug.expr=ALL-UNNAMED -jar "$0" "$@"
EOF

    # Concatenate launcher stub + Fat JAR into a single self-contained executable
    cat stub.sh "$JAR_FILE" > "$BINARY_NAME"
    chmod +x "$BINARY_NAME"
    rm stub.sh
fi

# ==============================================================================
# Execution Step 2: Install Binary into System PATH
# ==============================================================================

echo "🚀 Installing '${BINARY_NAME}' into ${INSTALL_DIR}..."

# Check if target installation directory is writable without root privileges
if [ -w "$INSTALL_DIR" ]; then
    mv "$BINARY_NAME" "${INSTALL_DIR}/${BINARY_NAME}"
else
    echo "🔑 Requesting sudo privileges to move binary into ${INSTALL_DIR}..."
    sudo mv "$BINARY_NAME" "${INSTALL_DIR}/${BINARY_NAME}"
fi

echo "=============================================================================="
echo "🎉 Debroid installed successfully!"
echo "   Location: ${INSTALL_DIR}/${BINARY_NAME}"
echo "   Test it:  debroid --help"
echo "=============================================================================="
