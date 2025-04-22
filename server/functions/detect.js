// detect.js
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { Buffer } = require("buffer");
const ort = require("onnxruntime-node");
const sharp = require("sharp");
const path = require("path");
const os = require("os");
const fs = require("fs");
const { Storage } = require("@google-cloud/storage");
const { firetriLocalize } = require("./fireTri"); // Assuming this returns {latitude: number, longitude: number} or null

// --- Constants ---
const CONFIDENCE_THRESHOLD = 0.85; // Example threshold
const MODEL_FILE_NAME = "cloud_best.onnx"; // Ensure this matches the deployed model
const TEMP_MODEL_PATH = path.join(os.tmpdir(), MODEL_FILE_NAME);
const BUCKET_NAME = "fireloc-e68b0.appspot.com"; // Your Firebase Storage bucket

// --- Global State for ONNX Session Caching ---
let sessionPromise = null;

// --- Helper Functions ---

// Lazily loads the ONNX model from GCS and creates the session
async function loadModelOnce() {
    // Return cached promise if already loading/loaded
    if (sessionPromise) return sessionPromise;

    // Create the promise that will be cached
    sessionPromise = (async () => {
        try {
            const storage = new Storage();
            const file = storage.bucket(BUCKET_NAME).file(`models/${MODEL_FILE_NAME}`);

            // Check if model exists locally in /tmp, download if not
            if (!fs.existsSync(TEMP_MODEL_PATH)) {
                console.log(`[ONNX] Model not found locally. Downloading from gs://${BUCKET_NAME}/models/${MODEL_FILE_NAME} to ${TEMP_MODEL_PATH}`);
                await file.download({ destination: TEMP_MODEL_PATH });
                console.log("[ONNX] Model downloaded successfully.");
            } else {
                console.log("[ONNX] Model already exists locally. Skipping download.");
            }

            console.log("[ONNX] Initializing ONNX InferenceSession...");
            const session = await ort.InferenceSession.create(TEMP_MODEL_PATH);
            console.log("[ONNX] InferenceSession initialized successfully.");
            return session;
        } catch (err) {
            console.error("[ONNX] Critical error loading ONNX model:", err);
            // Reset promise on failure so next invocation tries again
            sessionPromise = null;
            throw new Error("Failed to load ONNX model."); // Rethrow to fail the function call
        }
    })(); // Immediately invoke the async IIFE

    return sessionPromise;
}

// --- Main Cloud Function Handler ---
// Correctly exported as the module's default export
module.exports = async (req, res) => {
    // 1. Basic Request Validation
    if (req.method !== "POST") {
        return res.status(405).send({ error: "Method Not Allowed" });
    }

    // 2. App Check Verification
    const appCheckToken = req.headers["x-firebase-appcheck"];
    if (!appCheckToken) {
        console.warn("Missing App Check token.");
        return res.status(401).send({ error: "Unauthorized: Missing App Check token." }); // Use 401
    }
    try {
        // Verify token, allowing replay is often fine for non-critical checks but consider your security needs
        await admin.appCheck().verifyToken(appCheckToken);
    } catch (err) {
        console.error("Invalid App Check token:", err);
        return res.status(401).send({ error: "Unauthorized: Invalid App Check token." }); // Use 401
    }

    // 3. User Auth Verification
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        return res.status(401).send({ error: "Unauthorized: Missing or invalid Authorization header." });
    }
    const idToken = authHeader.split("Bearer ")[1];
    let decodedToken;
    try {
        decodedToken = await admin.auth().verifyIdToken(idToken);
    } catch (err) {
        console.error("Invalid Firebase ID token:", err);
         if (err.code === 'auth/id-token-expired') {
             return res.status(401).send({ error: "Unauthorized: Firebase ID token has expired." });
         }
        return res.status(401).send({ error: "Unauthorized: Invalid Firebase ID token." });
    }
    const uid = decodedToken.uid; // Get user ID

    // 4. Parse Request Body
    const { deviceId, image_base64, timestamp_ms, location, mobile_detected } = req.body;
    if (!deviceId || !image_base64 || !timestamp_ms || !location || typeof location.latitude !== 'number' || typeof location.longitude !== 'number') {
         console.error("Missing or invalid fields in request body:", req.body);
        return res.status(400).send({ error: "Bad Request: Missing or invalid required fields (deviceId, image_base64, timestamp_ms, location object)." });
    }

    // 5. Timestamp Validation
    const requestTimestamp = new Date(Number(timestamp_ms));
    if (isNaN(requestTimestamp.getTime())) {
        return res.status(400).send({ error: "Bad Request: Invalid timestamp format." });
    }

    // 6. Image Processing
    let imageBuffer;
    try {
        imageBuffer = Buffer.from(image_base64, "base64");
    } catch (err) {
        console.error("Error decoding base64 image:", err);
        return res.status(400).send({ error: "Bad Request: Invalid base64 image data." });
    }

    let tensor;
    try {
        // Preprocess: Resize, ensure 3 channels (remove alpha), normalize, create tensor
        const { data, info } = await sharp(imageBuffer)
            .removeAlpha() // Ensure 3 channels (RGB)
            .resize(640, 640, { fit: 'fill' }) // Ensure exact 640x640
            .raw() // Get raw pixel data
            .toBuffer({ resolveWithObject: true });

        if (info.channels !== 3) {
             throw new Error(`Expected 3 channels after processing, but got ${info.channels}`);
        }

        // Normalize pixel values to [0, 1]
        const floatArray = new Float32Array(data.length);
        for (let i = 0; i < data.length; i++) {
            floatArray[i] = data[i] / 255.0;
        }

        // Create ONNX tensor [batch_size, channels, height, width]
        tensor = new ort.Tensor("float32", floatArray, [1, info.channels, info.height, info.width]);

    } catch (err) {
        console.error("Image preprocessing failed:", err);
        return res.status(500).send({ error: "Internal Server Error: Failed to preprocess image." });
    }

    // 7. ONNX Inference
    let outputData;
    try {
        console.log("Loading/Retrieving ONNX session...");
        const session = await loadModelOnce(); // Use the cached session loader
        console.log("Preparing feeds for ONNX model...");
        const feeds = { [session.inputNames[0]]: tensor }; // Dynamically use the input name
        console.log("Running ONNX inference...");
        const results = await session.run(feeds);
        outputData = results[session.outputNames[0]].data; // Get the output data
        console.log("ONNX inference completed.");
    } catch (err) {
        console.error("ONNX inference run failed:", err);
        return res.status(500).send({ error: "Internal Server Error: Failed to run AI model inference." });
    }

    // 8. Process Detections
    let fireDetected = false;
    const cloudDetections = []; // Renamed to avoid clash with module name
    const outputStride = 6; // Assuming model output format [x1, y1, x2, y2, conf, classId]

    // Check if outputData is valid and has expected structure
     if (!outputData || !outputData.length || outputData.length % outputStride !== 0) {
        console.warn(`Unexpected ONNX output format or length. Length: ${outputData?.length}, Stride: ${outputStride}`);
        // Proceed without detections, or return an error depending on requirements
     } else {
        for (let i = 0; i < outputData.length; i += outputStride) {
            // Ensure indices are within bounds (though check above should cover this)
            if (i + outputStride > outputData.length) break;

            const [x1, y1, x2, y2, confidence, classId] = outputData.slice(i, i + outputStride);

            if (confidence >= CONFIDENCE_THRESHOLD) {
                // Assuming classId 0 or 1 might be fire/smoke, adjust as needed
                // You might want specific class IDs for 'fire'
                 // if (classId === FIRE_CLASS_ID) {
                      fireDetected = true;
                 // }

                cloudDetections.push({
                    class_id: Math.round(classId), // Assuming classId is a float
                    confidence: confidence,
                    // Ensure coordinates are within [0, 1] range, clamp if necessary
                    box_normalized: [
                         Math.max(0, Math.min(1, x1)),
                         Math.max(0, Math.min(1, y1)),
                         Math.max(0, Math.min(1, x2)),
                         Math.max(0, Math.min(1, y2)),
                     ]
                });
            }
        }
     }
     console.log(`Detection processing complete. Fire detected: ${fireDetected}. Detections found: ${cloudDetections.length}`);


    // 9. Firestore Update
    const db = admin.firestore();
    const batch = db.batch(); // Use a batch for atomic writes
    const deviceRef = db.collection("devices").doc(deviceId);

    try {
        if (fireDetected && cloudDetections.length > 0) {
            console.log(`Fire detected for device ${deviceId}. Performing localization and logging detection.`);
            const detectionId = `${deviceId}_${Date.now()}`; // Unique ID for the detection event
            const detectionRef = db.collection("detections").doc(detectionId);

            // Attempt localization (ensure firetriLocalize handles errors)
            let firePositionGeoPoint = null;
             try {
                  // Assuming first detection is most relevant for localization, adjust if needed
                 const localizedPosition = await firetriLocalize(location, cloudDetections[0].box_normalized);
                 if (localizedPosition && typeof localizedPosition.latitude === 'number' && typeof localizedPosition.longitude === 'number') {
                     firePositionGeoPoint = new admin.firestore.GeoPoint(localizedPosition.latitude, localizedPosition.longitude);
                 } else {
                     console.warn("Localization failed or returned invalid data for", deviceId);
                 }
             } catch (locErr) {
                 console.error("Error during firetriLocalize:", locErr);
                 // Continue without firePosition if localization fails
             }


            // Add detection record to batch
            batch.set(detectionRef, {
                deviceId: deviceId,
                userId: uid,
                timestamp: admin.firestore.Timestamp.fromDate(requestTimestamp),
                location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                results: cloudDetections, // Store all detections found by cloud
                firePosition: firePositionGeoPoint, // Store GeoPoint or null
                mobile_detected: mobile_detected || false // Store boolean from request
            });

            // Add device status update to batch
            batch.set(deviceRef, {
                lastSeen: admin.firestore.FieldValue.serverTimestamp(), // Update last seen time
                location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                status: "confirmed_fire" // More specific status
            }, { merge: true }); // Merge to preserve other fields like userId, deviceName

        } else {
            console.log(`No fire detected above threshold for device ${deviceId}. Updating device status.`);
            // --- Optional: Update device even if no fire detected ---
             batch.set(deviceRef, {
                 lastSeen: admin.firestore.FieldValue.serverTimestamp(),
                 location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
                 status: "scanning" // Or keep previous status if preferred
             }, { merge: true });
        }

        // Commit all writes in the batch
        await batch.commit();
        console.log(`Firestore updates committed successfully for device ${deviceId}.`);

    } catch (err) {
        console.error(`Firestore update failed for device ${deviceId}:`, err);
        // Don't block response to client for DB errors if possible, but log it critically
        // Consider returning 500 if DB write is critical for function success
        return res.status(500).send({ error: "Internal Server Error: Failed to update database." });
    }


    // 10. Send Response
    return res.status(200).json({
        status: "processed",
        detected: fireDetected, // Boolean indicating if fire was detected by cloud model
        results: cloudDetections // Array of detections found by cloud model
    });
}; // End of module.exports