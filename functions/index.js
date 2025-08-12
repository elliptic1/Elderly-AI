
const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

/**
 * Mints a short-lived claim token for a device.
 * This is a placeholder implementation.
 */
exports.mintClaimToken = functions.https.onCall(async (data, context) => {
  // In a real implementation, we would create a short-lived JWT
  // that embeds the owner's UID.
  const ownerUid = context.auth.uid;
  if (!ownerUid) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "The function must be called while authenticated."
    );
  }

  const claimToken = "test-token"; // Placeholder
  return { claimToken };
});

/**
 * Claims a device and associates it with a user account.
 * This is a placeholder implementation.
 */
exports.claim = functions.https.onRequest(async (req, res) => {
  const { device_id, device_pubkey, claim_token } = req.body;

  if (!device_id || !device_pubkey || !claim_token) {
    res.status(400).send("Missing parameters.");
    return;
  }

  // In a real implementation, we would:
  // 1. Validate the claim token.
  // 2. Extract the owner UID from the token.
  // 3. Create a new device document in Firestore.
  // 4. Return a Firebase custom token for the device.

  if (claim_token === "test-token") {
    const firebase_custom_token = "test-custom-token"; // Placeholder
    res.status(200).send({ firebase_custom_token });
  } else {
    res.status(401).send("Invalid claim token.");
  }
});
