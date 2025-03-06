// alertSystem.js - Handles user notifications and fire map updates
const fs = require('fs');
const path = require('path');

function notifyUsers(fireLocation) {
    console.log(`Notifying users about fire at: ${fireLocation.latitude}, ${fireLocation.longitude}`);
    
    // this is just a placeholder
    console.log('Alert: Fire detected in your area!');
}

function updateFireMap(fireData) {
    console.log('Updating fire map with new detection');
    
    const fireMapPath = path.join(__dirname, 'data', 'currentFires.json');
    let currentFires = [];
    
    try {
        if (fs.existsSync(fireMapPath)) {
            const data = fs.readFileSync(fireMapPath, 'utf8');
            currentFires = JSON.parse(data);
        }
    } catch (error) {
        console.error('Error reading current fires data:', error);
    }
    
    currentFires.push({
        id: Date.now().toString(),
        latitude: fireData.location.latitude,
        longitude: fireData.location.longitude,
        detectedBy: fireData.detectedBy,
        confidence: fireData.confidence,
        timestamp: fireData.timestamp,
        imagePath: fireData.imagePath
    });
    
    try {
        fs.writeFileSync(fireMapPath, JSON.stringify(currentFires, null, 2));
    } catch (error) {
        console.error('Error writing fire data:', error);
    }
}

module.exports = {
    notifyUsers,
    updateFireMap
};
