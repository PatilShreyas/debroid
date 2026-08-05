#!/usr/bin/env bash
set -e

REMOTE_MODE=false

for arg in "$@"; do
    case $arg in
        --remote|-r|--download)
            REMOTE_MODE=true
            shift
            ;;
    esac
done

if [ "$REMOTE_MODE" = true ]; then
    echo "⬇️  Downloading pre-built Debroid binary from GitHub Releases..."
    RELEASE_URL="https://github.com/PatilShreyas/debroid/releases/latest/download/debroid"
    
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o debroid "$RELEASE_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O debroid "$RELEASE_URL"
    else
        echo "❌ Error: Neither curl nor wget is installed."
        exit 1
    fi
    
    chmod +x debroid
else
    VERSION=$(cat version.txt | xargs)
    echo "🔨 Building Debroid CLI v$VERSION..."
    if ./gradlew :cli:jar --quiet --console=plain; then
        echo "✅ CLI build successful!"
    else
        echo "❌ CLI build failed!"
        exit 1
    fi

    JAR_FILE="cli/build/libs/debroid-$VERSION.jar"

    if [ ! -f "$JAR_FILE" ]; then
        echo "❌ Build failed: JAR not found at $JAR_FILE"
        exit 1
    fi

    echo "📦 Creating standalone executable..."

    # Create the bash stub that launches the JAR
    cat << 'EOF' > stub.sh
#!/usr/bin/env bash
exec java --enable-native-access=ALL-UNNAMED --add-exports=jdk.jdi/com.sun.tools.example.debug.expr=ALL-UNNAMED -jar "$0" "$@"
EOF

    # Concatenate the stub and the JAR to create a single executable
    cat stub.sh "$JAR_FILE" > debroid
    chmod +x debroid
    rm stub.sh
fi

echo "🚀 Installing 'debroid' to /usr/local/bin..."
if [ -w /usr/local/bin ]; then
    mv debroid /usr/local/bin/debroid
else
    echo "🔑 Requesting sudo permission to move binary to /usr/local/bin..."
    sudo mv debroid /usr/local/bin/debroid
fi

echo "✅ Debroid installed successfully!"
echo "You can now use the 'debroid' command from anywhere."
echo "Try running: debroid --help"
