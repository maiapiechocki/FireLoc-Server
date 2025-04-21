const detect = require("./detect");

// Fake Express-style request & response objects for testing:
(async () => {
    const req = {
        method: "POST",
        headers: {
            authorization: "Bearer FAKE_TOKEN",
            "x-firebase-appcheck": "FAKE_APPCHECK"
        },
        body: {
            deviceId: "test_device",
            image_base64: "<base64_image_string>",
            timestamp_ms: Date.now().toString(),
            location: { latitude: 37.7749, longitude: -122.4194 },
            mobile_detected: true
        }
    };

    const res = {
        status: (code) => {
            console.log("Response code:", code);
            return {
                json: (data) => {
                    console.log("Response data:", JSON.stringify(data, null, 2));
                }
            };
        }
    };

    await detect(req, res);
})();