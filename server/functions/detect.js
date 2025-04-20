const admin = require("firebase-admin");
const { AppCheckTokenVerifier } = require("firebase-admin/app-check");
const { Buffer } = require("buffer");

// Placeholder for ONNX inference — you can require and load ONNX here
// const ort = require("onnxruntime-node");

module.exports = async function detect(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method Not Allowed" });
    }

    // Step 1: Verify App Check token
    const appCheckToken = req.headers["x-firebase-appcheck"];
    if (!appCheckToken) {
        return res.status(403).json({ error: "Missing App Check token" });
    }

    try {
        await admin.appCheck().verifyToken(appCheckToken);
    } catch (err) {
        return res.status(403).json({ error: "Invalid App Check token" });
    }

    // Step 2: Parse and validate request body
    const {
        deviceId,
        image_base64,
        timestamp_ms,
        location,
        mobile_detected
    } = req.body;

    if (!deviceId || !image_base64 || !timestamp_ms || !location) {
        return res.status(400).json({ error: "Missing required fields" });
    }

    const timestamp = new Date(Number(timestamp_ms));
    if (!(timestamp)) {
        return res.status(400).json({ error: "Invalid timestamp" });
    }

    let imageBuffer;
    try {
        imageBuffer = Buffer.from(image_base64, "base64");
    } catch (e) {
        return res.status(400).json({ error: "Invalid base64 image" });
    }

    // Step 3: Run ONNX inference (placeholder)
    const detected = true; // assume fire detected (mock)
    const results = [
        {
            class_id: 1,
            confidence: 0.87,
            box_normalized: [0.3, 0.4, 0.6, 0.8]
        }
    ];

    // Step 4: Write to Firestore if fire/smoke is detected
    if (detected) {
        const detectionId = `${deviceId}_${Date.now()}`;

        await admin.firestore().collection("detections").doc(detectionId).set({
            deviceId,
            timestamp: admin.firestore.Timestamp.fromDate(timestamp),
            location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
            type: results[0].class_id === 1 ? "fire" : "smoke",
            confidence: results[0].confidence,
            boundingBox: results[0].box_normalized,
            mobile_detected
        });

        await admin.firestore().collection("devices").doc(deviceId).set({
            lastSeen: admin.firestore.Timestamp.now(),
            location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
            status: "active"
        }, { merge: true });
    }

    return res.status(200).json({
        status: "processed",
        detected,
        results: detected ? results : []
    });
};