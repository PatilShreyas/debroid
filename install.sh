#!/usr/bin/env bash
set -e

VERSION=$(cat cli/version.txt | xargs)
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

echo "🚀 Moving 'debroid' to /usr/local/bin (may require sudo password)..."
sudo mv debroid /usr/local/bin/debroid

echo "✅ Debroid installed successfully!"
echo "You can now use the 'debroid' command from anywhere."
echo "Try running: debroid --help"
