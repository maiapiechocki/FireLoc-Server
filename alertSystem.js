// alertSystem.js - Handles user notifications and fire map updates
const fs = require('fs');
const path = require('path');

// Notify users in the vicinity of a detected fire
function notifyUsers(fireLocation) {
    console.log(`Notifying users about fire at: ${fireLocation.latitude}, ${fireLocation.longitude}`);
    
    // In a real system, this would send push notifications, SMS, etc.
    // This is just a placeholder
    console.log('Alert: Fire detected in your area!');
}

// Update the fire map with a new detection
function updateFireMap(fireData) {
    console.log('Updating fire map with new detection');
    
    // Read existing fire data
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
    
    // Add new fire data
    currentFires.push({
        id: Date.now().toString(),
        latitude: fireData.location.latitude,
        longitude: fireData.location.longitude,
        detectedBy: fireData.detectedBy,
        confidence: fireData.confidence,
        timestamp: fireData.timestamp,
        imagePath: fireData.imagePath
    });
    
    // Write updated data back to file
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