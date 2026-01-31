#!/bin/bash
# ElderCare Admin Setup Script

set -e

echo "🔧 ElderCare Admin Tools Setup"
echo "=============================="
echo ""

# Check if npm is installed
if ! command -v npm &> /dev/null; then
    echo "❌ Error: npm not found"
    echo "Please install Node.js and npm first"
    exit 1
fi

# Install dependencies
echo "📦 Installing dependencies..."
npm install

# Check for service account key
if [ ! -f "service-account.json" ]; then
    echo ""
    echo "⚠️  Firebase service account key not found"
    echo ""
    echo "To set up:"
    echo "1. Go to Firebase Console → Project Settings → Service Accounts"
    echo "2. Click 'Generate New Private Key'"
    echo "3. Save the file as: admin/service-account.json"
    echo ""
    echo "Or set environment variable:"
    echo "  export FIREBASE_SERVICE_ACCOUNT=/path/to/service-account.json"
    echo ""
fi

# Check for device ID
echo ""
echo "📱 Getting device ID..."
echo ""

if command -v adb &> /dev/null; then
    if adb devices | grep -q "device$"; then
        DEVICE_ID=$(adb shell settings get secure android_id 2>/dev/null | tr -d '\r')
        if [ -n "$DEVICE_ID" ]; then
            echo "✓ Device connected: $DEVICE_ID"
            echo ""
            echo "To push config to this device:"
            echo "  node push-config.js $DEVICE_ID --api-key YOUR_KEY"
            echo ""
        else
            echo "⚠️  Could not get device ID (check ADB permissions)"
        fi
    else
        echo "⚠️  No device connected via ADB"
        echo ""
        echo "Connect device and run:"
        echo "  adb shell settings get secure android_id"
        echo ""
    fi
else
    echo "⚠️  ADB not found"
    echo ""
    echo "To get device ID manually:"
    echo "1. Connect device via ADB"
    echo "2. Run: adb shell settings get secure android_id"
    echo ""
fi

echo "✅ Setup complete!"
echo ""
echo "Next steps:"
echo "1. Get Firebase service account key (if not done)"
echo "2. Get device ID (if not connected)"
echo "3. Push your first config:"
echo "   node push-config.js <deviceId> --api-key sk-proj-..."
echo ""
echo "See README.md for full documentation"
echo ""
