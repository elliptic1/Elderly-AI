#!/usr/bin/env node
/**
 * Push OTA update to Firestore
 * 
 * This makes the update available to all ElderCare devices.
 * Devices check for updates every 6 hours and auto-apply.
 * 
 * Usage:
 *   node push-ota.js --metadata ota-metadata.json --url https://...
 * 
 * Or upload to Firebase Storage:
 *   node push-ota.js --file eldercare-ota-v1.0.1.zip --upload
 * 
 * Examples:
 *   # Push with external URL (S3, CDN, etc)
 *   node push-ota.js \
 *     --metadata ../aosp/ota-packages/eldercare-ota-v1.0.1.json \
 *     --url https://cdn.example.com/eldercare-ota-v1.0.1.zip
 * 
 *   # Upload to Firebase Storage and push
 *   node push-ota.js \
 *     --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip \
 *     --upload
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
  process.exit(1);
}

const serviceAccount = require(serviceAccountPath);

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  storageBucket: `${serviceAccount.project_id}.appspot.com`,
});

const db = admin.firestore();
const bucket = admin.storage().bucket();

/**
 * Parse command line arguments
 */
function parseArgs() {
  const args = process.argv.slice(2);
  const config = {
    metadata: null,
    packageUrl: null,
    packageFile: null,
    upload: false,
    releaseNotes: null,
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    
    switch (arg) {
      case '--metadata':
        config.metadata = args[++i];
        break;
        
      case '--url':
        config.packageUrl = args[++i];
        break;
        
      case '--file':
        config.packageFile = args[++i];
        break;
        
      case '--upload':
        config.upload = true;
        break;
        
      case '--notes':
        config.releaseNotes = args[++i];
        break;
        
      default:
        console.error(`Error: Unknown argument: ${arg}`);
        printUsage();
        process.exit(1);
    }
  }

  // Validate
  if (!config.metadata && !config.packageFile) {
    console.error('Error: Either --metadata or --file required');
    printUsage();
    process.exit(1);
  }

  if (config.metadata && !fs.existsSync(config.metadata)) {
    console.error(`Error: Metadata file not found: ${config.metadata}`);
    process.exit(1);
  }

  if (config.packageFile && !fs.existsSync(config.packageFile)) {
    console.error(`Error: Package file not found: ${config.packageFile}`);
    process.exit(1);
  }

  if (config.upload && !config.packageFile) {
    console.error('Error: --upload requires --file');
    process.exit(1);
  }

  return config;
}

function printUsage() {
  console.log(`
Usage: node push-ota.js [options]

Options:
  --metadata <file.json>     Path to OTA metadata JSON
  --url <url>                Public download URL for OTA package
  --file <package.zip>       Path to OTA package file
  --upload                   Upload package to Firebase Storage
  --notes <text>             Release notes (optional)

Examples:
  # Push with external hosting
  node push-ota.js \\
    --metadata ../aosp/ota-packages/eldercare-ota-v1.0.1.json \\
    --url https://cdn.example.com/eldercare-ota-v1.0.1.zip

  # Upload to Firebase Storage
  node push-ota.js \\
    --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip \\
    --upload \\
    --notes "Bug fixes and performance improvements"
  `);
}

/**
 * Upload OTA package to Firebase Storage
 */
async function uploadPackage(packageFile) {
  const fileName = path.basename(packageFile);
  const destination = `ota-updates/${fileName}`;
  
  console.log('📤 Uploading package to Firebase Storage...');
  console.log(`   ${fileName} → gs://${bucket.name}/${destination}`);
  
  const fileSize = fs.statSync(packageFile).size;
  console.log(`   Size: ${(fileSize / 1024 / 1024).toFixed(2)} MB`);
  
  await bucket.upload(packageFile, {
    destination: destination,
    metadata: {
      contentType: 'application/zip',
      cacheControl: 'public, max-age=31536000', // 1 year
    },
  });
  
  // Make file publicly readable
  const file = bucket.file(destination);
  await file.makePublic();
  
  // Get public URL
  const publicUrl = `https://storage.googleapis.com/${bucket.name}/${destination}`;
  
  console.log('✓ Upload complete');
  console.log(`   URL: ${publicUrl}`);
  
  return publicUrl;
}

/**
 * Generate metadata from package file
 */
async function generateMetadata(packageFile, packageUrl) {
  const crypto = require('crypto');
  
  console.log('🔍 Analyzing package...');
  
  // Calculate SHA-256
  const hash = crypto.createHash('sha256');
  const fileStream = fs.createReadStream(packageFile);
  
  for await (const chunk of fileStream) {
    hash.update(chunk);
  }
  
  const sha256 = hash.digest('hex');
  const stats = fs.statSync(packageFile);
  
  // Extract version from filename (e.g., eldercare-ota-v1.0.1.zip)
  const match = path.basename(packageFile).match(/eldercare-ota-(.+)\.zip/);
  const version = match ? match[1] : 'unknown';
  const buildId = `eldercare-${version}`;
  
  return {
    buildId: buildId,
    version: version,
    buildFingerprint: `google/panther/panther:15/AP2A.240805.005/${buildId}:userdebug/dev-keys`,
    packageUrl: packageUrl,
    packageFile: path.basename(packageFile),
    packageSize: stats.size,
    sha256: sha256,
    buildDate: new Date().toISOString(),
    releaseNotes: `ElderCare system update ${version}`,
  };
}

/**
 * Push update info to Firestore
 */
async function pushUpdate(metadata) {
  try {
    console.log('📝 Pushing update to Firestore...');
    console.log('');
    console.log('Update Info:');
    console.log(`  Build ID: ${metadata.buildId}`);
    console.log(`  Version: ${metadata.version}`);
    console.log(`  Size: ${(metadata.packageSize / 1024 / 1024).toFixed(2)} MB`);
    console.log(`  SHA-256: ${metadata.sha256}`);
    console.log(`  URL: ${metadata.packageUrl}`);
    console.log(`  Notes: ${metadata.releaseNotes}`);
    console.log('');

    // Write to updates/latest
    const docRef = db.collection('updates').doc('latest');
    await docRef.set(metadata);

    console.log('✓ Update published to Firestore');
    
    // Also save to update history
    await db.collection('updates').doc('history').collection('versions')
      .doc(metadata.buildId).set({
        ...metadata,
        publishedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    console.log('✓ Update saved to history');
    
  } catch (error) {
    console.error('Error pushing update:', error);
    process.exit(1);
  }
}

/**
 * Get current update status
 */
async function getCurrentUpdate() {
  try {
    const doc = await db.collection('updates').doc('latest').get();
    
    if (doc.exists) {
      const current = doc.data();
      console.log('\nCurrent Published Update:');
      console.log(`  Build ID: ${current.buildId}`);
      console.log(`  Version: ${current.version}`);
      console.log(`  Published: ${current.buildDate}`);
    } else {
      console.log('\nNo update currently published');
    }
  } catch (error) {
    console.error('Error fetching current update:', error);
  }
}

/**
 * Main
 */
async function main() {
  const config = parseArgs();
  
  let metadata;
  let packageUrl = config.packageUrl;

  // Upload package if requested
  if (config.upload) {
    packageUrl = await uploadPackage(config.packageFile);
  }

  // Load or generate metadata
  if (config.metadata) {
    console.log('📋 Loading metadata from file...');
    metadata = JSON.parse(fs.readFileSync(config.metadata, 'utf8'));
    
    // Override URL if provided
    if (packageUrl) {
      metadata.packageUrl = packageUrl;
    }
  } else {
    // Generate metadata from package
    if (!packageUrl) {
      console.error('Error: Package URL required when metadata not provided');
      console.error('Use --url or --upload');
      process.exit(1);
    }
    
    metadata = await generateMetadata(config.packageFile, packageUrl);
  }

  // Override release notes if provided
  if (config.releaseNotes) {
    metadata.releaseNotes = config.releaseNotes;
  }

  // Verify required fields
  if (!metadata.packageUrl) {
    console.error('Error: Package URL not specified');
    console.error('Use --url to provide download URL');
    process.exit(1);
  }

  // Push update
  await pushUpdate(metadata);
  
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('✅ OTA Update Published!');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('');
  console.log('Devices will check for updates every 6 hours.');
  console.log('Updates will download and apply automatically.');
  console.log('Devices will reboot to complete the update.');
  console.log('');
  console.log('Monitor device logs:');
  console.log('  adb logcat -s OTAUpdateService:I');
  console.log('');
  
  process.exit(0);
}

main();
