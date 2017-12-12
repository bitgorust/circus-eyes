package sh.diqi.circuseyes;

import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.widget.UVCCameraTextureView;

import java.util.ArrayList;

/**
 * Created by zengjing on 2017/12/11.
 */

public class MainActivity extends BaseActivity implements CameraDialog.CameraDialogParent {

    /**
     * set 0 if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     * by almost same way as USBCameratest2)
     * set 1 if you want to record movie using MediaVideoEncoder
     */
    private static final int ENCODER_TYPE = 1;

    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 1;

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;

    private USBMonitor usbMonitor;

    private ArrayList<UVCCameraTextureView> cameraViews = new ArrayList<>();
    private ArrayList<UVCCameraHandlerMultiSurface> cameraHandlers = new ArrayList<>();
    private int[] surfaceIds;
    private int processingIndex = -1;

    private final USBMonitor.OnDeviceConnectListener onDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(UsbDevice usbDevice) {
            Toast.makeText(MainActivity.this,
                    "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDettach(UsbDevice usbDevice) {
            Toast.makeText(MainActivity.this,
                    "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock, boolean b) {
            Toast.makeText(MainActivity.this,
                    "USB_DEVICE_CONNECTED", Toast.LENGTH_SHORT).show();
            if (processingIndex == -1) {
                return;
            }
            cameraHandlers.get(processingIndex).open(usbControlBlock);
            startPreview(processingIndex);
            processingIndex = -1;
        }

        @Override
        public void onDisconnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock) {
            Toast.makeText(MainActivity.this,
                    "USB_DEVICE_DISCONNECTED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(UsbDevice usbDevice) {
            Toast.makeText(MainActivity.this,
                    "USB_DEVICE_CANCELED", Toast.LENGTH_SHORT).show();
        }
    };

    private final View.OnClickListener onViewClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Log.d(getPackageName(), "clickedView: " + view.hashCode());
            for (int i = 0; i < cameraViews.size(); i++) {
                Log.d(getPackageName(), "cameraViews.get(" + i + "): " + cameraViews.get(i).hashCode());
                if (cameraViews.get(i).hashCode() == view.hashCode()) {
                    processingIndex = i;
                    break;
                }
            }
            if (processingIndex > -1) {
                if (!cameraHandlers.get(processingIndex).isOpened()) {
                    CameraDialog.showDialog(MainActivity.this);
                } else {
                    stopPreview(processingIndex);
                    processingIndex = -1;
                }
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbMonitor = new USBMonitor(this, onDeviceConnectListener);

        LinearLayout container = findViewById(R.id.textureViewContainer);
        int viewCount = container.getChildCount();
        surfaceIds = new int[viewCount];
        for (int i = 0; i < container.getChildCount(); i++) {
            UVCCameraTextureView cameraView = (UVCCameraTextureView) container.getChildAt(i);
            cameraView.setAspectRatio(PREVIEW_WIDTH / (double) PREVIEW_HEIGHT);
            cameraView.setOnClickListener(onViewClickListener);
            cameraViews.add(cameraView);
            cameraHandlers.add(UVCCameraHandlerMultiSurface.createHandler(this, cameraView,
                    ENCODER_TYPE, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE));
            surfaceIds[i] = 0;
        }

        Button captureButton = findViewById(R.id.buttonCapture);
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                for (UVCCameraHandlerMultiSurface handler : cameraHandlers) {
                    if (handler.isOpened() && checkPermissionWriteExternalStorage()) {
                        handler.captureStill();
                    }
                }
            }
        });
    }

    private void stopPreview(int index) {
        if (index == -1) {
            return;
        }
        UVCCameraHandlerMultiSurface processingHandler = cameraHandlers.get(index);
        if (surfaceIds[index] != 0) {
            processingHandler.removeSurface(surfaceIds[index]);
            surfaceIds[index] = 0;
        }
        processingHandler.close();
    }

    private void startPreview(final int index) {
        cameraHandlers.get(index).startPreview();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SurfaceTexture texture = cameraViews.get(index).getSurfaceTexture();
                    Log.d(getPackageName(), "texture: " + (texture != null ? texture.toString() : "null"));
                    if (texture != null) {
                        Surface surface = new Surface(texture);
                        int surfaceId = surface.hashCode();
                        cameraHandlers.get(index).addSurface(surfaceId, surface, false);
                        surfaceIds[index] = surfaceId;
                    }
                } catch (final Exception e) {
                    Log.e(getPackageName(), e.toString());
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        usbMonitor.register();
    }

    @Override
    protected void onStop() {
        usbMonitor.unregister();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        usbMonitor.destroy();
        for (int i = 0; i < cameraHandlers.size(); i++) {
            cameraHandlers.get(i).release();
            cameraHandlers.set(i, null);
        }
        cameraHandlers.clear();
        for (int i = 0; i < cameraViews.size(); i++) {
            cameraViews.set(i, null);
        }
        cameraViews.clear();
        super.onDestroy();
    }

    @Override
    public USBMonitor getUSBMonitor() {
        return usbMonitor;
    }

    @Override
    public void onDialogResult(boolean b) {

    }
}
