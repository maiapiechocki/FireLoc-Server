package com.fireloc.fireloc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.os.Looper
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fireloc.fireloc.camera.BoundingBoxOverlay
import com.fireloc.fireloc.camera.Detection
import com.fireloc.fireloc.camera.DetectionProcessor
import com.fireloc.fireloc.camera.FireDetectionType
import com.fireloc.fireloc.network.* // Import network models
import com.fireloc.fireloc.utils.ImageUtils
import com.fireloc.fireloc.util.Prefs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
// **** ADDED MISSING IMPORT ****
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FireLocMainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        private const val INPUT_SIZE = 640
        private const val REGISTER_DEVICE_URL = "https://registerdevice-pppkjwepma-uc.a.run.app"
        private const val DETECT_URL = "https://detect-pppkjwepma-uc.a.run.app"
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
    private lateinit var authStateListener: FirebaseAuth.AuthStateListener

    // Networking
    private lateinit var apiService: ApiService

    // State
    private var isDeviceRegistered: Boolean = false

    // Location Client
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Track if a cloud detection call is in progress
    private val isCloudDetectionInProgress = AtomicBoolean(false) // Now resolved


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
        setupAuthStateListener()
        initializeGoogleSignIn()
        initializeNetworking()
        initializeLocationClient()
        loadRegistrationStatus()
        setupButtonClickListeners()

        detectionProcessor = DetectionProcessor(this) { localDetections, sourceBitmap, width, height ->
            sourceImageWidth = width
            sourceImageHeight = height
            runOnUiThread {
                updateOverlayWithLocalDetections(localDetections)
                if (localDetections.isNotEmpty() && sourceBitmap != null) {
                    triggerCloudDetection(sourceBitmap)
                } else {
                    updateStatusTextBasedOnLocalDetections(localDetections)
                }
            }
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Permissions checked/requested in onStart
    }

    // --- Initialization Methods ---
    // All initialization methods remain unchanged
    private fun initializeViews() { /* ... unchanged ... */ try { viewFinder = findViewById(R.id.viewFinder); boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay); statusText = findViewById(R.id.statusText); signInButton = findViewById(R.id.signInButton); signOutButton = findViewById(R.id.signOutButton); registerDeviceButton = findViewById(R.id.registerDeviceButton); statusText.text = getString(R.string.initializing) } catch (e: IllegalStateException) { Log.e(TAG, "Error finding views.", e); Toast.makeText(this, "Layout Error", Toast.LENGTH_LONG).show(); finish() } }
    private fun initializeFirebase() { /* ... unchanged ... */ auth = Firebase.auth }
    private fun setupAuthStateListener() { /* ... unchanged ... */ authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth -> val user = firebaseAuth.currentUser; Log.d(TAG, "AuthStateListener triggered. User: ${user?.uid}"); updateUI(user); if (user != null) { if (allPermissionsGranted()) { startCamera() } else { if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { requestPermissions() } } } else { try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { Log.e(TAG, "Error unbinding camera on sign out", e) }; statusText.text = getString(R.string.signed_out) } }; }
    private fun initializeNetworking() { /* ... unchanged ... */ apiService = ApiClient.instance }
    private fun initializeLocationClient() { /* ... unchanged ... */ fusedLocationClient = LocationServices.getFusedLocationProviderClient(this); Log.d(TAG, "FusedLocationProviderClient initialized.") }
    private fun loadRegistrationStatus() { /* ... unchanged ... */ val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE); isDeviceRegistered = prefs.getBoolean(KEY_IS_DEVICE_REGISTERED, false); Log.d(TAG, "Loaded registration status: $isDeviceRegistered") }
    private fun saveRegistrationStatus(registered: Boolean) { /* ... unchanged ... */ isDeviceRegistered = registered; val prefs = getSharedPreferences("com.fireloc.fireloc.prefs", Context.MODE_PRIVATE); prefs.edit().putBoolean(KEY_IS_DEVICE_REGISTERED, registered).apply(); Log.d(TAG, "Saved registration status: $registered") }
    private fun initializeGoogleSignIn() { /* ... unchanged ... */ val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build(); googleSignInClient = GoogleSignIn.getClient(this, gso); googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == RESULT_OK) { val task = GoogleSignIn.getSignedInAccountFromIntent(result.data); try { val account = task.getResult(ApiException::class.java)!!; Log.d(TAG, "Google Sign In successful."); val idToken = account.idToken; if (idToken != null) { firebaseAuthWithGoogle(idToken) } else { Log.w(TAG, "Google ID Token was null."); statusText.text = getString(R.string.sign_in_failed) + " (No Token)"; updateUI(null) } } catch (e: ApiException) { Log.w(TAG, "Google sign in failed: ${e.statusCode}", e); statusText.text = getString(R.string.sign_in_failed) + " (API Exception: ${e.statusCode})"; updateUI(null) } } else { Log.w(TAG, "Google sign in activity cancelled/failed: ${result.resultCode}"); statusText.text = getString(R.string.sign_in_failed) + " (Result Code: ${result.resultCode})"; updateUI(null) } } }
    private fun setupButtonClickListeners() { /* ... unchanged ... */ signInButton.setOnClickListener { signIn() }; signOutButton.setOnClickListener { signOut() }; registerDeviceButton.setOnClickListener { registerDeviceWithBackend() } }


    // --- Permissions Handling ---
    // allPermissionsGranted, requestPermissions, handlePermissionsResult remain unchanged
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPermissions() { Log.i(TAG, "Requesting permissions: ${REQUIRED_PERMISSIONS.joinToString()}"); permissionsLauncher.launch(REQUIRED_PERMISSIONS) }
    private fun handlePermissionsResult(permissions: Map<String, Boolean>) { val allGranted = REQUIRED_PERMISSIONS.all { permissions.getOrDefault(it, false) }; if (allGranted) { Log.i(TAG, "All required permissions granted."); if (auth.currentUser != null) { startCamera() } } else { Log.w(TAG, "One or more permissions were denied."); val deniedPermissions = REQUIRED_PERMISSIONS.filterNot { permissions.getOrDefault(it, false) }; val message = "Required permissions denied: ${deniedPermissions.joinToString { it.substringAfterLast('.') }}"; Toast.makeText(this, message, Toast.LENGTH_LONG).show(); statusText.text = getString(R.string.permissions_required); finish() } }


    // --- Authentication Logic ---
    // signIn, signOut, firebaseAuthWithGoogle, updateUI remain unchanged
    private fun signIn() { /* ... unchanged ... */ Log.i(TAG, "Initiating Google Sign-In."); statusText.text = getString(R.string.signing_in); val signInIntent = googleSignInClient.signInIntent; googleSignInLauncher.launch(signInIntent) }
    private fun signOut() { /* ... unchanged ... */ Log.i(TAG, "Initiating Sign Out."); statusText.text = "Signing Out..."; auth.signOut(); googleSignInClient.signOut().addOnCompleteListener(this) { task -> Log.d(TAG,"Google Sign-Out task complete."); Toast.makeText(this, "Signed Out", Toast.LENGTH_SHORT).show() } }
    private fun firebaseAuthWithGoogle(idToken: String) { /* ... unchanged ... */ val credential = GoogleAuthProvider.getCredential(idToken, null); Log.d(TAG, "Calling Firebase auth.signInWithCredential..."); statusText.text = "Authenticating with Firebase..."; auth.signInWithCredential(credential).addOnCompleteListener(this) { task -> Log.d(TAG, "Firebase signInWithCredential complete. Task Successful: ${task.isSuccessful}"); if (!task.isSuccessful) { Log.w(TAG, "Firebase signInWithCredential:failure", task.exception); statusText.text = getString(R.string.sign_in_failed) + " (Firebase Auth Error)"; Toast.makeText(baseContext, "Firebase Authentication Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show(); updateUI(null) } } }
    private fun updateUI(user: FirebaseUser?) { /* ... unchanged ... */ Log.d(TAG, "Updating UI for user: ${user?.uid}"); if (user != null) { statusText.text = getString(R.string.signed_in_fmt, user.email ?: "User"); signInButton.visibility = View.GONE; signOutButton.visibility = View.VISIBLE; registerDeviceButton.visibility = View.VISIBLE; registerDeviceButton.isEnabled = !isDeviceRegistered; registerDeviceButton.alpha = if (isDeviceRegistered) 0.5f else 1.0f } else { statusText.text = getString(R.string.signed_out); signInButton.visibility = View.VISIBLE; signOutButton.visibility = View.GONE; registerDeviceButton.visibility = View.GONE }; boundingBoxOverlay.updateDetections(emptyList()) }


    // --- Device Registration Logic ---
    // getIdToken, registerDeviceWithBackend remain unchanged
    private fun getIdToken(forceRefresh: Boolean = false, callback: (token: String?) -> Unit) { /* ... unchanged ... */ val currentUser = auth.currentUser; if (currentUser == null) { Log.w(TAG, "getIdToken: user is null."); callback(null); return }; currentUser.getIdToken(forceRefresh).addOnCompleteListener { task -> if (task.isSuccessful) { callback(task.result?.token) } else { Log.e(TAG, "getIdToken failed", task.exception); callback(null) } } }
    private fun registerDeviceWithBackend() { /* ... unchanged ... */ val currentUser=auth.currentUser;if(currentUser==null){Toast.makeText(this,"Sign in required.",Toast.LENGTH_SHORT).show();return};if(isDeviceRegistered){Toast.makeText(this,"Device already registered.",Toast.LENGTH_SHORT).show();return};val deviceId=Prefs.getDeviceId(this);Log.d(TAG,"Device ID: $deviceId");statusText.text="Getting Token...";getIdToken(true){idToken->if(idToken==null){statusText.text=getString(R.string.token_error);return@getIdToken};statusText.text=getString(R.string.registering_device);val requestBody=DeviceRegistrationRequest(deviceId=deviceId);val authHeader="Bearer $idToken";lifecycleScope.launch{try{val response=apiService.registerDevice(REGISTER_DEVICE_URL,authHeader,requestBody);if(response.isSuccessful){Log.i(TAG,"Device registration successful");saveRegistrationStatus(true);updateUI(currentUser);Toast.makeText(applicationContext,"Device Registered!",Toast.LENGTH_SHORT).show()}else{val errorBody=response.errorBody()?.string()?:"?";Log.e(TAG,"Device registration failed(${response.code()}): $errorBody");statusText.text=getString(R.string.registration_failed);Toast.makeText(applicationContext,"Registration failed: ${response.code()}",Toast.LENGTH_LONG).show();updateUI(currentUser)}}catch(e:Exception){Log.e(TAG,"Exception during registration",e);statusText.text=getString(R.string.registration_error_fmt,e.localizedMessage);Toast.makeText(applicationContext,"Registration Error",Toast.LENGTH_LONG).show();updateUI(currentUser)}}}}


    // --- CameraX Logic ---
    // startCamera remains unchanged
    @SuppressLint("UnsafeOptInUsageError") private fun startCamera() { /* ... unchanged ... */ if(!allPermissionsGranted()){Log.w(TAG,"startCamera called but permissions not granted.");requestPermissions();return};Log.d(TAG,"Attempting to start CameraX.");if(auth.currentUser!=null)statusText.text=getString(R.string.initializing);val cameraProviderFuture=ProcessCameraProvider.getInstance(this);cameraProviderFuture.addListener({try{val cameraProvider:ProcessCameraProvider=cameraProviderFuture.get();val preview=Preview.Builder().setTargetResolution(Size(1280,720)).build().also{it.setSurfaceProvider(viewFinder.surfaceProvider)};val imageAnalyzer=ImageAnalysis.Builder().setTargetResolution(Size(1280,720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build().also{it.setAnalyzer(cameraExecutor){imageProxy->sourceRotationDegrees=imageProxy.imageInfo.rotationDegrees;detectionProcessor.processImageProxy(imageProxy)}};val cameraSelector=CameraSelector.DEFAULT_BACK_CAMERA;cameraProvider.unbindAll();cameraProvider.bindToLifecycle(this,cameraSelector,preview,imageAnalyzer);Log.i(TAG,"CameraX bound successfully.");if(auth.currentUser!=null)statusText.text=getString(R.string.scanning_status)}catch(exc:Exception){Log.e(TAG,"Use case binding failed",exc);statusText.text=getString(R.string.camera_error)}},ContextCompat.getMainExecutor(this)); }


    // --- Location Fetching ---
    // hasLocationPermission, getCurrentLocation, tryLastLocation remain unchanged
    private fun hasLocationPermission(): Boolean { /* ... unchanged ... */ return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    @SuppressLint("MissingPermission") private fun getCurrentLocation(callback: (location: Location?) -> Unit) { /* ... unchanged ... */ if(!hasLocationPermission()){callback(null);return};fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener{location:Location?->if(location!=null){callback(location)}else{Log.w(TAG,"getCurrentLocation null, trying last.");tryLastLocation(callback)}}.addOnFailureListener{e->Log.e(TAG,"getCurrentLocation failed",e);tryLastLocation(callback)} }
    @SuppressLint("MissingPermission") private fun tryLastLocation(callback: (location: Location?) -> Unit) { /* ... unchanged ... */ if(!hasLocationPermission()){callback(null);return};fusedLocationClient.lastLocation.addOnSuccessListener{location:Location?->callback(location)}.addOnFailureListener{e->Log.e(TAG,"Getting lastLocation failed",e);callback(null)} }


    // --- Detection Result Handling & Cloud Trigger ---
    // updateOverlayWithLocalDetections, updateStatusTextBasedOnLocalDetections, triggerCloudDetection remain unchanged
    private fun updateOverlayWithLocalDetections(localDetections: List<Detection>) { /* ... unchanged ... */ if (boundingBoxOverlay.width == 0 || boundingBoxOverlay.height == 0 || sourceImageWidth == 0 || sourceImageHeight == 0) return; imageToViewMatrix.set(calculateTransformMatrix(sourceImageWidth, sourceImageHeight, boundingBoxOverlay.width, boundingBoxOverlay.height, sourceRotationDegrees)); val viewDetections = localDetections.mapNotNull { detection -> val normBox=detection.boundingBox; val viewRect=RectF(); val modelRect=RectF(normBox.left*INPUT_SIZE,normBox.top*INPUT_SIZE,normBox.right*INPUT_SIZE,normBox.bottom*INPUT_SIZE); imageToViewMatrix.mapRect(viewRect,modelRect); viewRect.left=max(0f,viewRect.left); viewRect.top=max(0f,viewRect.top); viewRect.right=min(boundingBoxOverlay.width.toFloat(),viewRect.right); viewRect.bottom=min(boundingBoxOverlay.height.toFloat(),viewRect.bottom); if(viewRect.width()>0&&viewRect.height()>0) Detection(detection.type,detection.confidence,viewRect) else null }; boundingBoxOverlay.updateDetections(viewDetections) }
    private fun updateStatusTextBasedOnLocalDetections(localDetections: List<Detection>) { /* ... unchanged ... */ if (auth.currentUser == null) { statusText.text = getString(R.string.signed_out); return; }; if (localDetections.isNotEmpty()) { val fire=localDetections.count{it.type==FireDetectionType.FIRE}; val smoke=localDetections.count{it.type==FireDetectionType.SMOKE}; val conf=localDetections.maxOfOrNull{it.confidence}?:0f; statusText.text = "Local: ${getString(R.string.detection_status_format, fire, smoke, (conf * 100).toInt())}" } else { val time=detectionProcessor.getLastProcessingTimeMs(); statusText.text = "${getString(R.string.scanning_status)} (${time}ms)" } }
    private fun triggerCloudDetection(sourceBitmap: Bitmap) { /* ... unchanged ... */ if(!isCloudDetectionInProgress.compareAndSet(false,true)){Log.w(TAG,"Cloud detection already in progress.");return};Log.i(TAG,"Starting cloud detection process...");val currentUser=auth.currentUser;if(currentUser==null){Log.w(TAG,"User signed out.");isCloudDetectionInProgress.set(false);return};if(!isDeviceRegistered){Log.w(TAG,"Device not registered.");Toast.makeText(this,"Device not registered.",Toast.LENGTH_SHORT).show();isCloudDetectionInProgress.set(false);return};if(!hasLocationPermission()){Log.w(TAG,"Location permission missing.");Toast.makeText(this,"Location permission needed.",Toast.LENGTH_SHORT).show();isCloudDetectionInProgress.set(false);return};statusText.text="Getting data for cloud...";getIdToken(true){idToken->if(idToken==null){Log.e(TAG,"Failed to get ID token.");statusText.text=getString(R.string.token_error);isCloudDetectionInProgress.set(false);return@getIdToken};val authHeader="Bearer $idToken";getCurrentLocation{location->val locationData=location?.let{LocationData(it.latitude,it.longitude,if(it.hasAccuracy())it.accuracy else null)};if(location==null){Log.w(TAG,"Proceeding without location data.")};val deviceId=Prefs.getDeviceId(this);val timestamp=System.currentTimeMillis();statusText.text="Preparing image...";lifecycleScope.launch{var base64Image:String?=null;var success=false;try{withContext(Dispatchers.IO){Log.d(TAG,"Encoding bitmap...");base64Image=ImageUtils.bitmapToBase64(sourceBitmap);Log.d(TAG,"Encoding done. Size: ${base64Image?.length}")};if(base64Image==null)throw Exception("Base64 encoding failed");val detectRequest=DetectRequest(deviceId,base64Image!!,timestamp,locationData,true);statusText.text="Sending to cloud...";Log.d(TAG,"Calling /detect API: $DETECT_URL");val response=apiService.detect(DETECT_URL,authHeader,detectRequest);if(response.isSuccessful){val respBody=response.body();Log.i(TAG,"/detect SUCCESS (${response.code()}): $respBody");statusText.text="Cloud: ${respBody?.status?:"Processed"}${if(respBody?.detected==true)" - Confirmed" else ""}";success=true}else{val errorBody=response.errorBody()?.string()?:"?";Log.e(TAG,"/detect FAILED (${response.code()}): $errorBody");statusText.text="Cloud Detect Failed (${response.code()})";Toast.makeText(applicationContext,"Cloud Error: ${response.code()}",Toast.LENGTH_SHORT).show()}}catch(e:Exception){Log.e(TAG,"Exception in cloud detection trigger",e);statusText.text="Cloud Error: ${e.localizedMessage}";Toast.makeText(applicationContext,"Cloud Process Error",Toast.LENGTH_SHORT).show()}finally{isCloudDetectionInProgress.set(false)}}}}}


    // calculateTransformMatrix remains unchanged
    private fun calculateTransformMatrix(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int, rotation: Int): Matrix { /* ... unchanged ... */ val transformMatrix=Matrix();val(uprightSrcWidth,uprightSrcHeight)=if(rotation==90||rotation==270){srcHeight.toFloat() to srcWidth.toFloat()}else{srcWidth.toFloat() to srcHeight.toFloat()};val scaleX=dstWidth.toFloat()/uprightSrcWidth;val scaleY=dstHeight.toFloat()/uprightSrcHeight;val scale=min(scaleX,scaleY);val scaledImageWidth=uprightSrcWidth*scale;val scaledImageHeight=uprightSrcHeight*scale;val dx=(dstWidth-scaledImageWidth)/2f;val dy=(dstHeight-scaledImageHeight)/2f;val finalMatrix=Matrix();finalMatrix.postScale(scale*uprightSrcWidth/INPUT_SIZE,scale*uprightSrcHeight/INPUT_SIZE);finalMatrix.postTranslate(dx,dy);return finalMatrix }

    // --- Lifecycle ---
    // onStart, onStop, onDestroy remain unchanged (using AuthStateListener)
    override fun onStart() { super.onStart(); if(::authStateListener.isInitialized) { auth.addAuthStateListener(authStateListener); Log.d(TAG, "AuthStateListener registered.") }; if (!allPermissionsGranted()) { requestPermissions() } else if (auth.currentUser != null){ startCamera() } }
    override fun onStop() { super.onStop(); if(::authStateListener.isInitialized) { auth.removeAuthStateListener(authStateListener); Log.d(TAG, "AuthStateListener unregistered.") } }
    override fun onDestroy() { super.onDestroy(); if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) { cameraExecutor.shutdown() }; if (::detectionProcessor.isInitialized) { detectionProcessor.release() }; Log.d(TAG, "MainActivity onDestroy completed.") }

} // End of MainActivity class