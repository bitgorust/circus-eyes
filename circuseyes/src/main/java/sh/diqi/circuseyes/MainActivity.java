package sh.diqi.circuseyes;

import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.widget.UVCCameraTextureView;

import java.util.ArrayList;
import java.util.Locale;

import cn.bingoogolapple.qrcode.core.QRCodeView;

/**
 * Created by zengjing on 2017/12/11.
 */

public class MainActivity extends BaseActivity implements CameraDialog.CameraDialogParent, QRCodeView.Delegate {

    private static final int PREVIEW_WIDTH = 320;
    private static final int PREVIEW_HEIGHT = 240;

    private USBMonitor usbMonitor;

    private ArrayList<TextView> cameraTips;
    private ArrayList<UVCCameraTextureView> cameraViews;
    private int[] surfaceIds;
    private UVCCameraHandlerMultiSurface[] cameraHandlers;

    private EditText barcodeText;
    private ArrayList<TextView> deviceTexts;
    private ArrayList<TextView> captureTexts;

    private int processingIndex = -1;

    private QRCodeView mQRCodeView;

    private final USBMonitor.OnDeviceConnectListener onDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(UsbDevice usbDevice) {
            Toast.makeText(MainActivity.this,
                    usbDevice.getDeviceName() + "已接上", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDettach(UsbDevice usbDevice) {
            Toast.makeText(MainActivity.this,
                    usbDevice.getDeviceName() + "已脱离", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock, boolean b) {
            Toast.makeText(MainActivity.this,
                    "正在连接：" + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
            if (processingIndex == -1) {
                return;
            }
            cameraHandlers[processingIndex].open(usbControlBlock);
            startPreview(processingIndex);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    deviceTexts.get(processingIndex).setText(usbDevice.getDeviceName());
                    captureTexts.get(processingIndex).setText("预览中");
                    processingIndex = -1;
                }
            });
        }

        @Override
        public void onDisconnect(UsbDevice usbDevice, USBMonitor.UsbControlBlock usbControlBlock) {
            Toast.makeText(MainActivity.this,
                    "断开连接：" + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(UsbDevice usbDevice) {
            Toast.makeText(MainActivity.this,
                    "取消连接：" + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
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
                if (!cameraHandlers[processingIndex].isOpened()) {
                    CameraDialog.showDialog(MainActivity.this);
                } else {
                    stopPreview(processingIndex);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deviceTexts.get(processingIndex).setText("未连接");
                            captureTexts.get(processingIndex).setText("不可用");
                            processingIndex = -1;
                        }
                    });
                }
            }
        }
    };

    @Override
    public void onScanQRCodeSuccess(String result) {
        Toast.makeText(this, "条码识别成功", Toast.LENGTH_SHORT).show();
        barcodeText.setText(result);
    }

    @Override
    public void onScanQRCodeOpenCameraError() {
        Toast.makeText(this, "打开相机出错", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbMonitor = new USBMonitor(this, onDeviceConnectListener);

        barcodeText = findViewById(R.id.barcode);

        deviceTexts = getDeviceTexts();
        captureTexts = getCaptureTexts();

        cameraTips = getCameraTips();
        cameraViews = getCameraViews();
        surfaceIds = new int[cameraViews.size()];
        cameraHandlers = new UVCCameraHandlerMultiSurface[cameraViews.size()];

        final TextView bandwidthFactorValue = findViewById(R.id.bandwidthFactorValue);
        final SeekBar bandwidthFactorSlider = findViewById(R.id.bandwidthFactorSlider);
        bandwidthFactorSlider.incrementProgressBy(5);
        bandwidthFactorSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                progress /= 5;
                progress *= 5;
                bandwidthFactorValue.setText(String.format(Locale.CHINESE, "0.%d", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        final Button bandwidthButton = findViewById(R.id.bandwidthConfirm);
        bandwidthButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                float factor = Float.parseFloat(bandwidthFactorValue.getText().toString());
                for (int i = 0; i < cameraViews.size(); i++) {
                    final UVCCameraTextureView cameraView = cameraViews.get(i);
                    cameraView.setAspectRatio(PREVIEW_WIDTH / (double) PREVIEW_HEIGHT);
                    cameraView.setOnClickListener(onViewClickListener);
                    cameraTips.get(i).setText(R.string.choose);
                    final UVCCameraHandlerMultiSurface cameraHandler =
                            UVCCameraHandlerMultiSurface.createHandler(MainActivity.this, cameraView,
                                    PREVIEW_WIDTH, PREVIEW_HEIGHT, factor);
                    cameraHandlers[i] = cameraHandler;
                    surfaceIds[i] = 0;
                }
                bandwidthButton.setEnabled(false);
            }
        });

        Button captureButton = findViewById(R.id.buttonCapture);
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                for (int i = 0; i < cameraHandlers.length; i++) {
                    UVCCameraHandlerMultiSurface handler = cameraHandlers[i];
                    if (handler != null && handler.isOpened() && checkPermissionWriteExternalStorage()) {
                        handler.captureStill();
                        captureTexts.get(i).setText("存照中");
                    }
                }
            }
        });

        mQRCodeView = findViewById(R.id.zxingview);
        mQRCodeView.setDelegate(this);

        findViewById(R.id.detect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, FrameActivity.class));
            }
        });
    }

    private void stopPreview(int index) {
        if (index == -1) {
            return;
        }
        UVCCameraHandlerMultiSurface processingHandler = cameraHandlers[index];
        if (surfaceIds[index] != 0) {
            processingHandler.removeSurface(surfaceIds[index]);
            surfaceIds[index] = 0;
        }
        processingHandler.close();
    }

    private void startPreview(final int index) {
        cameraHandlers[index].startPreview();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SurfaceTexture texture = cameraViews.get(index).getSurfaceTexture();
                    Log.d(getPackageName(), "texture: " + (texture != null ? texture.toString() : "null"));
                    if (texture != null) {
                        Surface surface = new Surface(texture);
                        int surfaceId = surface.hashCode();
                        cameraHandlers[index].addSurface(surfaceId, surface, false);
                        surfaceIds[index] = surfaceId;
                    }
                } catch (final Exception e) {
                    Log.e(getPackageName(), e.toString());
                }
            }
        });
    }

    private ArrayList<TextView> getCameraTips() {
        ArrayList<TextView> views = new ArrayList<>();
        RelativeLayout container = findViewById(R.id.cameraTips);
        for (int i = 0; i < container.getChildCount(); i++) {
            views.add((TextView) container.getChildAt(i));
        }
        return views;
    }

    private ArrayList<UVCCameraTextureView> getCameraViews() {
        ArrayList<UVCCameraTextureView> views = new ArrayList<>();
        RelativeLayout container = findViewById(R.id.textureViewContainer);
        for (int i = 0; i < container.getChildCount(); i++) {
            views.add((UVCCameraTextureView) container.getChildAt(i));
        }
        return views;
    }

    private ArrayList<TextView> getDeviceTexts() {
        ArrayList<TextView> textViews = new ArrayList<>();
        LinearLayout devices1 = findViewById(R.id.devices1);
        for (int i = 0; i < devices1.getChildCount(); i++) {
            textViews.add((TextView) devices1.getChildAt(i));
        }
        LinearLayout devices2 = findViewById(R.id.devices2);
        for (int i = 0; i < devices2.getChildCount(); i++) {
            textViews.add((TextView) devices2.getChildAt(i));
        }
        return textViews;
    }

    private ArrayList<TextView> getCaptureTexts() {
        ArrayList<TextView> textViews = new ArrayList<>();
        LinearLayout capture1 = findViewById(R.id.capture1);
        for (int i = 0; i < capture1.getChildCount(); i++) {
            textViews.add((TextView) capture1.getChildAt(i));
        }
        LinearLayout capture2 = findViewById(R.id.capture2);
        for (int i = 0; i < capture2.getChildCount(); i++) {
            textViews.add((TextView) capture2.getChildAt(i));
        }
        return textViews;
    }

    @Override
    protected void onStart() {
        super.onStart();
        usbMonitor.register();
        mQRCodeView.startCamera();
        mQRCodeView.startSpotAndShowRect();
    }

    @Override
    protected void onStop() {
        mQRCodeView.stopSpotAndHiddenRect();
        mQRCodeView.stopCamera();
        usbMonitor.unregister();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mQRCodeView.onDestroy();
        usbMonitor.destroy();
        for (int i = 0; i < cameraHandlers.length; i++) {
            if (cameraHandlers[i] != null) {
                cameraHandlers[i].release();
                cameraHandlers[i] = null;
            }
        }
        cameraHandlers = null;
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
