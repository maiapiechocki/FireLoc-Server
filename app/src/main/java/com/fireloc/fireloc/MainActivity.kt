package com.fireloc.fireloc

import android.Manifest
import android.annotation.SuppressLint
// **** ADDED MISSING IMPORT ****
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fireloc.fireloc.camera.BoundingBoxOverlay
import com.fireloc.fireloc.camera.Detection
import com.fireloc.fireloc.camera.DetectionProcessor
import com.fireloc.fireloc.camera.FireDetectionType
import com.fireloc.fireloc.network.ApiClient
import com.fireloc.fireloc.network.ApiService
import com.fireloc.fireloc.network.DeviceRegistrationRequest
import com.fireloc.fireloc.utils.ImageUtils
import com.fireloc.fireloc.util.Prefs // Corrected import based on Prefs.kt
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
// Removed: import kotlinx.coroutines.tasks.await // Not strictly needed if using addOnCompleteListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FireLocMainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        // Model input size (check your actual model)
        private const val INPUT_SIZE = 640
        // Backend URLs
        private const val REGISTER_DEVICE_URL = "https://registerdevice-pppkiwepma-uc.a.run.app"
        private const val DETECT_URL = "https://detect-pppkjwepma-uc.a.run.app" // Keep for potential future use

        // Key for storing registration status in SharedPreferences
        private const val KEY_IS_DEVICE_REGISTERED = "is_device_registered"
    }

    // Views
    private lateinit var viewFinder: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var registerDeviceButton: Button

    // Detection
    private lateinit var detectionProcessor: DetectionProcessor
    private lateinit var cameraExecutor: ExecutorService

    // Coordinates Transformation
    private val imageToViewMatrix = Matrix()
    private var sourceImageWidth: Int = 0
    private var sourceImageHeight: Int = 0
    private var sourceRotationDegrees: Int = 0

    // Firebase & Google Auth
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInLauncher: ActivityResultLauncher<android.content.Intent>

    // Networking
    private lateinit var apiService: ApiService

    // State
    private var isDeviceRegistered: Boolean = false // Local state for registration

    // Permissions Launcher
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionsResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeFirebase()
        initializeGoogleSignIn()
        initializeNetworking()
        loadRegistrationStatus() // Load status from Prefs
        setupButtonClickListeners()

        detectionProcessor = DetectionProcessor(this) { detections, width, height ->
            // Store dimensions from the actual image processed
            sourceImageWidth = width
            sourceImageHeight = height
            // Update UI on main thread
            runOnUiThread { updateDetectionResults(detections) }
        }
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        updateUI(auth.currentUser) // Set initial UI based on auth state
    }

    // --- Initialization Methods ---

    private fun initializeViews() {
        try {
            viewFinder = findViewById(R.id.viewFinder)
            boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay)
            statusText = findViewById(R.id.statusText)
            signInButton = findViewById(R.id.signInButton)
            signOutButton = findViewById(R.id.signOutButton)
            registerDeviceButton = findViewById(R.id.registerDeviceButton)
            statusText.text = getString(R.string.initializing)
        } catch (e: IllegalStateException) { // More specific catch
            Log.e(TAG, "Error finding views. Check IDs in activity_main.xml", e)
            Toast.makeText(this, "Layout Error", Toast.LENGTH_LONG).show()
            finish() // Can't proceed without views
        }
    }

    private fun initializeFirebase() {
        auth = Firebase.auth
    }

    private fun initializeNetworking() {
        apiService = ApiClient.instance
    }

    private fun loadRegistrationStatus() {
        // Load registration status from SharedPreferences
        // Uses Context implicitly provided by the Activity
        val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE) // Needs import for Context
        isDeviceRegistered = prefs.getBoolean(KEY_IS_DEVICE_REGISTERED, false)
        Log.d(TAG, "Loaded registration status: $isDeviceRegistered")
    }

    private fun saveRegistrationStatus(registered: Boolean) {
        // Save registration status to SharedPreferences
        // Uses Context implicitly provided by the Activity
        isDeviceRegistered = registered
        val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE) // Needs import for Context
        prefs.edit().putBoolean(KEY_IS_DEVICE_REGISTERED, registered).apply()
        Log.d(TAG, "Saved registration status: $registered")
    }


    private fun initializeGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        googleSignInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)!!
                    Log.d(TAG, "Google Sign In Successful. Account ID: ${account.id}") // Log Google success
                    val idToken = account.idToken
                    if (idToken != null) {
                        Log.d(TAG, "Google ID Token retrieved, attempting Firebase Auth.")
                        firebaseAuthWithGoogle(idToken)
                    } else {
                        Log.w(TAG, "Google ID Token was null after successful Google Sign In.")
                        statusText.text = getString(R.string.sign_in_failed) + " (No Token)"
                        updateUI(null)
                    }
                } catch (e: ApiException) {
                    // Log the specific API exception status code and message
                    Log.w(TAG, "Google sign in failed: Status Code = ${e.statusCode}, Message = ${e.message}", e)
                    statusText.text = getString(R.string.sign_in_failed) + " (API Exception: ${e.statusCode})"
                    updateUI(null)
                }
            } else {
                Log.w(TAG, "Google sign in activity cancelled or failed. Result code: ${result.resultCode}")
                statusText.text = getString(R.string.sign_in_failed) + " (Result Code: ${result.resultCode})"
                updateUI(null)
            }
        }

    }

    private fun setupButtonClickListeners() {
        signInButton.setOnClickListener { signIn() }
        signOutButton.setOnClickListener { signOut() }
        registerDeviceButton.setOnClickListener { registerDeviceWithBackend() }
    }

    // --- Permissions Handling ---

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        Log.i(TAG, "Requesting camera permissions.")
        permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }


    private fun handlePermissionsResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.entries.all { it.key in REQUIRED_PERMISSIONS && it.value }
        if (allGranted) {
            Log.i(TAG, "Camera permission granted.")
            startCamera()
        } else {
            Log.w(TAG, "Camera permission denied.")
            Toast.makeText(this, "Camera permission is required to run detection.", Toast.LENGTH_LONG).show()
            statusText.text = getString(R.string.permissions_required)
            // Consider finishing the activity or disabling camera features
            finish() // Example: Close app if permission denied
        }
    }

    // --- Authentication Logic ---

    private fun signIn() {
        Log.i(TAG, "Initiating Google Sign-In.")
        statusText.text = getString(R.string.signing_in)
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        Log.i(TAG, "Signing out.")
        statusText.text = "Signing Out..." // Give feedback
        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Google Sign-Out task successful.")
            } else {
                Log.w(TAG, "Google Sign-Out task failed.")
            }
            // Update UI regardless of Google sign-out success, as Firebase sign-out already happened
            updateUI(null)
            Toast.makeText(this, "Signed Out", Toast.LENGTH_SHORT).show()
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        Log.d(TAG, "Calling Firebase auth.signInWithCredential...") // Log before call
        statusText.text = "Authenticating with Firebase..." // Feedback
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                Log.d(TAG, "Firebase signInWithCredential complete. Task Successful: ${task.isSuccessful}") // Log completion status
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    // Log user details right after success
                    Log.d(TAG, "Firebase signInWithCredential:success. User: ${user?.uid}, Email: ${user?.email}")
                    updateUI(user)
                } else {
                    // **** THIS IS THE MOST LIKELY PLACE THE ERROR OCCURS ****
                    // Log the specific Firebase exception
                    Log.w(TAG, "Firebase signInWithCredential:failure", task.exception)
                    statusText.text = getString(R.string.sign_in_failed) + " (Firebase Auth Error)"
                    // Show more specific error to user
                    Toast.makeText(baseContext, "Firebase Authentication Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    updateUI(null)
                }
            }
    }

    private fun updateUI(user: FirebaseUser?) {
        Log.d(TAG, "updateUI called with user: ${user?.uid}") // Log user passed in
        if (user != null) {
            // ... (rest of updateUI remains the same) ...
            statusText.text = getString(R.string.signed_in_fmt, user.email ?: "User")
            signInButton.visibility = View.GONE
            signOutButton.visibility = View.VISIBLE
            registerDeviceButton.visibility = View.VISIBLE
            registerDeviceButton.isEnabled = !isDeviceRegistered
            registerDeviceButton.alpha = if (isDeviceRegistered) 0.5f else 1.0f
        } else {
            // ... (rest of updateUI remains the same) ...
            statusText.text = getString(R.string.signed_out)
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
            registerDeviceButton.visibility = View.GONE
        }
        boundingBoxOverlay.updateDetections(emptyList())
    }


    // --- Device Registration Logic ---

    private fun getIdToken(forceRefresh: Boolean = false, callback: (token: String?) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w(TAG, "Attempted to get ID token, but user is null.")
            callback(null)
            return
        }
        statusText.text = getString(R.string.getting_token)
        currentUser.getIdToken(forceRefresh)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val idToken = task.result?.token
                    if (idToken == null) {
                        Log.w(TAG, "getIdToken task successful but token is null.")
                        statusText.text = getString(R.string.token_error)
                        Toast.makeText(this, "Failed to retrieve auth token.", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Successfully retrieved ID Token.")
                    }
                    callback(idToken)
                } else {
                    Log.e(TAG, "getIdToken: task failed", task.exception)
                    statusText.text = getString(R.string.token_error)
                    Toast.makeText(this, "Error getting authentication token.", Toast.LENGTH_SHORT).show()
                    callback(null)
                }
            }
    }

    private fun registerDeviceWithBackend() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Sign in required.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isDeviceRegistered) {
            Toast.makeText(this, "Device already registered.", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = getString(R.string.generating_device_id)
        val deviceId = Prefs.getDeviceId(this) // Use correct package
        Log.d(TAG, "Device ID for registration: $deviceId")

        statusText.text = "Getting Auth Token..."
        getIdToken(true) { idToken -> // Force refresh token for registration
            if (idToken == null) {
                statusText.text = getString(R.string.token_error) // Show error from getIdToken
                // updateUI(currentUser) - Handled within getIdToken failure path
                return@getIdToken
            }

            statusText.text = getString(R.string.registering_device)
            val requestBody = DeviceRegistrationRequest(deviceId = deviceId, deviceName = null) // name is optional
            val authHeader = "Bearer $idToken"

            lifecycleScope.launch { // Use coroutine for network call
                try {
                    Log.d(TAG, "Calling registerDevice: $REGISTER_DEVICE_URL")
                    val response = apiService.registerDevice(REGISTER_DEVICE_URL, authHeader, requestBody)

                    if (response.isSuccessful) {
                        Log.i(TAG, "Device registration successful (Code: ${response.code()})")
                        saveRegistrationStatus(true) // Save state
                        updateUI(currentUser) // Update button state immediately
                        Toast.makeText(applicationContext, "Device Registered!", Toast.LENGTH_SHORT).show()
                        // Status text updated by updateUI
                    } else {
                        // Log detailed error
                        val errorBody = response.errorBody()?.string() ?: "No response body"
                        Log.e(TAG, "Device registration failed (Code: ${response.code()}): $errorBody")
                        statusText.text = getString(R.string.registration_failed)
                        Toast.makeText(applicationContext, "Registration failed: ${response.message()} (${response.code()})", Toast.LENGTH_LONG).show()
                        // Don't save registration status as true if it failed
                        updateUI(currentUser) // Reset status text / UI state
                    }
                } catch (e: Exception) { // Catch network or other exceptions
                    Log.e(TAG, "Exception during device registration call", e)
                    statusText.text = getString(R.string.registration_error_fmt, e.localizedMessage ?: "Unknown Error")
                    Toast.makeText(applicationContext, "Registration Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateUI(currentUser) // Reset status text / UI state
                }
            }
        }
    }


    // --- CameraX Logic ---

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        Log.d(TAG, "Attempting to start CameraX.")
        // Update status based on auth state
        statusText.text = if (auth.currentUser != null) getString(R.string.initializing) else getString(R.string.signed_out)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // --- Preview Use Case ---
                val preview = Preview.Builder()
                    .setTargetResolution(Size(1280, 720)) // Define target resolution
                    // .setTargetAspectRatio(AspectRatio.RATIO_16_9) // Or Aspect Ratio
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                // --- Image Analysis Use Case ---
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720)) // Match preview if performance allows
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy -> // Use lambda shorthand
                            // Store rotation for coordinate mapping
                            sourceRotationDegrees = imageProxy.imageInfo.rotationDegrees
                            // Process the image (DetectionProcessor handles closing the proxy)
                            detectionProcessor.processImageProxy(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind before rebinding
                cameraProvider.unbindAll()

                // Bind use cases
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                Log.i(TAG, "CameraX bound successfully.")
                // Update status only if user is logged in
                if (auth.currentUser != null) {
                    statusText.text = getString(R.string.scanning_status)
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                statusText.text = getString(R.string.camera_error)
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this)) // Run on main thread
    }

    // --- Detection Result UI Update ---

    private fun updateDetectionResults(detections: List<Detection>) {
        // Do not process/display if user is not signed in
        if (auth.currentUser == null) {
            boundingBoxOverlay.updateDetections(emptyList())
            return
        }

        // Ensure view/image dimensions are valid before calculating transform
        if (boundingBoxOverlay.width == 0 || boundingBoxOverlay.height == 0 || sourceImageWidth == 0 || sourceImageHeight == 0) {
            return // Avoid division by zero or incorrect mapping
        }

        // Calculate the matrix to transform model coords -> view coords
        imageToViewMatrix.set(
            calculateTransformMatrix(
                sourceImageWidth, sourceImageHeight,
                boundingBoxOverlay.width, boundingBoxOverlay.height,
                sourceRotationDegrees
            )
        )

        // Transform detection boxes for display
        val viewDetections = detections.mapNotNull { detection ->
            val normalizedBox = detection.boundingBox // Normalized (0.0-1.0) relative to model input
            val viewRect = RectF()

            // Map from model input pixel space (0-INPUT_SIZE) to view space
            val modelInputRect = RectF(
                normalizedBox.left * INPUT_SIZE, normalizedBox.top * INPUT_SIZE,
                normalizedBox.right * INPUT_SIZE, normalizedBox.bottom * INPUT_SIZE
            )
            imageToViewMatrix.mapRect(viewRect, modelInputRect)

            // Clamp the final view coordinates to the overlay bounds
            viewRect.left = max(0f, viewRect.left)
            viewRect.top = max(0f, viewRect.top)
            viewRect.right = min(boundingBoxOverlay.width.toFloat(), viewRect.right)
            viewRect.bottom = min(boundingBoxOverlay.height.toFloat(), viewRect.bottom)

            // Create new Detection with View Coordinates, skip if rect is invalid
            if (viewRect.width() > 0 && viewRect.height() > 0) {
                Detection(detection.type, detection.confidence, viewRect)
            } else {
                Log.w(TAG, "Skipping detection with invalid viewRect: $viewRect from norm: $normalizedBox")
                null
            }
        }

        // Update the overlay
        boundingBoxOverlay.updateDetections(viewDetections)

        // Update status text
        if (detections.isNotEmpty()) { // Use original detections list to check if *any* were found
            val fireCount = detections.count { it.type == FireDetectionType.FIRE }
            val smokeCount = detections.count { it.type == FireDetectionType.SMOKE }
            // Find the highest confidence among *all* valid detections before filtering
            val maxConfidence = detections.maxOfOrNull { it.confidence } ?: 0f
            statusText.text = getString(R.string.detection_status_format, fireCount, smokeCount, (maxConfidence * 100).toInt())
        } else {
            val procTime = detectionProcessor.getLastProcessingTimeMs()
            statusText.text = "${getString(R.string.scanning_status)} (${procTime}ms)"
        }
    }

    /**
     * Calculates matrix to transform coordinates from the model's input space (INPUT_SIZE)
     * to the destination View's coordinate space, accounting for image rotation and scaling.
     */
    private fun calculateTransformMatrix(
        srcWidth: Int, srcHeight: Int, // Image buffer dimensions
        dstWidth: Int, dstHeight: Int, // View dimensions
        rotation: Int                  // Buffer rotation (0, 90, 180, 270)
    ): Matrix {
        val transformMatrix = Matrix()

        // Get dimensions of the source image if it were upright
        val (uprightSrcWidth, uprightSrcHeight) = if (rotation == 90 || rotation == 270) {
            srcHeight.toFloat() to srcWidth.toFloat()
        } else {
            srcWidth.toFloat() to srcHeight.toFloat()
        }

        // Calculate the scale needed to fit the upright source into the destination view
        // while maintaining aspect ratio (letterbox/pillarbox - min scale factor)
        val scaleX = dstWidth.toFloat() / uprightSrcWidth
        val scaleY = dstHeight.toFloat() / uprightSrcHeight
        val scale = min(scaleX, scaleY)

        // Calculate the translation to center the scaled image within the destination view
        val scaledImageWidth = uprightSrcWidth * scale
        val scaledImageHeight = uprightSrcHeight * scale
        val dx = (dstWidth - scaledImageWidth) / 2f
        val dy = (dstHeight - scaledImageHeight) / 2f

        // Combine transformations:
        // Map Model Input (0-INPUT_SIZE) -> Scaled Dest -> Centered Dest
        val finalMatrix = Matrix()
        // Correct: Scale FROM model input size TO the size it occupies in the destination view
        finalMatrix.postScale(scale * uprightSrcWidth / INPUT_SIZE, scale * uprightSrcHeight / INPUT_SIZE)
        finalMatrix.postTranslate(dx, dy)

        // This matrix now maps directly from model input pixel coordinates (0 to INPUT_SIZE)
        // to the correctly scaled and centered view coordinates.
        return finalMatrix
    }


    // --- Lifecycle ---

    override fun onStart() {
        super.onStart()
        // Check initial auth state when activity starts/resumes
        val currentUser = auth.currentUser
        Log.d(TAG, "onStart called. Current Firebase user: ${currentUser?.uid}")
        // Note: Don't call updateUI(currentUser) here directly if it causes race conditions.
        // Rely on the auth callbacks primarily. If needed, use a flag or check timestamps.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down the background executor
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
            Log.d(TAG,"Camera executor shutdown.")
        }
        // Release the detection processor (closes the model)
        if (::detectionProcessor.isInitialized) {
            detectionProcessor.release() // Handles model.close()
        }
        Log.d(TAG, "MainActivity onDestroy completed.")
    }


} // End of MainActivity class