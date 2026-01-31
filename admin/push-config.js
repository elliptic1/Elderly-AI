#!/usr/bin/env node
/**
 * Push device configuration to Firestore
 * 
 * Usage:
 *   node push-config.js <deviceId> --api-key <key>
 *   node push-config.js <deviceId> --schedule <hour>:<minute> [--prompt "..."]
 *   node push-config.js <deviceId> --config config.json
 * 
 * Examples:
 *   # Update API key
 *   node push-config.js abc123 --api-key sk-proj-xyz...
 * 
 *   # Add a schedule
 *   node push-config.js abc123 --schedule 09:00 --prompt "Morning check-in"
 * 
 *   # Push full config from JSON
 *   node push-config.js abc123 --config device-config.json
 */

const admin = require('firebase-admin');
const fs = require('fs');
const path = require('path');

// Initialize Firebase Admin
const serviceAccountPath = process.env.FIREBASE_SERVICE_ACCOUNT || 
  path.join(__dirname, 'service-account.json');

if (!fs.existsSync(serviceAccountPath)) {
  console.error('Error: Firebase service account key not found!');
  console.error(`Expected at: ${serviceAccountPath}`);
  console.error('Set FIREBASE_SERVICE_ACCOUNT env var or place service-account.json in admin/');
  process.exit(1);
}

const serviceAccount = require(serviceAccountPath);

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

const db = admin.firestore();

/**
 * Parse command line arguments
 */
function parseArgs() {
  const args = process.argv.slice(2);
  
  if (args.length < 1) {
    console.error('Error: Device ID required');
    printUsage();
    process.exit(1);
  }

  const deviceId = args[0];
  const config = {
    deviceId,
    updates: {},
  };

  for (let i = 1; i < args.length; i++) {
    const arg = args[i];
    
    switch (arg) {
      case '--api-key':
        config.updates.apiKey = args[++i];
        break;
        
      case '--schedule':
        const time = args[++i];
        const [hour, minute] = time.split(':').map(Number);
        
        if (isNaN(hour) || isNaN(minute)) {
          console.error('Error: Invalid time format. Use HH:MM');
          process.exit(1);
        }
        
        const prompt = args[i + 1] === '--prompt' ? args[i + 2] : 'Daily check-in';
        if (args[i + 1] === '--prompt') i += 2;
        
        config.updates.schedules = config.updates.schedules || [];
        config.updates.schedules.push({
          hour,
          minute,
          enabled: true,
          prompt,
        });
        break;
        
      case '--config':
        const configPath = args[++i];
        if (!fs.existsSync(configPath)) {
          console.error(`Error: Config file not found: ${configPath}`);
          process.exit(1);
        }
        const jsonConfig = JSON.parse(fs.readFileSync(configPath, 'utf8'));
        Object.assign(config.updates, jsonConfig);
        break;
        
      default:
        console.error(`Error: Unknown argument: ${arg}`);
        printUsage();
        process.exit(1);
    }
  }

  if (Object.keys(config.updates).length === 0) {
    console.error('Error: No updates specified');
    printUsage();
    process.exit(1);
  }

  return config;
}

function printUsage() {
  console.log(`
Usage: node push-config.js <deviceId> [options]

Options:
  --api-key <key>              Update OpenAI API key
  --schedule <HH:MM>           Add a check-in schedule
  --prompt <text>              Set prompt for schedule (with --schedule)
  --config <file.json>         Push full config from JSON file

Examples:
  # Update API key
  node push-config.js abc123 --api-key sk-proj-xyz...

  # Add morning check-in
  node push-config.js abc123 --schedule 09:00 --prompt "Good morning check-in"

  # Push full config
  node push-config.js abc123 --config device-config.json

Device Config JSON Format:
{
  "apiKey": "sk-proj-...",
  "schedules": [
    { "hour": 9, "minute": 0, "enabled": true, "prompt": "Morning check-in" },
    { "hour": 18, "minute": 0, "enabled": true, "prompt": "Evening check-in" }
  ],
  "settings": {
    "maxSessionDuration": 180,
    "silenceTimeout": 20
  }
}
  `);
}

/**
 * Push config to Firestore
 */
async function pushConfig(config) {
  try {
    console.log(`Pushing config to device: ${config.deviceId}`);
    console.log('Updates:', JSON.stringify(config.updates, null, 2));

    const docRef = db.collection('devices').doc(config.deviceId)
      .collection('config').doc('current');

    // Get current config to merge
    const currentDoc = await docRef.get();
    const currentData = currentDoc.exists ? currentDoc.data() : {};

    // Merge updates
    const newData = {
      ...currentData,
      ...config.updates,
      version: (currentData.version || 0) + 1,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    // Write to Firestore
    await docRef.set(newData);

    console.log('✓ Config pushed successfully');
    console.log(`Version: ${newData.version}`);
    
    // Also create a history entry
    await db.collection('devices').doc(config.deviceId)
      .collection('config-history').add({
        ...newData,
        pushedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    console.log('✓ Config history saved');
    
  } catch (error) {
    console.error('Error pushing config:', error);
    process.exit(1);
  }
}

/**
 * Get device status
 */
async function getDeviceStatus(deviceId) {
  try {
    const statusDoc = await db.collection('devices').doc(deviceId)
      .collection('status').doc('current').get();
    
    if (statusDoc.exists) {
      const status = statusDoc.data();
      console.log('\nDevice Status:');
      console.log(`  Status: ${status.status}`);
      console.log(`  Message: ${status.message}`);
      console.log(`  Last Update: ${status.timestamp?.toDate()}`);
    } else {
      console.log('\nDevice status not available (device may not have checked in yet)');
    }
  } catch (error) {
    console.error('Error fetching device status:', error);
  }
}

/**
 * Main
 */
async function main() {
  const config = parseArgs();
  
  await pushConfig(config);
  await getDeviceStatus(config.deviceId);
  
  console.log('\nThe device will sync this config within 15 minutes.');
  console.log('Or reboot the device to sync immediately.\n');
  
  process.exit(0);
}

main();
