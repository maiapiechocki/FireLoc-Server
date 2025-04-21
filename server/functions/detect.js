const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { Buffer } = require("buffer");
const ort = require("onnxruntime-node");
const sharp = require("sharp");
const path = require("path");
const os = require("os");
const fs = require("fs");
const { Storage } = require("@google-cloud/storage");
const { firetriLocalize } = require("./fireTri");

const CONFIDENCE_THRESHOLD = 0.85;
const MODEL_FILE_NAME = "cloud_best.onnx";
const TEMP_MODEL_PATH = path.join(os.tmpdir(), MODEL_FILE_NAME);
const BUCKET_NAME = "fireloc-e68b0.appspot.com";

let sessionPromise;

async function loadModelOnce() {
    if (sessionPromise) return sessionPromise;

    try {
        const storage = new Storage();
        const file = storage.bucket(BUCKET_NAME).file(`models/${MODEL_FILE_NAME}`);

        if (!fs.existsSync(TEMP_MODEL_PATH)) {
            console.log(`[ONNX] Downloading model from storage bucket: ${BUCKET_NAME}/models/${MODEL_FILE_NAME}`);
            await file.download({ destination: TEMP_MODEL_PATH });
            console.log("[ONNX] Model successfully downloaded to local tmp directory.");
        } else {
            console.log("[ONNX] Model already exists locally. Skipping download.");
        }

        console.log("[ONNX] Initializing inference session...");
        sessionPromise = ort.InferenceSession.create(TEMP_MODEL_PATH);
        return sessionPromise;
    } catch (err) {
        console.error("[ONNX] Model loading failed:", err);
        throw err;
    }
}

module.exports = async function detect(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method Not Allowed" });
    }

    const appCheckToken = req.headers["x-firebase-appcheck"];
    if (!appCheckToken) return res.status(403).json({ error: "Missing App Check token" });

    try {
        await admin.appCheck().verifyToken(appCheckToken);
    } catch {
        return res.status(403).json({ error: "Invalid App Check token" });
    }

    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        return res.status(401).json({ error: "Missing Authorization header" });
    }

    const idToken = authHeader.split("Bearer ")[1];
    let decodedToken;
    try {
        decodedToken = await admin.auth().verifyIdToken(idToken);
    } catch (err) {
        return res.status(401).json({ error: "Invalid or expired ID token" });
    }

    const { deviceId, image_base64, timestamp_ms, location, mobile_detected } = req.body;
    if (!deviceId || !image_base64 || !timestamp_ms || !location) {
        return res.status(400).json({ error: "Missing required fields" });
    }

    const timestamp = new Date(Number(timestamp_ms));
    if (isNaN(timestamp.getTime())) {
        return res.status(400).json({ error: "Invalid timestamp" });
    }

    let imageBuffer;
    try {
        imageBuffer = Buffer.from(image_base64, "base64");
    } catch {
        return res.status(400).json({ error: "Invalid base64 image" });
    }

    let floatArray;
    try {
        const { data } = await sharp(imageBuffer)
            .resize(640, 640)
            .removeAlpha()
            .raw()
            .toBuffer({ resolveWithObject: true });

        floatArray = Float32Array.from(data).map(v => v / 255.0);
    } catch (err) {
        console.error("Image preprocessing failed:", err);
        return res.status(500).json({ error: "Failed to preprocess image" });
    }

    const tensor = new ort.Tensor("float32", floatArray, [1, 3, 640, 640]);

    let output;
    try {
        const session = await loadModelOnce();
        const feeds = {};
        feeds[session.inputNames[0]] = tensor;
        const results = await session.run(feeds);
        output = results[session.outputNames[0]].data;
    } catch (err) {
        console.error("ONNX inference error:", err);
        return res.status(500).json({ error: "Failed to run inference" });
    }

    let fireDetected = false;
    let detections = [];

    for (let i = 0; i < output.length; i += 6) {
        const [x1, y1, x2, y2, confidence, classId] = output.slice(i, i + 6);
        if (confidence >= CONFIDENCE_THRESHOLD) {
            fireDetected = true;
            detections.push({
                class_id: Math.round(classId),
                confidence,
                box_normalized: [x1, y1, x2, y2]
            });
        }
    }

    const db = admin.firestore();
    const detectionId = `${deviceId}_${Date.now()}`;

    if (fireDetected) {
        const firePosition = await firetriLocalize(location, detections[0].box_normalized);

        await db.collection("detections").doc(detectionId).set({
            deviceId,
            timestamp: admin.firestore.Timestamp.fromDate(timestamp),
            location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
            results: detections,
            firePosition,
            mobile_detected,
            userId: decodedToken.uid
        });

        await db.collection("devices").doc(deviceId).set({
            lastSeen: admin.firestore.Timestamp.now(),
            location: new admin.firestore.GeoPoint(location.latitude, location.longitude),
            status: "confirmed"
        }, { merge: true });
    }

    return res.status(200).json({
        status: "processed",
        detected: fireDetected,
        results: detections
    });
};