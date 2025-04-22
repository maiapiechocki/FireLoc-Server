# python-service/app.py

import os
import traceback
from flask import Flask, request, jsonify

# --- TODO (Vanya & Andrew): Import your refactored algorithm functions ---
# Example:
# from algorithms.dmt import run_dmt_localization
# from algorithms.topomono import run_topomono_localization
# from algorithms.firetri import run_firetri_localization
# from utils.dem import load_dem_for_area # Example utility
# from utils.image import load_image_from_gcs # Example utility

# --- Flask App Initialization ---
app = Flask(__name__)

# --- Helper Function ---
def validate_camera_params(params):
    """Basic validation for camera_params structure."""
    required_keys = ["lat", "lon", "elevation_m", "heading_deg", "tilt_deg", "fov_h_deg", "fov_v_deg", "resolution"]
    if not isinstance(params, dict): return False
    if not all(key in params for key in required_keys): return False
    if not isinstance(params.get("resolution"), dict) or \
            "width" not in params["resolution"] or \
            "height" not in params["resolution"]: return False
    # Add more type checks if needed
    return True

# --- API Endpoints ---

@app.route('/localize_single_camera', methods=['POST'])
def localize_single_camera():
    """
    Endpoint to localize using TopoMono or DMT based on single camera view.
    """
    print("Received request for /localize_single_camera")
    try:
        data = request.get_json()
        if not data:
            return jsonify({"status": "error", "message": "Bad Request: No JSON data received."}), 400

        # --- Extract and Validate Input ---
        algorithm = data.get('algorithm')
        image_gcs_uri = data.get('image_gcs_uri')
        detection_pixel = data.get('detection_pixel')
        camera_params = data.get('camera_params')
        dem_identifier = data.get('dem_identifier') # How service identifies which DEM

        required_fields = [algorithm, image_gcs_uri, detection_pixel, camera_params, dem_identifier]
        if not all(required_fields):
            return jsonify({"status": "error", "message": "Bad Request: Missing required fields (algorithm, image_gcs_uri, detection_pixel, camera_params, dem_identifier)."}), 400

        if not isinstance(detection_pixel, dict) or "x" not in detection_pixel or "y" not in detection_pixel:
            return jsonify({"status": "error", "message": "Bad Request: Invalid 'detection_pixel' format."}), 400

        if not validate_camera_params(camera_params):
            return jsonify({"status": "error", "message": "Bad Request: Invalid 'camera_params' structure or missing keys."}), 400

        print(f"Processing request: Algorithm='{algorithm}', Image='{image_gcs_uri}', Pixel=({detection_pixel['x']},{detection_pixel['y']}), DEM='{dem_identifier}'")

        # --- TODO (Vanya): Add logic to load DEM based on dem_identifier ---
        # dem_data, dem_geo_transform = load_dem_for_area(dem_identifier)
        # if dem_data is None: return jsonify({... error ...}), 500

        # --- TODO (Vanya): Add logic to load image based on image_gcs_uri ---
        # image_np = load_image_from_gcs(image_gcs_uri)
        # if image_np is None: return jsonify({... error ...}), 500

        # --- TODO (Vanya): Add logic to load/cache MonoDepth2 model ---
        # mono_model = get_monodepth_model() # Function to load/cache model

        # --- Call Actual Algorithm (Replace Placeholder) ---
        lat, lon, elev, success, error_msg = None, None, None, False, "Algorithm logic not implemented"

        if algorithm == "TopoMono":
            print("Placeholder: Calling TopoMono")
            # lat, lon, elev, success = run_topomono_localization(camera_params, detection_pixel, dem_data, dem_geo_transform)
            # Mock success for now:
            lat, lon, elev, success, error_msg = 34.075, -118.198, 450.0, True, None
        elif algorithm == "DMT" or algorithm == "DMT-RANSAC":
            print(f"Placeholder: Calling {algorithm}")
            # lat, lon, elev, success = run_dmt_localization(camera_params, detection_pixel, image_np, mono_model, dem_data, dem_geo_transform, use_ransac=(algorithm=="DMT-RANSAC"))
            # Mock success for now:
            lat, lon, elev, success, error_msg = 34.075123, -118.198456, 455.7, True, None
        else:
            return jsonify({"status": "error", "message": f"Bad Request: Unsupported algorithm '{algorithm}'."}), 400

        # --- Format Response ---
        if success:
            response_data = {
                "status": "success",
                "latitude": lat,
                "longitude": lon,
                "elevation_m": elev,
                "method_used": algorithm,
                "message": None
            }
            return jsonify(response_data), 200
        else:
            response_data = {
                "status": "error",
                "latitude": None,
                "longitude": None,
                "elevation_m": None,
                "method_used": algorithm,
                "message": error_msg or "Algorithm execution failed."
            }
            return jsonify(response_data), 500 # Internal server error if algorithm fails

    except Exception as e:
        print(f"!!! UNEXPECTED ERROR in /localize_single_camera: {e}")
        traceback.print_exc() # Print full stack trace to logs
        return jsonify({
            "status": "error",
            "latitude": None, "longitude": None, "elevation_m": None,
            "method_used": data.get('algorithm', 'unknown') if 'data' in locals() else 'unknown',
            "message": f"Internal Server Error: {type(e).__name__}"
        }), 500


@app.route('/localize_triangulate', methods=['POST'])
def localize_triangulate():
    """
    Endpoint to localize using FireTri based on multiple camera views.
    """
    print("Received request for /localize_triangulate")
    try:
        data = request.get_json()
        if not data:
            return jsonify({"status": "error", "message": "Bad Request: No JSON data received."}), 400

        # --- Extract and Validate Input ---
        camera_views = data.get('camera_views')
        dem_identifier = data.get('dem_identifier')

        if not dem_identifier:
            return jsonify({"status": "error", "message": "Bad Request: Missing 'dem_identifier'."}), 400

        if not isinstance(camera_views, list) or len(camera_views) < 2:
            return jsonify({"status": "error", "message": "Bad Request: 'camera_views' must be a list with at least 2 elements."}), 400

        # Validate each camera view structure
        for i, view in enumerate(camera_views):
            if not isinstance(view, dict): return jsonify({"status": "error", "message": f"Bad Request: camera_views[{i}] is not an object."}), 400
            if "camera_params" not in view or "detection_pixel" not in view: return jsonify({"status": "error", "message": f"Bad Request: camera_views[{i}] missing 'camera_params' or 'detection_pixel'."}), 400
            if not validate_camera_params(view["camera_params"]): return jsonify({"status": "error", "message": f"Bad Request: Invalid 'camera_params' in camera_views[{i}]."}), 400
            if not isinstance(view["detection_pixel"], dict) or "x" not in view["detection_pixel"] or "y" not in view["detection_pixel"]: return jsonify({"status": "error", "message": f"Bad Request: Invalid 'detection_pixel' in camera_views[{i}]."}), 400

        print(f"Processing request: Triangulating {len(camera_views)} views, DEM='{dem_identifier}'")

        # --- TODO (Andrew): Add logic to load DEM based on dem_identifier (optional elevation correction) ---
        # dem_data, dem_geo_transform = load_dem_for_area(dem_identifier) # Maybe optional?

        # --- Call Actual Algorithm (Replace Placeholder) ---
        lat, lon, elev, success, error_msg = None, None, None, False, "Algorithm logic not implemented"

        print("Placeholder: Calling FireTri")
        # lat, lon, elev, success = run_firetri_localization(camera_views, dem_data, dem_geo_transform)
        # Mock success for now:
        lat, lon, elev, success, error_msg = 34.074987, -118.198888, 450.2, True, None

        # --- Format Response ---
        if success:
            response_data = {
                "status": "success",
                "latitude": lat,
                "longitude": lon,
                "elevation_m": elev,
                "method_used": "FireTri",
                "message": None
            }
            return jsonify(response_data), 200
        else:
            response_data = {
                "status": "error",
                "latitude": None,
                "longitude": None,
                "elevation_m": None,
                "method_used": "FireTri",
                "message": error_msg or "Algorithm execution failed."
            }
            return jsonify(response_data), 500 # Internal server error if algorithm fails

    except Exception as e:
        print(f"!!! UNEXPECTED ERROR in /localize_triangulate: {e}")
        traceback.print_exc() # Print full stack trace to logs
        return jsonify({
            "status": "error",
            "latitude": None, "longitude": None, "elevation_m": None,
            "method_used": "FireTri",
            "message": f"Internal Server Error: {type(e).__name__}"
        }), 500

# --- Main Execution ---
if __name__ == "__main__":
    # Get port from environment variable or default to 8080
    port = int(os.environ.get("PORT", 8080))
    # Run the app (accessible externally '0.0.0.0')
    # debug=True automatically reloads on code changes, BUT DO NOT USE IN PRODUCTION
    app.run(host='0.0.0.0', port=port, debug=False)