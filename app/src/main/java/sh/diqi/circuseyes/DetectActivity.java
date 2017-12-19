package sh.diqi.circuseyes;

import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.opencv.ImageProcessor;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.widget.UVCCameraTextureView;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class DetectActivity extends BaseActivity
        implements CameraDialog.CameraDialogParent {

    private USBMonitor mUSBMonitor;

    private ArrayList<UVCCameraTextureView> mCameraViews;
    private UVCCameraHandler[] mCameraHandlers;
    private ImageProcessor[] mImageProcessors;

    private int mProcessingIndex = -1;
    private boolean[] mProcessorRunning;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);

        mCameraViews = new ArrayList<>();
        LinearLayout rootLayout = findViewById(R.id.root);
        for (int i = 0; i < rootLayout.getChildCount(); i++) {
            View childView = rootLayout.getChildAt(i);
            if (childView instanceof UVCCameraTextureView) {
                mCameraViews.add((UVCCameraTextureView) childView);
            }
        }

        mCameraHandlers = new UVCCameraHandler[mCameraViews.size()];
        for (int i = 0; i < mCameraViews.size(); i++) {
            UVCCameraTextureView cameraView = mCameraViews.get(i);
            cameraView.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (double) UVCCamera.DEFAULT_PREVIEW_HEIGHT);
            cameraView.setOnClickListener(mOnClickListener);
            mCameraHandlers[i] = UVCCameraHandler.createHandler(DetectActivity.this,
                    cameraView, UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT);
        }

        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);

        mImageProcessors = new ImageProcessor[mCameraViews.size()];
        mProcessorRunning = new boolean[mCameraViews.size()];
        for (int i = 0; i < mProcessorRunning.length; i++) {
            mProcessorRunning[i] = false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
    }

    @Override
    protected void onStop() {
        stopPreviews();
        for (UVCCameraHandler handler : mCameraHandlers) {
            handler.close();
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        for (int i = 0; i < mCameraHandlers.length; i++) {
            if (mCameraHandlers[i] != null) {
                mCameraHandlers[i].release();
                mCameraHandlers[i] = null;
            }
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        for (int i = 0; i < mCameraViews.size(); i++) {
            mCameraViews.set(i, null);
        }
        super.onDestroy();
    }

    private void startPreview(final int index) {
        if (index <= -1) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SurfaceTexture st = mCameraViews.get(index).getSurfaceTexture();
                    mCameraHandlers[index].startPreview(new Surface(st));
                    startImageProcessor(index, UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT);
                } catch (final Exception e) {
                    Log.w(getPackageName(), e);
                }
            }
        });
    }

    private void stopPreviews() {
        for (int i = 0; i < mCameraViews.size(); i++) {
            stopPreview(i);
        }
    }

    private void stopPreview(int index) {
        stopImageProcessor(index);
        mCameraHandlers[index].close();
    }

    protected void startImageProcessor(final int index, final int processing_width, final int processing_height) {
        mProcessorRunning[index] = true;
        if (mImageProcessors[index] == null) {
            mImageProcessors[index] = new ImageProcessor(processing_width, processing_height,
                    new MyImageProcessorCallback(index, processing_width, processing_height));
            mImageProcessors[index].start(processing_width, processing_height);
        }
    }

    /**
     * stop image processing
     */
    protected void stopImageProcessor(final int index) {
        if (mImageProcessors[index] != null) {
            mImageProcessors[index].release();
            mImageProcessors[index] = null;
        }
    }

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            for (int i = 0; i < mCameraViews.size(); i++) {
                if (mCameraViews.get(i).hashCode() == view.hashCode()) {
                    mProcessingIndex = i;
                    break;
                }
            }
            if (mProcessingIndex > -1) {
                if (!mCameraHandlers[mProcessingIndex].isOpened()) {
                    CameraDialog.showDialog(DetectActivity.this);
                } else {
                    stopPreview(mProcessingIndex);
                }
                mProcessingIndex = -1;
            }
        }
    };

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener
            = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(DetectActivity.this,
                    "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device,
                              final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            if (mProcessingIndex == -1) {
                return;
            }
            mCameraHandlers[mProcessingIndex].open(ctrlBlock);
            startPreview(mProcessingIndex);
            mProcessingIndex = -1;
        }

        @Override
        public void onDisconnect(final UsbDevice device,
                                 final USBMonitor.UsbControlBlock ctrlBlock) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    stopPreviews();
                }
            }, 0);
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(DetectActivity.this,
                    "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {

        }
    };

    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean b) {

    }

    protected class MyImageProcessorCallback implements ImageProcessor.ImageProcessorCallback {
        private final int index, width, height;

        MyImageProcessorCallback(final int processing_index,
                                 final int processing_width, final int processing_height) {
            index = processing_index;
            width = processing_width;
            height = processing_height;
        }

        @Override
        public void onFrame(final ByteBuffer frame) {
            Log.d(getPackageName(), "frame: " + frame.array().length);
        }

        @Override
        public void onResult(final int type, final float[] result) {
            // do something
        }

    }
}
