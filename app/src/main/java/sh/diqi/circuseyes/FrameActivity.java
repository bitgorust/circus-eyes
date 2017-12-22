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
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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

                Mat image = new Mat();
                Utils.bitmapToMat(bitmap, image);
                Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2HSV);
                Mat mask = new Mat();
                Core.inRange(image, new Scalar(35, 43, 46), new Scalar(99, 255, 255), mask);
                Imgproc.threshold(mask, mask, 128, 255, 1);
                Mat kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_ELLIPSE, new Size(8, 3));
                Imgproc.dilate(mask, mask, kernel, new Point(), 2);
                List<MatOfPoint> contours = new ArrayList<>();
                Imgproc.findContours(mask, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                if (contours.size() == 0) {
                    return;
                }

                Collections.sort(contours, new Comparator<MatOfPoint>() {
                    @Override
                    public int compare(MatOfPoint c1, MatOfPoint c2) {
                        double a1 = Imgproc.contourArea(c1);
                        double a2 = Imgproc.contourArea(c2);
                        return a2 > a1 ? 1 : (a2 == a1 ? 0 : -1);
                    }
                });
                List<Rect> boxes = new ArrayList<>();
                for (MatOfPoint contour : contours) {
                    Rect rect = Imgproc.boundingRect(contour);
                    rect = new Rect(rect.x - 100 > 0 ? rect.x - 100 : 0, rect.y - 100 > 0 ? rect.y - 100 : 0,
                            rect.width + 200 < image.width() ? rect.width + 200 : image.width(), rect.height + 200 < image.height() ? rect.height + 200 : image.height());
                    boolean matched = false;
                    for (int i = 0; i < boxes.size(); i++) {
                        Rect box = boxes.get(i);
                        if (isInside(rect, box)) {
                            boxes.set(i, new Rect(box.x < rect.x ? box.x : rect.x, box.y < rect.y ? box.y : rect.y,
                                    box.width > rect.width ? box.width : rect.width, box.height > rect.height ? box.height : rect.height));
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        boxes.add(rect);
                    }
                }
                if (boxes.size() == 0) {
                    return;
                }

                contours.clear();
                for (Rect box : boxes) {
                    contours.add(new MatOfPoint(new Point(box.x, box.y), new Point(box.x + box.width, box.y),
                            new Point(box.x + box.width, box.y + box.height), new Point(box.x, box.y + box.height)));
                }
                for (MatOfPoint contour : contours) {
                    Rect rect = Imgproc.boundingRect(contour);
                    Imgproc.rectangle(image, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height),
                            new Scalar(255, 0, 0), 2);
                }
                Utils.matToBitmap(image, bitmap);
            }
            preview.post(mUpdateImageTask);
        }
    };

    private boolean isInside(Rect a, Rect b) {
        if (a.x >= b.x && a.x <= b.x + b.width && a.x + a.width >= b.x && a.x + a.width <= b.x + b.width && a.y >= b.y && a.y <= b.y + b.height && a.y + a.height >= b.y && a.y + a.height <= b.y + b.height) {
            return true;
        }
        int aCenterX = a.x + a.width / 2;
        int aCenterY = a.y + a.height / 2;
        return aCenterX >= b.x && aCenterX <= b.x + b.width && aCenterY >= b.y && aCenterY <= b.y + b.height;
    }

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
