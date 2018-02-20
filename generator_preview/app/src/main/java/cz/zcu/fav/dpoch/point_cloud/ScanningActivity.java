package cz.zcu.fav.dpoch.point_cloud;

import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.google.tango.support.TangoSupport;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScanningActivity extends AppCompatActivity {
    private static final String TAG = ScanningActivity.class.getSimpleName();
    private static final int INVALID_TEXTURE_ID = 0;

    private StringBuffer outputMessage;
    private int pointCloudsCounter = 0;

    private Tango tango;
    private TangoConfig tangoConfig;
    private boolean isConnected = false;
    private boolean isScanning = false;

    private GLSurfaceView previewView;
    private VideoRenderer renderer;

    // NOTE: Naming indicates which thread is in charge of updating this variable.
    private int connectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean isFrameAvailableTangoThread = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanning);

        this.outputMessage = new StringBuffer();
        this.previewView = (GLSurfaceView) findViewById(R.id.textureView);
        setupRenderer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        previewView.onResume();
        // Set render mode to RENDERMODE_CONTINUOUSLY to force getting onDraw callbacks until the
        // Tango Service is properly set up and we start getting onFrameAvailable callbacks.
        previewView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        createTango();
    }

    @Override
    protected void onPause() {
        super.onPause();
        previewView.onPause();
        if (tango != null) {
            disconnectTango();
            resetUI();
        }
    }

    /*
     * ------------------------------- TANGO ----------------------------------
     */

    private void createTango() {
        // Initialize Tango Service as a normal Android Service. Since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time onResume gets called we
        // should create a new Tango object.
        this.tango = new Tango(ScanningActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready; this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there are no
            // UI thread changes involved.
            @Override
            public void run() {
                synchronized (ScanningActivity.this) {
                    try {
                        tangoConfig = setupTangoConfig(tango);
                        tango.connect(tangoConfig);
                        startupTango();
                        TangoSupport.initialize(tango);
                        isConnected = true;
                        setDisplayRotation();
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                        showsToastAndFinishOnUiThread(R.string.exception_out_of_date);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_error);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.exception_tango_invalid), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_invalid);
                    }
                }
            }
        });
    }

    /**
     * Sets up the Tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Create a new Tango configuration and enable the Depth Sensing API.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
        return config;
    }

    /**
     * Set up the callback listeners for the Tango Service and obtain other parameters required
     * after Tango connection.
     * Listen to new Point Cloud data.
     */
    private void startupTango() {
        // Lock configuration and connect to Tango.
        // Select coordinate frame pair.
        final ArrayList<TangoCoordinateFramePair> framePairs =
                new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));

        // Listen for new Tango data.
        this.tango.connectListener(framePairs, new Tango.OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // We are not using TangoPoseData for this application.
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onPointCloudAvailable(final TangoPointCloudData pointCloudData) {
                if (isScanning) {
                    writePointCloudTxt(pointCloudData);
                }
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                // Ignoring TangoEvents.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                Log.d(TAG, "onFrameAvailable");

                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {

                    // Note that the RGB data is not passed as a parameter here.
                    // Instead, this callback indicates that you can call
                    // the {@code updateTexture()} method to have the
                    // RGB data copied directly to the OpenGL texture at the native layer.
                    // Since that call needs to be done from the OpenGL thread, what we do here is
                    // set up a flag to tell the OpenGL thread to do that in the next run.
                    // NOTE: Even if we are using a render-by-request method, this flag is still
                    // necessary since the OpenGL thread run requested below is not guaranteed
                    // to run in synchrony with this requesting call.
                    isFrameAvailableTangoThread.set(true);
                    // Trigger an OpenGL render to update the OpenGL scene with the new RGB data.
                    previewView.requestRender();
                }
            }
        });
    }

    private synchronized void disconnectTango() {
        try {
            this.tango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
            // We need to invalidate the connected texture ID so that we cause a
            // re-connection in the OpenGL thread after resume.
            connectedTextureIdGlThread = INVALID_TEXTURE_ID;
            this.tango.disconnect();
            this.tango = null;
            this.isConnected = false;
            this.isScanning = false;
        } catch (TangoErrorException e) {
            Log.e(TAG, getString(R.string.exception_tango_error), e);
        }
    }

    /*
     * ------------------------------- RENDERER ----------------------------------
     */

    /**
     * Here is where you would set up your rendering logic. We're replacing it with a minimalistic,
     * dummy example, using a standard GLSurfaceView and a basic renderer, for illustration purposes
     * only.
     */
    private void setupRenderer() {
        previewView.setEGLContextClientVersion(2);
        renderer = new VideoRenderer(new VideoRenderer.RenderCallback() {
            @Override
            public void preRender() {
                Log.d(TAG, "preRender");
                // This is the work that you would do on your main OpenGL render thread.

                // We need to be careful to not run any Tango-dependent code in the OpenGL
                // thread unless we know the Tango Service to be properly set up and connected.
                if (!isConnected) {
                    Log.d(TAG, "Not connected.");
                    return;
                }

                try {
                    // Synchronize against concurrently disconnecting the service triggered from the
                    // UI thread.
                    synchronized (ScanningActivity.this) {
                        // Connect the Tango SDK to the OpenGL texture ID where we are going to
                        // render the camera.
                        // NOTE: This must be done after the texture is generated and the Tango
                        // service is connected.
                        if (connectedTextureIdGlThread == INVALID_TEXTURE_ID) {
                            connectedTextureIdGlThread = renderer.getTextureId();
                            tango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                    renderer.getTextureId());
                            Log.d(TAG, "connected to texture id: " + renderer.getTextureId());
                        }

                        // If there is a new RGB camera frame available, update the texture and
                        // scene camera pose.
                        if (isFrameAvailableTangoThread.compareAndSet(true, false)) {
                            tango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                            Log.d(TAG, "Frame updated.");
                        }
                    }
                } catch (TangoErrorException e) {
                    Log.e(TAG, "Tango API call error within the OpenGL thread", e);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception on the OpenGL thread", t);
                }
            }
        });
        previewView.setRenderer(renderer);
    }

    /**
     * Set the color camera background texture rotation and save the camera to display rotation.
     */
    private void setDisplayRotation() {
        Display display = getWindowManager().getDefaultDisplay();
        final int displayRotation = display.getRotation();

        // We also need to update the camera texture UV coordinates. This must be run in the OpenGL
        // thread.
        previewView.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    renderer.updateColorCameraTextureUv(displayRotation);
                }
            }
        });
    }

    /*
     * ------------------------------- REST ----------------------------------
     */

    public void startScanning(View v) {
        this.isScanning = true;
        ImageButton imageButton = (ImageButton) findViewById(R.id.controlScanning);
        imageButton.setImageResource(R.drawable.ic_recording);
        imageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finishScanning(view);
            }
        });
    }

    public void finishScanning(View v) {
        this.isScanning = false;
        Intent i = new Intent(this, OutputActivity.class);
        i.putExtra("MESSAGE", this.outputMessage.toString());
        startActivity(i);
    }

    private void resetUI() {
        this.pointCloudsCounter = 0;
        ImageButton imageButton = (ImageButton) findViewById(R.id.controlScanning);
        imageButton.setImageResource(R.drawable.ic_camera);
        imageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScanning(view);
            }
        });
        TextView counterView = (TextView) findViewById(R.id.counterText);
        counterView.setText("");
    }

    private void writePointCloudTxt(TangoPointCloudData pointCloudData)  {
        this.pointCloudsCounter++;
        this.outputMessage.append("Writing point cloud " + this.pointCloudsCounter + " into ");
        this.updateCounterOnUiThread();

        String filename = "points" + this.pointCloudsCounter;
        PrintWriter writer;
        long start = 0, end = 0;

        try {
            File file = new File(getExternalFilesDir(null), filename);
            this.outputMessage.append(file.getPath() + "\n");
            writer = new PrintWriter(new BufferedWriter(new FileWriter(file)));
            FloatBuffer buffer = pointCloudData.points;
            start = System.currentTimeMillis();
            while(buffer.hasRemaining()){
                writer.println(buffer.get());
            }

            end = System.currentTimeMillis();
            writer.close();
        } catch (Exception e) {
            showsToastAndFinishOnUiThread(R.string.exception_file_writing);
        }

        this.outputMessage.append("Writing of " + filename + " is finished and took " + (end - start) + "ms. \n");
        this.outputMessage.append("----------------\n");
    }

    private void updateCounterOnUiThread() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView counterView = (TextView) findViewById(R.id.counterText);
                counterView.setText("" + pointCloudsCounter);
            }
        });
    }

    /**
     * Display toast on UI thread.
     *
     * @param resId The resource id of the string resource to use. Can be formatted text.
     */
    private void showsToastAndFinishOnUiThread(final int resId){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ScanningActivity.this,
                        getString(resId), Toast.LENGTH_LONG).show();
            }
        });

        finish();
    }
}
