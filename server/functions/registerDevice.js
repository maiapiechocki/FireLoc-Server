const functions = require("firebase-functions");
const admin = require("firebase-admin");

async function verifyFirebaseIdToken(req) {
    const authHeader = req.headers.authorization;

    if (!authHeader || !authHeader.startsWith("Bearer ")) {
        throw new Error("Missing or invalid Authorization header");
    }

    const idToken = authHeader.split("Bearer ")[1];

    try {
        const decodedToken = await admin.auth().verifyIdToken(idToken);
        return decodedToken; // contains uid, email, etc.
    } catch (err) {
        throw new Error("Invalid or expired Firebase ID token");
    }
}

exports.registerDevice = functions.https.onRequest(async (req, res) => {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method Not Allowed" });
    }

    let decodedUser;
    try {
        decodedUser = await verifyFirebaseIdToken(req);
    } catch (err) {
        return res.status(401).json({ error: err.message });
    }

    const uid = decodedUser.uid;
    const { deviceId, deviceName } = req.body;

    if (!deviceId) {
        return res.status(400).json({ error: "Missing deviceId" });
    }

    try {
        const db = admin.firestore();

        // (1) Write to devices/{deviceId}
        await db.collection("devices").doc(deviceId).set({
            userId: uid,
            deviceName: deviceName || "Unnamed device",
            status: "registered",
            lastSeen: admin.firestore.FieldValue.serverTimestamp(),
            location: null
        }, { merge: true });

        // (2) Link device in users/{uid}
        await db.collection("users").doc(uid).set({
            registeredDevices: admin.firestore.FieldValue.arrayUnion(deviceId)
        }, { merge: true });

        return res.status(200).json({
            status: "success",
            message: "Device registered successfully."
        });

    } catch (err) {
        console.error("Error registering device:", err);
        return res.status(500).json({ error: "Server error" });
    }
});