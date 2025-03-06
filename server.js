// firelocserver.js - Main server file for FireLoc cloud service

const express = require('express');
const http = require('http'); 
const socketIo = require('socket.io'); 
const multer = require('multer'); 
const fs = require('fs');
const path = require('path'); 
const cors = require('cors'); 
const { processImage, detectFire } = require('./fireDetection');
const { geolocateFireFromImage } = require('./fireGeolocation');
const { notifyUsers, updateFireMap } = require('./alertSystem');

const app = express();
const server = http.createServer(app);
const io = socketIo(server);

app.use(cors());
app.use(express.json());
app.use(express.static('public'));

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

// mem storage for real-time streaming
const memStorage = multer.memoryStorage();
const memUpload = multer({ storage: memStorage });

app.post('/api/upload', upload.single('image'), async (req, res) => {
    try {
        console.log('Upload request received from device:', req.body.deviceId);
        
        const metadata = {
            deviceId: req.body.deviceId,
            latitude: parseFloat(req.body.latitude),
            longitude: parseFloat(req.body.longitude),
            orientation: req.body.orientation ? JSON.parse(req.body.orientation) : null,
            timestamp: Date.now()
        };
        
        const metadataPath = path.join(path.dirname(req.file.path), 'metadata.json');
        fs.writeFileSync(metadataPath, JSON.stringify(metadata));
        
        let fireDetected = req.body.fireDetected === '1';
        let fireConfidence = 0;
        let fireBoundingBox = null;
        
        if (!fireDetected) {
            const detectionResult = await detectFire(req.file.path);
            fireDetected = detectionResult.detected;
            fireConfidence = detectionResult.confidence;
            fireBoundingBox = detectionResult.boundingBox;
            
            console.log('Server-side fire detection result:', 
                        fireDetected ? 'FIRE DETECTED' : 'No fire detected',
                        'Confidence:', fireConfidence);
        }
        
        // if fire detected perform geolocation
        let fireLocation = null;
        if (fireDetected && fireConfidence > 0.5) {  // threshold for confidence
            fireLocation = await geolocateFireFromImage(
                req.file.path,
                metadata.latitude,
                metadata.longitude,
                metadata.orientation,
                fireBoundingBox
            );
            
            if (fireLocation) {
                updateFireMap({
                    location: fireLocation,
                    detectedBy: metadata.deviceId,
                    confidence: fireConfidence,
                    timestamp: metadata.timestamp,
                    imagePath: req.file.path
                });
                
                notifyUsers(fireLocation);
                
                io.emit('fireAlert', {
                    location: fireLocation,
                    confidence: fireConfidence,
                    timestamp: metadata.timestamp,
                    detectedBy: metadata.deviceId
                });
            }
        }
        
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

app.post('/api/stream', memUpload.single('image'), (req, res) => {
    try {
        console.log('Stream request received from device:', req.body.deviceId);
        
        const imageBuffer = req.file.buffer;
        const encodedImage = imageBuffer.toString('base64');
        
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

app.get('/api/fires', (req, res) => {
    try {
        // get latest fire data from our storage
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

io.on('connection', (socket) => {
    console.log('New client connected');
    
    socket.on('disconnect', () => {
        console.log('Client disconnected');
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`FireLoc server running on port ${PORT}`);
});

module.exports = {
    app,
    io,
    server
};
