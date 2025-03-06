// firelocserver.js - Main server file for FireLoc cloud service

const express = require('express'); // framework for web servers
const http = require('http'); // creates actual server 
const socketIo = require('socket.io'); // web apps to server real time communication 
const multer = require('multer'); // middleware for file uploads in nodejs
const fs = require('fs'); // filesys --. read/write manipulate files/dir
const path = require('path'); 
const cors = require('cors'); //security implemented by browser; prevents malicious websites from accessing server while allowing access from web to api requests
const { processImage, detectFire } = require('./fireDetection');
const { geolocateFireFromImage } = require('./fireGeolocation');
const { notifyUsers, updateFireMap } = require('./alertSystem');

// Initialize express app
const app = express();
const server = http.createServer(app);
const io = socketIo(server);

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static('public'));

// Storage configuration for uploaded images
const storage = multer.diskStorage({
    destination: function (req, file, cb) {
        const id = req.body.deviceId;
        const fireDetected = req.body.fireDetected;
        console.log('Received an image from device:', id);
        
        let dirPath;
        if (id) {
            dirPath = path.join(__dirname, 'uploads', id);
            
            if (fireDetected !== undefined) {
                dirPath = path.join(dirPath, fireDetected === '1' ? 'fire' : 'no_fire');
            }
        } else {
            dirPath = path.join(__dirname, 'uploads', 'unknown');
        }
        
        console.log('Image stored at:', dirPath);
        
        if (!fs.existsSync(dirPath)) {
            fs.mkdirSync(dirPath, { recursive: true });
        }
        
        cb(null, dirPath);
    },
    filename: function (req, file, cb) {
        // Use timestamp as filename to ensure uniqueness
        const name = Date.now() + path.extname(file.originalname);
        console.log('File name:', name);
        cb(null, name);
    }
});

const upload = multer({ storage: storage });

// Memory storage for real-time streaming
const memStorage = multer.memoryStorage();
const memUpload = multer({ storage: memStorage });

// Routes
app.post('/api/upload', upload.single('image'), async (req, res) => {
    try {
        console.log('Upload request received from device:', req.body.deviceId);
        
        // Extract metadata
        const metadata = {
            deviceId: req.body.deviceId,
            latitude: parseFloat(req.body.latitude),
            longitude: parseFloat(req.body.longitude),
            orientation: req.body.orientation ? JSON.parse(req.body.orientation) : null,
            timestamp: Date.now()
        };
        
        // Save metadata
        const metadataPath = path.join(path.dirname(req.file.path), 'metadata.json');
        fs.writeFileSync(metadataPath, JSON.stringify(metadata));
        
        // Process the image for fire detection if not already detected on device
        let fireDetected = req.body.fireDetected === '1';
        let fireConfidence = 0;
        let fireBoundingBox = null;
        
        if (!fireDetected) {
            // Run server-side fire detection
            const detectionResult = await detectFire(req.file.path);
            fireDetected = detectionResult.detected;
            fireConfidence = detectionResult.confidence;
            fireBoundingBox = detectionResult.boundingBox;
            
            console.log('Server-side fire detection result:', 
                        fireDetected ? 'FIRE DETECTED' : 'No fire detected',
                        'Confidence:', fireConfidence);
        }
        
        // If fire is detected, perform geolocation
        let fireLocation = null;
        if (fireDetected && fireConfidence > 0.5) {  // Use threshold for confidence
            fireLocation = await geolocateFireFromImage(
                req.file.path,
                metadata.latitude,
                metadata.longitude,
                metadata.orientation,
                fireBoundingBox
            );
            
            // Update fire map and notify users
            if (fireLocation) {
                updateFireMap({
                    location: fireLocation,
                    detectedBy: metadata.deviceId,
                    confidence: fireConfidence,
                    timestamp: metadata.timestamp,
                    imagePath: req.file.path
                });
                
                // Notify users in the area
                notifyUsers(fireLocation);
                
                // Broadcast to all connected clients via Socket.IO
                io.emit('fireAlert', {
                    location: fireLocation,
                    confidence: fireConfidence,
                    timestamp: metadata.timestamp,
                    detectedBy: metadata.deviceId
                });
            }
        }
        
        // Send response back to the device
        res.json({
            success: true,
            fireDetected: fireDetected,
            confidence: fireConfidence,
            location: fireLocation
        });
        
    } catch (error) {
        console.error('Error processing uploaded image:', error);
        res.status(500).json({ 
            success: false, 
            error: 'Error processing image' 
        });
    }
});

// Route for real-time streaming
app.post('/api/stream', memUpload.single('image'), (req, res) => {
    try {
        console.log('Stream request received from device:', req.body.deviceId);
        
        // Process streamed image
        const imageBuffer = req.file.buffer;
        const encodedImage = imageBuffer.toString('base64');
        
        // Broadcast the image to connected clients
        io.emit('streamImage', {
            deviceId: req.body.deviceId,
            image: encodedImage,
            timestamp: Date.now()
        });
        
        res.json({ success: true, message: 'Image streamed successfully' });
    } catch (error) {
        console.error('Error streaming image:', error);
        res.status(500).json({ 
            success: false, 
            error: 'Error streaming image' 
        });
    }
});

// Route to get current fire data
app.get('/api/fires', (req, res) => {
    try {
        // Get the latest fire data from our storage
        // This would typically come from a database or cache
        const fireData = require('./data/currentFires.json');
        res.json(fireData);
    } catch (error) {
        console.error('Error fetching fire data:', error);
        res.status(500).json({ 
            success: false, 
            error: 'Error fetching fire data' 
        });
    }
});

// Socket.IO connection handler
io.on('connection', (socket) => {
    console.log('New client connected');
    
    socket.on('disconnect', () => {
        console.log('Client disconnected');
    });
});

// Start the server
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`FireLoc server running on port ${PORT}`);
});

module.exports = {
    app,
    io,
    server
};