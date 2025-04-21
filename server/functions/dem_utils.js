// const fs = require("fs");
// const path = require("path");
// const axios = require("axios");
// const gdal = require("gdal-async");
//
// const OPENTOPO_API_KEY = "3eaa7c1448abaa9b36469ab544430be7";
//
// // ----------------------
// // Download DEM GeoTIFF
// // ----------------------
// async function fetchDemData(west, south, east, north, demType = "SRTMGL1", outputFile = "elevation_data.tif") {
//     const apiUrl = "https://portal.opentopography.org/API/globaldem";
//
//     if (west >= east || south >= north) {
//         console.error("Invalid bounding box: west must be < east, and south < north.");
//         return false;
//     }
//
//     const params = {
//         demtype: demType,
//         west,
//         south,
//         east,
//         north,
//         outputFormat: "GTiff",
//         API_Key: OPENTOPO_API_KEY
//     };
//
//     try {
//         const response = await axios.get(apiUrl, {
//             params,
//             responseType: "arraybuffer",
//             timeout: 30000
//         });
//
//         if (response.status === 200) {
//             fs.writeFileSync(outputFile, Buffer.from(response.data));
//             console.log(`DEM file downloaded successfully: ${outputFile}`);
//             return true;
//         } else {
//             console.error("DEM download failed:", response.status, response.data);
//             return false;
//         }
//     } catch (err) {
//         console.error("Request failed:", err.message);
//         return false;
//     }
// }
//
// // ----------------------
// // Load GeoTIFF as GDAL dataset
// // ----------------------
// function loadDemAsDataset(filePath) {
//     try {
//         const dataset = gdal.open(filePath);
//         return dataset;
//     } catch (err) {
//         console.error("Could not open DEM file:", err.message);
//         return null;
//     }
// }
//
// // ----------------------
// // Convert lat/lon → raster pixel (x, y)
// // ----------------------
// function gpsToRaster(lat, lon, dataset) {
//     const geoTransform = dataset.geoTransform;
//     const minLon = geoTransform[0];
//     const maxLat = geoTransform[3];
//     const resX = geoTransform[1];
//     const resY = Math.abs(geoTransform[5]);
//
//     const x = Math.floor((lon - minLon) / resX);
//     const y = Math.floor((maxLat - lat) / resY);
//
//     return { x, y };
// }
//
// // ----------------------
// // Get elevation from dataset
// // ----------------------
// function getElevation(lat, lon, dataset) {
//     const band = dataset.bands.get(1);
//     const { x, y } = gpsToRaster(lat, lon, dataset);
//     const elevation = band.pixels.get(x, y);
//     return elevation;
// }
//
// module.exports = {
//     fetchDemData,
//     loadDemAsDataset,
//     getElevation
// };

// dem_utils.js

// Stubbed fallback version for Firebase Cloud Functions (Gen 2)
function getElevation(lat, lon, dataset) {
    console.warn("GDAL is not supported in Cloud Functions. Returning placeholder elevation.");
    return 0; // or a fixed safe default
}

function fetchDemData() {
    console.warn("fetchDemData() is not available in deployed environment.");
    return false;
}

function loadDemAsDataset() {
    console.warn("loadDemAsDataset() is not available in deployed environment.");
    return null;
}

module.exports = {
    fetchDemData,
    loadDemAsDataset,
    getElevation
};