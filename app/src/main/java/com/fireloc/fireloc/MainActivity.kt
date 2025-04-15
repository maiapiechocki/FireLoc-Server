package com.fireloc.fireloc // Ensure this matches your package name exactly

// ** Crucial: Ensure this import matches your package name **
import com.fireloc.fireloc.R

// Other required imports (Alphabetical order helps)
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.fireloc.fireloc.network.DeviceRegistrationRequest // Import Network data class
import com.fireloc.fireloc.network.RetrofitClient // Import Retrofit client
import com.google.android.gms.auth.api.signin.GoogleSignIn
// import com.google.android.gms.auth.api.signin.GoogleSignInAccount // Not directly used after getting token
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers // Import Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext // Import withContext
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION") // Suppress for deprecated GoogleSignIn classes used throughout
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FireLocMainActivity"
        // TODO: Add ACCESS_FINE_LOCATION when implementing location retrieval
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val INPUT_SIZE = 640
        private const val PREFS_NAME = "FireLocPrefs"
        private const val KEY_DEVICE_ID = "uniqueDeviceId"
        // Key for storing registration status, including device ID to handle multiple devices per user if needed later
        private fun getRegistrationPrefKey(deviceId: String) = "deviceRegistered_$deviceId"
    }

    // Views
    private lateinit var viewFinder: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var statusText: TextView
    private lateinit var signInButton: SignInButton
    private lateinit var signOutButton: Button
    private lateinit var registerDeviceButton: Button

    // Detection
    private lateinit var detectionProcessor: DetectionProcessor
    private lateinit var cameraExecutor: ExecutorService

    // Transformation (Local variable used instead)
    private var sourceImageWidth: Int = 0
    private var sourceImageHeight: Int = 0
    private var sourceRotationDegrees: Int = 0

    // Firebase Auth & Google Sign In
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    // SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private var uniqueDeviceId: String? = null

    // Permissions Launcher
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check if ALL required permissions were granted
        val allGranted = REQUIRED_PERMISSIONS.all { permission ->
            permissions[permission] ?: false
        }

        if (!allGranted) {
            // Explain why permissions are needed (Camera is essential)
            // TODO: Handle location permission explanation later if added
            Toast.makeText(this, R.string.camera_permission_denied_toast, Toast.LENGTH_LONG).show()
            statusText.text = getString(R.string.camera_permission_required)
            // Maybe finish() the activity if camera is absolutely required?
        } else {
            // Permissions granted, proceed with camera setup
            startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // This references R

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        uniqueDeviceId = retrieveOrGenerateDeviceId() // ** Call renamed method **

        auth = Firebase.auth

        initializeViews()
        configureGoogleSignIn()
        initializeSignInLauncher()
        initializeDetectionProcessor()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupButtonClickListeners()

        // Check permissions right away. If not granted, request them.
        // If granted, startCamera() will be called by the launcher callback or directly here.
        if (!allPermissionsGranted()) {
            requestPermissions()
        } else {
            startCamera() // Start camera if permissions are already granted
        }
    }

    override fun onStart() {
        super.onStart()
        // Check initial login state when activity becomes visible
        updateUI(auth.currentUser)
    }

    // --- Initialization ---
    private fun initializeViews() {
        try {
            viewFinder = findViewById(R.id.viewFinder)
            boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay)
            statusText = findViewById(R.id.statusText)
            signInButton = findViewById(R.id.signInButton)
            signOutButton = findViewById(R.id.signOutButton)
            registerDeviceButton = findViewById(R.id.registerDeviceButton)
            statusText.text = getString(R.string.initializing) // References R.string
        } catch (e: Exception) {
            Log.e(TAG, "Error finding views. Check IDs in activity_main.xml", e)
            Toast.makeText(this, R.string.layout_error_toast, Toast.LENGTH_LONG).show() // References R.string
            finish() // Exit if layout is broken
        }
    }

    private fun configureGoogleSignIn() {
        // Ensure you have replaced YOUR_WEB_CLIENT_ID in strings.xml
        val webClientId = getString(R.string.default_web_client_id)
        if (webClientId == "YOUR_WEB_CLIENT_ID" || webClientId.isEmpty()) {
            Log.e(TAG, "Web Client ID is not configured in strings.xml!")
            Toast.makeText(this, "Sign-In Configuration Error!", Toast.LENGTH_LONG).show()
            // Disable sign-in button if ID is missing
            if(::signInButton.isInitialized) signInButton.isEnabled = false
            return // Cannot proceed without Web Client ID
        }

        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId) // Use the validated ID
                .requestEmail()
                .build()
            googleSignInClient = GoogleSignIn.getClient(this, gso)
            if(::signInButton.isInitialized) signInButton.isEnabled = true // Ensure enabled if setup is okay
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Google Sign In.", e)
            Toast.makeText(this, R.string.error_signin_setup, Toast.LENGTH_LONG).show() // References R.string
            if(::signInButton.isInitialized) signInButton.isEnabled = false
        }
    }

    private fun initializeSignInLauncher() {
        signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    // Google Sign In was successful, try to get account and token
                    val account = task.getResult(ApiException::class.java)
                    if (account != null && account.idToken != null) {
                        Log.d(TAG, "Google Sign In successful, firebaseAuthWithGoogle starting.")
                        firebaseAuthWithGoogle(account.idToken!!) // Use non-null assertion after check
                    } else {
                        // Handle rare case where account or token is null despite RESULT_OK
                        Log.e(TAG, "GoogleSignInAccount or idToken is null after successful sign-in attempt!")
                        Toast.makeText(this, R.string.error_getting_token, Toast.LENGTH_SHORT).show()
                        updateUI(null) // Reflect failed state
                    }
                } catch (e: ApiException) {
                    // Google Sign In failed (e.g., network error, configuration issue)
                    Log.w(TAG, "Google sign in failed", e)
                    updateUI(null) // Reflect failed state
                    // Provide a more specific error message if possible based on statusCode
                    Toast.makeText(this, "Google Sign-In Failed. Code: ${e.statusCode}", Toast.LENGTH_SHORT).show()
                }
            } else {
                // User cancelled the sign-in flow or another error occurred
                Log.w(TAG, "Google Sign-In activity result was not OK. Code: ${result.resultCode}")
                Toast.makeText(this, R.string.google_signin_failed_or_cancelled, Toast.LENGTH_SHORT).show() // References R.string
                updateUI(null) // Reflect signed-out state
            }
        }
    }

    private fun initializeDetectionProcessor() {
        detectionProcessor = DetectionProcessor(this) { detections, width, height ->
            sourceImageWidth = width
            sourceImageHeight = height
            runOnUiThread { updateDetectionResults(detections) }
        }
    }

    private fun setupButtonClickListeners() {
        signInButton.setOnClickListener { signIn() }
        signOutButton.setOnClickListener { signOut() }
        registerDeviceButton.setOnClickListener {
            uniqueDeviceId?.let { deviceId ->
                // Check registration status BEFORE attempting to register again
                if (sharedPreferences.getBoolean(getRegistrationPrefKey(deviceId), false)) {
                    Toast.makeText(this, R.string.device_already_registered, Toast.LENGTH_SHORT).show() // Use String resource
                } else {
                    registerDevice(deviceId) // Only call register if not already registered
                }
            } ?: run {
                Log.e(TAG, "Device ID is null, cannot register.")
                Toast.makeText(this, R.string.error_device_id_missing, Toast.LENGTH_SHORT).show() // References R.string
            }
        }
    }

    // --- Auth ---
    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase Authentication successful.")
                    updateUI(auth.currentUser)
                    // Optional: Auto-register device after first successful sign-in?
                    // uniqueDeviceId?.let { if (!sharedPreferences.getBoolean(getRegistrationPrefKey(it), false)) registerDevice(it) }
                } else {
                    Log.w(TAG, "Firebase Authentication failed.", task.exception)
                    updateUI(null)
                    Toast.makeText(this, R.string.error_firebase_auth_failed, Toast.LENGTH_SHORT).show() // References R.string
                }
            }
    }

    private fun signIn() {
        Log.d(TAG, "signIn: Launching Google Sign-In Intent")
        // Check initialization before launching
        if (!::googleSignInClient.isInitialized) {
            Log.e(TAG, "GoogleSignInClient not initialized. Check configuration.")
            Toast.makeText(this, R.string.error_signin_setup, Toast.LENGTH_SHORT).show() // References R.string
            return
        }
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        Log.d(TAG, "signOut: Signing out user.")
        auth.signOut() // Sign out from Firebase

        // Also sign out from Google explicitly to allow account switching
        if (::googleSignInClient.isInitialized) {
            googleSignInClient.signOut().addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Google Sign Out complete.")
                } else {
                    Log.w(TAG, "Google Sign Out failed.", task.exception)
                }
                // Update UI regardless of Google sign-out success (Firebase signout is key)
                updateUI(null)
                Toast.makeText(this, R.string.signed_out_toast, Toast.LENGTH_SHORT).show() // References R.string
            }
        } else {
            // If GSC wasn't initialized, just update UI based on Firebase signout
            updateUI(null)
            Toast.makeText(this, R.string.signed_out_toast, Toast.LENGTH_SHORT).show() // References R.string
        }
    }

    // --- Device ID ---
    // ** Renamed method **
    @SuppressLint("ApplySharedPref") // Suppress warning for apply() as minSdk > 9
    private fun retrieveOrGenerateDeviceId(): String {
        var id = sharedPreferences.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            // Use commit() if you need to ensure it's saved before proceeding,
            // otherwise apply() is fine for background saving.
            sharedPreferences.edit().putString(KEY_DEVICE_ID, id).apply()
            Log.i(TAG, "Generated new Device ID: $id")
        } else {
            Log.d(TAG, "Retrieved existing Device ID: $id")
        }
        return id
    }

    // --- Device Registration ---
    private fun registerDevice(deviceId: String) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, R.string.signin_required_to_register, Toast.LENGTH_SHORT).show() // References R.string
            return
        }

        // Redundant check, already done in listener, but safe to keep
        if (sharedPreferences.getBoolean(getRegistrationPrefKey(deviceId), false)) {
            Toast.makeText(this, R.string.device_already_registered, Toast.LENGTH_SHORT).show()
            registerDeviceButton.isEnabled = false
            return
        }

        registerDeviceButton.isEnabled = false // Disable button during request
        Toast.makeText(this@MainActivity, R.string.registering_device_toast, Toast.LENGTH_SHORT).show() // References R.string

        lifecycleScope.launch { // Use coroutine scope for async token retrieval and network call
            var idToken: String? = null
            try {
                // Get ID Token first
                val tokenResult = currentUser.getIdToken(true).await() // Force refresh token
                idToken = tokenResult.token
                if (idToken == null) throw IllegalStateException("ID Token was null after retrieval.") // Handle null case

                Log.d(TAG, "Got ID Token for registration: ${idToken.take(20)}...")

                // Prepare Request Body
                // TODO: Allow user input for deviceName later (e.g., from an EditText)
                val deviceName: String? = "My FireLoc Device (${android.os.Build.MODEL})" // Example placeholder name with device model
                val request = DeviceRegistrationRequest(deviceId, deviceName)
                val bearerTokenString = "Bearer $idToken"

                // Make Network Call using Retrofit Client
                // Ensure BASE_URL in RetrofitClient.kt is set correctly!
                Log.i(TAG, "Attempting to register device $deviceId via network call")
                // Switch to IO dispatcher for network call
                val response = withContext(Dispatchers.IO) {
                    // Ensure RetrofitClient.instance provides ApiService correctly
                    RetrofitClient.instance.registerDevice(bearerTokenString, request)
                }

                // Process Response (implicitly back on Main thread due to lifecycleScope)
                if (response.isSuccessful && response.body()?.status == "success") {
                    Log.i(TAG, "Device registration successful via network. Response: ${response.body()?.message}")
                    Toast.makeText(this@MainActivity, R.string.registration_success_toast, Toast.LENGTH_LONG).show() // References R.string
                    // Save registration status LOCALLY upon successful backend confirmation
                    sharedPreferences.edit().putBoolean(getRegistrationPrefKey(deviceId), true).apply()
                    registerDeviceButton.isEnabled = false // Keep button disabled
                } else {
                    // Handle both unsuccessful responses and success responses with unexpected body
                    val errorBody = response.errorBody()?.string() ?: response.body()?.message ?: getString(R.string.unknown_error)
                    Log.e(TAG, "Device registration failed via network: ${response.code()} - $errorBody")
                    Toast.makeText(this@MainActivity, getString(R.string.registration_failed_toast, response.code(), errorBody), Toast.LENGTH_LONG).show() // References R.string
                    registerDeviceButton.isEnabled = true // Re-enable button on failure
                }

            } catch (e: Exception) { // Catch exceptions from getting token or network call
                Log.e(TAG, "Error during device registration process", e)
                val errorMsg = when (e) {
                    is IllegalStateException -> e.message ?: getString(R.string.error_getting_token) // Use string resource
                    is java.net.UnknownHostException -> "Network Error: Cannot reach server. Check URL and connection."
                    is java.net.SocketTimeoutException -> "Network Error: Connection timed out."
                    // Add more specific exception handling if needed
                    else -> getString(R.string.network_error_toast, e.message ?: getString(R.string.unknown_error)) // General network error
                }
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                registerDeviceButton.isEnabled = true // Re-enable button on any error
            }
        } // End Coroutine Scope
    }


    // --- UI Update ---
    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            // User is signed in
            signInButton.visibility = View.GONE
            signOutButton.visibility = View.VISIBLE
            registerDeviceButton.visibility = View.VISIBLE

            // Check registration status from SharedPreferences using the unique key
            uniqueDeviceId?.let { deviceId ->
                val isRegistered = sharedPreferences.getBoolean(getRegistrationPrefKey(deviceId), false)
                registerDeviceButton.isEnabled = !isRegistered
                if (isRegistered) {
                    Log.d(TAG, "Device $deviceId already registered locally.")
                }
            } ?: run {
                registerDeviceButton.isEnabled = false // Disable if ID is missing
                Log.e(TAG, "uniqueDeviceId is null in updateUI, cannot check registration status.")
            }

            statusText.text = getString(R.string.status_signed_in, user.email ?: "Unknown User") // References R.string
        } else {
            // User is signed out
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
            registerDeviceButton.visibility = View.GONE

            statusText.text = getString(R.string.status_signed_out) // References R.string
        }
    }

    // --- Permissions ---
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        Log.d(TAG, "Requesting permissions: ${REQUIRED_PERMISSIONS.joinToString()}")
        permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // --- Camera & Detection ---
    @SuppressLint("NewApi") // For CameraX APIs potentially requiring higher than minSdk (though 26 is likely fine)
    private fun startCamera() {
        Log.d(TAG, "Starting CameraX...")
        // Check permissions again just before starting (belt and suspenders)
        if (!allPermissionsGranted()) {
            Log.w(TAG, "startCamera called but permissions are not granted.")
            requestPermissions() // Request again if somehow lost
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = viewFinder.surfaceProvider
                }

                @Suppress("DEPRECATION")
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720)) // Example resolution
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            // Only process if detectionProcessor is initialized
                            if (::detectionProcessor.isInitialized) {
                                imageProxy.imageInfo?.let { info ->
                                    sourceRotationDegrees = info.rotationDegrees
                                } ?: run { sourceRotationDegrees = 0; Log.w(TAG, "ImageInfo null") }
                                detectionProcessor.processImageProxy(imageProxy)
                            } else {
                                Log.w(TAG,"DetectionProcessor not ready, closing imageProxy.")
                                imageProxy.close() // Close if not processing
                            }
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll() // Unbind previous use cases before binding new ones
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                Log.i(TAG, "CameraX use cases bound successfully.")
                // UI status update happens via onStart or subsequent detection results
                updateUI(auth.currentUser) // Ensure UI reflects correct state initially

            } catch (exc: Exception) {
                Log.e(TAG, "CameraX binding failed", exc)
                statusText.text = getString(R.string.camera_error) // References R.string
                Toast.makeText(this, getString(R.string.camera_start_error_toast, exc.message ?: "Unknown Cause"), Toast.LENGTH_SHORT).show() // References R.string
            }
        }, ContextCompat.getMainExecutor(this)) // Run listener on main thread
    }

    private fun updateDetectionResults(detections: List<Detection>) {
        // Only proceed if view/data is ready
        if (boundingBoxOverlay.width == 0 || boundingBoxOverlay.height == 0 || sourceImageWidth == 0 || sourceImageHeight == 0) {
            Log.v(TAG, "Overlay dimensions or source image dimensions not ready yet. Skipping draw.")
            if (boundingBoxOverlay.width == 0 || boundingBoxOverlay.height == 0) {
                boundingBoxOverlay.updateDetections(emptyList()) // Clear old boxes if view gone
            }
            return
        }

        // Calculate matrix locally
        val transformMatrix = calculateTransformMatrix(sourceImageWidth, sourceImageHeight, boundingBoxOverlay.width, boundingBoxOverlay.height, sourceRotationDegrees)

        val viewDetections = detections.mapNotNull { detection ->
            val normalizedBox = detection.boundingBox
            val viewRect = RectF()
            val modelInputRect = RectF(
                normalizedBox.left * INPUT_SIZE,
                normalizedBox.top * INPUT_SIZE,
                normalizedBox.right * INPUT_SIZE,
                normalizedBox.bottom * INPUT_SIZE
            )
            transformMatrix.mapRect(viewRect, modelInputRect)
            // Clamp after transform
            viewRect.left = max(0f, viewRect.left)
            viewRect.top = max(0f, viewRect.top)
            viewRect.right = min(boundingBoxOverlay.width.toFloat(), viewRect.right)
            viewRect.bottom = min(boundingBoxOverlay.height.toFloat(), viewRect.bottom)

            if (viewRect.width() > 0 && viewRect.height() > 0) {
                Detection(detection.type, detection.confidence, viewRect)
            } else { null } // Skip if rect has no area after clamping
        }

        boundingBoxOverlay.updateDetections(viewDetections)

        // Update status text ONLY IF user is signed in
        if (auth.currentUser != null) {
            if (viewDetections.isNotEmpty()) {
                val fireCount = viewDetections.count { it.type == FireDetectionType.FIRE }
                val smokeCount = viewDetections.count { it.type == FireDetectionType.SMOKE }
                val maxConfidence = viewDetections.maxOfOrNull { it.confidence } ?: 0f
                statusText.text = getString(R.string.detection_status_format, fireCount, smokeCount, (maxConfidence * 100).toInt()) // References R.string
            } else {
                val procTime = detectionProcessor.getLastProcessingTimeMs()
                statusText.text = getString(R.string.scanning_status_with_time, procTime) // References R.string
            }
        }
        // If signed out, updateUI() handles the status text
    }

    private fun calculateTransformMatrix(
        srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, rotation: Int
    ): Matrix {
        val matrix = Matrix()
        val uprightSrcWidth = if (rotation == 90 || rotation == 270) srcHeight.toFloat() else srcWidth.toFloat()
        val uprightSrcHeight = if (rotation == 90 || rotation == 270) srcWidth.toFloat() else srcHeight.toFloat()

        // Check for zero dimensions to avoid division by zero
        if (uprightSrcWidth == 0f || uprightSrcHeight == 0f || INPUT_SIZE == 0) {
            Log.e(TAG, "Cannot calculate transform matrix with zero dimensions.")
            return matrix // Return identity matrix
        }

        val scaleX = dstWidth.toFloat() / uprightSrcWidth
        val scaleY = dstHeight.toFloat() / uprightSrcHeight
        val scale = min(scaleX, scaleY)
        val scaledBufferWidth = uprightSrcWidth * scale
        val scaledBufferHeight = uprightSrcHeight * scale
        val dx = (dstWidth - scaledBufferWidth) / 2f
        val dy = (dstHeight - scaledBufferHeight) / 2f
        val modelToViewScaleX = scaledBufferWidth / INPUT_SIZE
        val modelToViewScaleY = scaledBufferHeight / INPUT_SIZE
        matrix.postScale(modelToViewScaleX, modelToViewScaleY)
        matrix.postTranslate(dx, dy)
        return matrix
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called.")
        // Shut down the executor service gracefully
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            try {
                cameraExecutor.shutdown()
                // Optionally wait for tasks to finish
                // if (!cameraExecutor.awaitTermination(50, TimeUnit.MILLISECONDS)) {
                //    cameraExecutor.shutdownNow();
                // }
            } catch (e: InterruptedException) {
                cameraExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        // Release detection processor resources (closes ONNX model)
        if (::detectionProcessor.isInitialized) {
            detectionProcessor.release()
        }
        Log.d(TAG, "MainActivity onDestroy: Resources potentially released.")
    }
}