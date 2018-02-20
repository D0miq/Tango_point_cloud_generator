package cz.zcu.fav.dpoch.point_cloud_generator;

        import com.google.atap.tangoservice.Tango;
        import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
        import com.google.atap.tangoservice.TangoConfig;
        import com.google.atap.tangoservice.TangoCoordinateFramePair;
        import com.google.atap.tangoservice.TangoErrorException;
        import com.google.atap.tangoservice.TangoEvent;
        import com.google.atap.tangoservice.TangoInvalidException;
        import com.google.atap.tangoservice.TangoOutOfDateException;
        import com.google.atap.tangoservice.TangoPointCloudData;
        import com.google.atap.tangoservice.TangoPoseData;
        import com.google.atap.tangoservice.TangoXyzIjData;

        import android.app.Activity;
        import android.os.Bundle;
        import android.util.Log;
        import android.widget.EditText;
        import android.widget.TextView;
        import android.widget.Toast;
        import android.widget.Button;
        import android.view.View;

        import java.io.BufferedWriter;
        import java.io.DataOutputStream;
        import java.io.File;
        import java.io.FileOutputStream;
        import java.io.FileWriter;
        import java.io.PrintWriter;
        import java.nio.FloatBuffer;
        import java.util.ArrayList;


/**
 * Main activity class for the Depth Perception sample. Handles the connection to the {@link Tango}
 * service and propagation of Tango Point Cloud data to Layout view.
 */
public class PointCloudGeneratorActivity extends Activity {

    private static final String TAG = PointCloudGeneratorActivity.class.getSimpleName();

    private Tango mTango;
    private TangoConfig mConfig;
    private int pointCloudsCounter = 0;
    private int framesCount = 0;
    private TextView outputText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_point_cloud_generator);

        final Button button = findViewById(R.id.button);
        final EditText editText = findViewById(R.id.inputText);
        outputText = findViewById(R.id.outputText);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String input = editText.getText().toString();
                try {
                    framesCount = pointCloudsCounter + framesCount + Integer.parseInt(input);
                }catch(NumberFormatException e){
                    outputText.append(R.string.wrong_input + "\n");
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Initialize Tango Service as a normal Android Service. Since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time onResume gets called we
        // should create a new Tango object.
        mTango = new Tango(PointCloudGeneratorActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready; this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there are no
            // UI thread changes involved.
            @Override
            public void run() {
                synchronized (PointCloudGeneratorActivity.this) {
                    try {
                        mConfig = setupTangoConfig(mTango);
                        mTango.connect(mConfig);
                        startupTango();
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

    @Override
    protected void onPause() {
        super.onPause();
        synchronized (this) {
            try {
                mTango.disconnect();
            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.exception_tango_error), e);
            }
        }
    }

    /**
     * Sets up the Tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Create a new Tango configuration and enable the Depth Sensing API.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
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
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {
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
                if(pointCloudsCounter < framesCount){
                    writePointCloudTxt(pointCloudData);
                } else if(framesCount != 0) {
                    framesCount = 0;
                }
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                // Ignoring TangoEvents.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }

    private void writePointCloudTxt(TangoPointCloudData pointCloudData)  {
        long start = System.currentTimeMillis();
        this.pointCloudsCounter++;
        outputText.append("Writing point cloud " + pointCloudsCounter + "into file. \n");
        String filename = "points" + this.pointCloudsCounter;
        PrintWriter writer;

        try {
            File file = new File(getExternalFilesDir(null), filename);
            outputText.append(file.getPath() + "\n");
            writer = new PrintWriter(new BufferedWriter(new FileWriter(file)));
            FloatBuffer buffer = pointCloudData.points;
            while(buffer.hasRemaining()){
                writer.println(buffer.get());
            }
            writer.close();
        } catch (Exception e) {
            showsToastAndFinishOnUiThread(R.string.exception_file_writing);
        }
        long end = System.currentTimeMillis();
        outputText.append("Writing of " + filename + "is finished and took " + (end - start) + "ms. \n");
    }


    private void writePointCloudBin(TangoPointCloudData pointCloudData) {
        long start = System.currentTimeMillis();
        this.pointCloudsCounter++;
        outputText.append("Writing point cloud " + pointCloudsCounter + "into file. \n");
        String filename = "points" + this.pointCloudsCounter;
        DataOutputStream  outputStream;

        try {
            File file = new File(getExternalFilesDir(null), filename);
            outputText.append(file.getPath() + "\n");
            outputStream = new DataOutputStream(new FileOutputStream(file));
            FloatBuffer buffer = pointCloudData.points;
            while(buffer.hasRemaining()){
                outputStream.writeFloat(buffer.get());
            }
            outputStream.close();
        } catch (Exception e) {
            showsToastAndFinishOnUiThread(R.string.exception_file_writing);
        }
        long end = System.currentTimeMillis();
        outputText.append("Writing of " + filename + "is finished and took " + (end - start) + "ms. \n");
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
                Toast.makeText(PointCloudGeneratorActivity.this,
                        getString(resId), Toast.LENGTH_LONG).show();
            }
        });
        finish();
    }
}
