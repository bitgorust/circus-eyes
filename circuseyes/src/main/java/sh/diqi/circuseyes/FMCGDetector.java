package sh.diqi.circuseyes;

import android.graphics.Bitmap;
import android.util.Log;
import android.util.Pair;

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

import java.net.SecureCacheResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by zengjing on 2017/12/23.
 */

public class FMCGDetector {

    public static enum BG {
        GREEN,
        RED,
        BLUE,
        BLACK,
        GRAY,
        WHITE;

        private BG() {

        }
    }

    public interface DetectCallback {
        public void onFrame(final ByteBuffer frame);
        public void onResult(final List<Pair<String, Double>> results);
    }

    private static final String TAG = FMCGDetector.class.getSimpleName();
    private static double THRESHOLD_THRESH = 128;
    private static double THRESHOLD_MAXVAL = 255;
    private static int THRESHOLD_TYPE = 1;
    private static double KERNEL_WIDTH = 8;
    private static double KERNEL_HEIGHT = 3;
    private static int DILATE_ITERATIONS = 2;

    private int mImageWidth;
    private int mImageHeight;
    private BG mImageBgColor;
    private Bitmap mBitmap;

    private DetectCallback mDetectCallback;

    public FMCGDetector(final int width, final int height, final BG color) {
        mImageWidth = width;
        mImageHeight = height;
        mImageBgColor = color;
    }

    public FMCGDetector(final int width, final int height, final BG color, final DetectCallback callback) throws NullPointerException {
        this(width, height, color);
        if (callback == null) {
            throw new NullPointerException("callback should not be null");
        }
        mDetectCallback = callback;
        mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
    }

    public void analyze(final ByteBuffer byteBuffer) {

    }

    public void analyze(final ByteBuffer byteBuffer, final DetectCallback callback) throws NullPointerException {
        if (callback == null) {
            throw new NullPointerException("callback should not be null");
        }
        Bitmap bitmap = Bitmap.createBitmap(mImageWidth, mImageHeight, Bitmap.Config.RGB_565);
//        byteBuffer.clear();
        bitmap.copyPixelsFromBuffer(byteBuffer);
        bitmap = analyze(bitmap);
        bitmap.copyPixelsToBuffer(byteBuffer);
        callback.onFrame(byteBuffer);
        bitmap.recycle();
    }

    public synchronized Bitmap analyze(Bitmap bitmap) {
        long start = System.currentTimeMillis();
        Mat image = new Mat();
        Utils.bitmapToMat(bitmap, image);
        Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2HSV);
        Mat mask = new Mat();
        Pair<Scalar, Scalar> bounding = getBounding(mImageBgColor);
        Core.inRange(image, bounding.first, bounding.second, mask);
        Imgproc.threshold(mask, mask, THRESHOLD_THRESH, THRESHOLD_MAXVAL, THRESHOLD_TYPE);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_ELLIPSE, new Size(KERNEL_WIDTH, KERNEL_HEIGHT));
        Imgproc.dilate(mask, mask, kernel, new Point(), DILATE_ITERATIONS);
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(mask, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
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
        Log.d(TAG, (System.currentTimeMillis() - start) / 1000 + " ms taken.");
        return bitmap;
    }

    public void release() {
        if (mBitmap != null && !mBitmap.isRecycled()) {
            mBitmap.recycle();
        }
    }

    private Pair<Scalar, Scalar> getBounding(final BG color) {
        switch (color) {
            case BLACK:
                return new Pair<>(new Scalar(0, 0, 0), new Scalar(180, 255, 46));
            case GRAY:
                return new Pair<>(new Scalar(0, 0, 46), new Scalar(180, 43, 220));
            case WHITE:
                return new Pair<>(new Scalar(0, 0, 221), new Scalar(180, 30, 255));
            case BLUE:
                return new Pair<>(new Scalar(100, 43, 46), new Scalar(124, 255, 255));
            case RED:
                return new Pair<>(new Scalar(0, 43, 46), new Scalar(10, 255, 255));
            case GREEN:
            default:
                return new Pair<>(new Scalar(35, 43, 46), new Scalar(99, 255, 255));
        }
    }

    private boolean isInside(Rect a, Rect b) {
        if (a.x >= b.x && a.x <= b.x + b.width && a.x + a.width >= b.x && a.x + a.width <= b.x + b.width && a.y >= b.y && a.y <= b.y + b.height && a.y + a.height >= b.y && a.y + a.height <= b.y + b.height) {
            return true;
        }
        int aCenterX = a.x + a.width / 2;
        int aCenterY = a.y + a.height / 2;
        return aCenterX >= b.x && aCenterX <= b.x + b.width && aCenterY >= b.y && aCenterY <= b.y + b.height;
    }

    private static boolean isInit;
    static {
        if (!isInit) {
            OpenCVLoader.initDebug();
            isInit = true;
        }
    }
}
