const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

const registerDevice = require("./registerDevice");

exports.registerDevice = functions.https.onRequest(registerDevice);

const detect = require("./detect");

exports.detect = functions.https.onRequest(detect);