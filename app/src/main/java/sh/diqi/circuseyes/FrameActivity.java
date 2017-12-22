package sh.diqi.circuseyes;

import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import org.opencv.android.OpenCVLoader;

import java.nio.ByteBuffer;

public class FrameActivity extends BaseActivity implements CameraDialog.CameraDialogParent {

    static {
        OpenCVLoader.initDebug();
    }

    private USBMonitor usbMonitor;

    private SurfaceView surfaceView;
    private ImageView preview;
    private UVCCamera camera;

    private final Bitmap bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565);

    private final Runnable mUpdateImageTask = new Runnable() {
        @Override
        public void run() {
            synchronized (bitmap) {
                preview.setImageBitmap(bitmap);
            }
        }
    };

    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            frame.clear();
            synchronized (bitmap) {
                bitmap.copyPixelsFromBuffer(frame);
            }
            preview.post(mUpdateImageTask);
        }
    };

    private final USBMonitor.OnDeviceConnectListener onDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(UsbDevice usbDevice) {
            Toast.makeText(FrameActivity.this,
                    usbDevice.getDeviceName() + "已接上", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDettach(UsbDevice usbDevice) {
            Toast.makeText(FrameActivity.this,
                    usbDevice.getDeviceName() + "已脱离", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock, boolean b) {
            Toast.makeText(FrameActivity.this,
                    "正在连接：" + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
            if (camera == null) {
                camera = new UVCCamera();
            } else {
                camera.close();
            }
            camera.open(usbControlBlock);
            try {
                camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
            } catch (final IllegalArgumentException e) {
                try {
                    // fallback to YUV mode
                    camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                } catch (final IllegalArgumentException e1) {
                    camera.destroy();
                    return;
                }
            }
            Surface surface = surfaceView.getHolder().getSurface();
            if (surface != null) {
                camera.setPreviewDisplay(surface);
                camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGB565);
                camera.startPreview();
            }
        }

        @Override
        public void onDisconnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock) {
            Toast.makeText(FrameActivity.this,
                    "断开连接：" + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(UsbDevice usbDevice) {
            Toast.makeText(FrameActivity.this,
                    "取消连接：" + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
        }
    };

    private final View.OnClickListener onViewClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            CameraDialog.showDialog(FrameActivity.this);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_frame);

        usbMonitor = new USBMonitor(this, onDeviceConnectListener);

        surfaceView = findViewById(R.id.camera_surface_view);

        preview = findViewById(R.id.preview);
        preview.setOnClickListener(onViewClickListener);
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
        if (camera != null) {
            camera.stopPreview();
            camera.destroy();
        }
        bitmap.recycle();
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
