#!/bin/bash
# Build OTA update package for ElderCare
#
# This script generates an OTA package that can be deployed to devices
# for remote system updates.
#
# Usage:
#   ./build-ota.sh [version]
#
# Example:
#   ./build-ota.sh v1.0.1

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AOSP_ROOT="${AOSP_ROOT:-$HOME/aosp-panther}"
PRODUCT_NAME="eldercare_panther"

# Version from argument or timestamp
VERSION="${1:-$(date +%Y%m%d-%H%M%S)}"
BUILD_ID="eldercare-$VERSION"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔨 Building ElderCare OTA Package"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Version: $VERSION"
echo "Build ID: $BUILD_ID"
echo "Product: $PRODUCT_NAME"
echo "AOSP Root: $AOSP_ROOT"
echo ""

# Check AOSP directory
if [ ! -d "$AOSP_ROOT" ]; then
    echo "❌ Error: AOSP source not found at $AOSP_ROOT"
    echo "Set AOSP_ROOT environment variable or update this script"
    exit 1
fi

cd "$AOSP_ROOT"

# Set up build environment
echo "📦 Setting up build environment..."
source build/envsetup.sh
lunch "${PRODUCT_NAME}-userdebug"

# Set custom build ID
export BUILD_ID="$BUILD_ID"

echo ""
echo "🏗️  Building target files..."
echo "This will take 30-60 minutes on first build..."
echo ""

# Build target files package
# This creates the basis for the OTA package
m target-files-package -j$(nproc)

TARGET_FILES="$AOSP_ROOT/out/target/product/panther/obj/PACKAGING/target_files_intermediates/${PRODUCT_NAME}-target_files-${BUILD_ID}.zip"

if [ ! -f "$TARGET_FILES" ]; then
    echo "❌ Error: Target files not found at $TARGET_FILES"
    exit 1
fi

echo ""
echo "✓ Target files built: $TARGET_FILES"
echo ""
echo "📦 Generating OTA package..."
echo ""

# Output directory
OTA_DIR="$SCRIPT_DIR/ota-packages"
mkdir -p "$OTA_DIR"

OTA_PACKAGE="$OTA_DIR/eldercare-ota-$VERSION.zip"

# Generate OTA package
# For incremental updates, you'd use --incremental_from with previous target files
ota_from_target_files \
    --verbose \
    --block \
    --key "$AOSP_ROOT/build/target/product/security/testkey" \
    "$TARGET_FILES" \
    "$OTA_PACKAGE"

if [ ! -f "$OTA_PACKAGE" ]; then
    echo "❌ Error: OTA package generation failed"
    exit 1
fi

echo ""
echo "✓ OTA package generated!"
echo ""

# Calculate SHA-256 checksum
echo "🔐 Calculating checksum..."
SHA256=$(sha256sum "$OTA_PACKAGE" | awk '{print $1}')
SIZE=$(stat -f %z "$OTA_PACKAGE" 2>/dev/null || stat -c %s "$OTA_PACKAGE")

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Build Complete!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Package: $OTA_PACKAGE"
echo "Size: $(numfmt --to=iec-i --suffix=B $SIZE 2>/dev/null || echo "$SIZE bytes")"
echo "SHA-256: $SHA256"
echo ""

# Generate metadata file
METADATA_FILE="$OTA_DIR/eldercare-ota-$VERSION.json"

cat > "$METADATA_FILE" << EOF
{
  "buildId": "$BUILD_ID",
  "version": "$VERSION",
  "buildFingerprint": "google/panther/panther:15/AP2A.240805.005/$BUILD_ID:userdebug/dev-keys",
  "packageFile": "$(basename "$OTA_PACKAGE")",
  "packageSize": $SIZE,
  "sha256": "$SHA256",
  "buildDate": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "releaseNotes": "ElderCare system update $VERSION"
}
EOF

echo "Metadata: $METADATA_FILE"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Next steps:"
echo "1. Upload OTA package to hosting (Firebase Storage, S3, etc.)"
echo "2. Get public download URL"
echo "3. Push update to devices:"
echo "   cd ../admin"
echo "   node push-ota.js --metadata $METADATA_FILE --url <download-url>"
echo ""
echo "Or test on connected device:"
echo "   adb sideload $OTA_PACKAGE"
echo ""
