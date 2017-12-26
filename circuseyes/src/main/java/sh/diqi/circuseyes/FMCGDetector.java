package sh.diqi.circuseyes;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by zengjing on 2017/12/23.
 */

public class FMCGDetector {

    private static final boolean DEBUG = true;

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

    private static final double THRESHOLD_THRESH = 128;
    private static final double THRESHOLD_MAXVAL = 255;
    private static final int THRESHOLD_TYPE = 1;
    private static final double KERNEL_WIDTH = 8;
    private static final double KERNEL_HEIGHT = 3;
    private static final int DILATE_SIZE = 256;
    private static final int DILATE_ITERATIONS = 2;
    private static final float MINIMUM_CONFIDENCE = 0.1f;
    private static final float MINIMUM_ROI_AREA = 2048;
    private static final float MAXIMUM_ROI_AREA = 2048 * 1536;
    private static final int MINIMUM_ROI_NUM = 3;

    private Context mContext;
    private Classifier mDetector;
    private int mImageWidth;
    private int mImageHeight;
    private BG mImageBgColor;

    private int mInputSize = 300;
    private int mSensorOrientation;

    private Bitmap mBitmap;

    private DetectCallback mDetectCallback;

    public FMCGDetector(final Context context, final String modelFile, final String labelFile, final int width, final int height, final BG color) throws IOException {
        mContext = context;
        mImageWidth = width;
        mImageHeight = height;
        mImageBgColor = color;
        mSensorOrientation = 90 - getScreenOrientation(context);
        mInputSize = Math.max(width, height);
        mDetector = TensorFlowObjectDetectionAPIModel.create(context.getAssets(), modelFile, labelFile, mInputSize);
    }

    public FMCGDetector(final Context context, final String modelFile, final String labelFile, final int width, final int height, final BG color, final DetectCallback callback) throws NullPointerException, IOException {
        this(context, modelFile, labelFile, width, height, color);
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
        Mat origin = new Mat();
        Utils.bitmapToMat(bitmap, origin);
        Mat image = new Mat();
        Imgproc.cvtColor(origin, image, Imgproc.COLOR_BGR2HSV);
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
            Imgproc.rectangle(origin, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height),
                    new Scalar(255, 0, 0), 2);
        }
        Utils.matToBitmap(origin, bitmap);
        Log.d(TAG, (System.currentTimeMillis() - start) + " millis taken.");
        return bitmap;
    }

    public Bitmap drawRects(Bitmap bitmap, List<RectF> rects, int red, int green, int blue) {
        Mat origin = new Mat();
        Utils.bitmapToMat(bitmap, origin);
        for (RectF rect : rects) {
            Imgproc.rectangle(origin, new Point(rect.left, rect.top), new Point(rect.right, rect.bottom), new Scalar(red, green, blue), 2);
        }
        Utils.matToBitmap(origin, bitmap);
        return bitmap;
    }

    public List<RectF> getRois(Bitmap bitmap, BG color) {
        long start = System.currentTimeMillis();
        Mat origin = new Mat();
        Utils.bitmapToMat(bitmap, origin);
        Mat image = new Mat();
        Imgproc.cvtColor(origin, image, Imgproc.COLOR_BGR2HSV);
        Mat mask = new Mat();
        Pair<Scalar, Scalar> bounding = getBounding(color);
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

        List<RectF> boxes = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            RectF dilated = new RectF(Math.max(rect.x - DILATE_SIZE, 0),
                    Math.max(rect.y - DILATE_SIZE, 0),
                    Math.min(rect.x + rect.width + DILATE_SIZE, origin.width()),
                    Math.min(rect.y + rect.height + DILATE_SIZE, origin.height()));
            boolean matched = false;
            for (int i = 0; i < boxes.size(); i++) {
                RectF box = boxes.get(i);
                if (box.contains(dilated)) {
                    matched = true;
                    break;
                }
                if (box.contains(dilated.centerX(), dilated.centerY())) {
                    box.set(Math.min(box.left, dilated.left),
                            Math.min(box.top, dilated.top),
                            Math.max(box.right, dilated.right),
                            Math.max(box.bottom, dilated.bottom));
                    boxes.set(i, box);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                boxes.add(new RectF(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height));
            }
        }

        if (boxes.size() == 0) {
            boxes = Collections.singletonList(new RectF(0, 0, origin.width(), origin.height()));
        }

        List<RectF> rois = new ArrayList<>();
        for (RectF box : boxes) {
            if (box.width() * box.height() > MINIMUM_ROI_AREA) {
                rois.add(box);
                if (box.width() * box.height() > MAXIMUM_ROI_AREA) {
                    float halfWidth = (float) Math.floor(box.width() / 2d);
                    float halfHeight = (float) Math.floor(box.height() / 2d);
                    for (float left = 0; left + halfWidth <= box.width(); left += halfWidth) {
                        rois.add(new RectF(box.left + left, box.top, box.left + left + halfWidth, box.bottom));
                    }
                    for (float top = 0; top + halfHeight <= box.height(); top += halfHeight) {
                        rois.add(new RectF(box.left, box.top + top, box.right, box.top + top + halfHeight));
                    }
                    for (float left = 0; left + halfWidth <= box.width(); left += halfWidth) {
                        for (float top = 0; top + halfHeight <= box.height(); top += halfHeight) {
                            rois.add(new RectF(box.left + left, box.top + top, box.left + left + halfWidth, box.top + top + halfHeight));
                        }
                    }
//                    float atomSize = (float) Math.min(Math.floor(origin.width() / 3d), Math.floor(origin.height() / 3d));
//                    float atomStep = (float) Math.floor(atomSize / 2f);
//                    for (float left = 0; left < origin.width(); left += atomStep) {
//                        for (float top = 0; top < origin.height(); top += atomStep) {
//                            rois.add(new RectF(left, top, Math.min(left + atomSize, origin.width()), Math.min(top + atomSize, origin.height())));
//                        }
//                    }
                }
            }
        }

        long spent = System.currentTimeMillis() - start;
        Log.d(TAG, spent + " ms taken to get rects.");

        if (DEBUG) {
            String dirName = md5(bitmap) + "_" + MINIMUM_ROI_AREA;
            File dir = new File(mContext.getExternalFilesDir(null), dirName);
            if (dir.exists() || dir.mkdirs()) {
                for (RectF box : rois) {
                    saveFile(Bitmap.createBitmap(bitmap, Math.round(box.left), Math.round(box.top), Math.round(box.width()), Math.round(box.height())),
                            dirName, "roi_" + spent + "ms_b" + rois.size() + "_" + box.toShortString() + "_" + box.width() + "×" + box.height() + ".jpg");
                }
            }
        }

        boxes.clear();
        return rois;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin) {
        final long startTime = SystemClock.uptimeMillis();
        Bitmap cropped = Bitmap.createBitmap(mInputSize, mInputSize, Bitmap.Config.ARGB_8888);
        Matrix frameToCropTransform =
                getTransformationMatrix(
                        origin.getWidth(), origin.getHeight(),
                        mInputSize, mInputSize,
                        mSensorOrientation, false);
        Matrix cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
        Canvas canvas = new Canvas(cropped);
        canvas.drawBitmap(origin, frameToCropTransform, null);
        final List<Classifier.Recognition> results = new ArrayList<>();
        for (Classifier.Recognition result : mDetector.recognizeImage(cropped)) {
            final RectF location = result.getLocation();
            if (location != null && result.getConfidence() >= MINIMUM_CONFIDENCE) {
                cropToFrameTransform.mapRect(location);
                result.setLocation(location);
                results.add(result);
            }
        }
        Log.d(TAG, (SystemClock.uptimeMillis() - startTime) + " ms taken to recognize bitmap.");
        return results;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin, RectF roi) {
        final long startTime = SystemClock.uptimeMillis();
        Bitmap bitmap = Bitmap.createBitmap(origin, Math.round(roi.left), Math.round(roi.top), Math.round(roi.width()), Math.round(roi.height()));
        final List<Classifier.Recognition> results = recognize(bitmap);
        long spent = SystemClock.uptimeMillis() - startTime;
        Log.d(TAG, spent + " ms taken to analyze roi.");

        if (DEBUG) {
            String dirName = md5(origin) + "_" + MINIMUM_ROI_AREA;
            File dir = new File(mContext.getExternalFilesDir(null), dirName);
            if (dir.exists() || dir.mkdirs()) {
                Mat mat = new Mat();
                Utils.bitmapToMat(bitmap, mat);
                for (Classifier.Recognition result : results) {
                    RectF rect = result.getLocation();
                    Imgproc.rectangle(mat, new Point(rect.left, rect.top), new Point(rect.right, rect.bottom), new Scalar(255, 0, 0), 2);
                }
                Utils.matToBitmap(mat, bitmap);
                saveFile(bitmap, dirName, "rec_" + spent + "ms_" + roi.toShortString() + "_" + roi.width() + "×" + roi.height() + ".jpg");
            }
        }

        for (int i = 0; i < results.size(); i++) {
            Classifier.Recognition result = results.get(i);
            final RectF location = result.getLocation();
            location.offset(roi.left, roi.top);
            result.setLocation(location);
            results.set(i, result);
        }
        return results;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin, List<RectF> rois) {
        for (RectF roi : rois) {
            Log.d(TAG, roi.toString());
        }
        final long startTime = SystemClock.uptimeMillis();
        final List<Classifier.Recognition> results = new ArrayList<>();
        for (RectF roi : rois) {
            results.add(new Classifier.Recognition("r", "roi", 1f, roi));
            results.addAll(recognize(origin, roi));
        }
        Log.d(TAG, (SystemClock.uptimeMillis() - startTime) + " millis taken to recognize bitmap.");
        return results;
    }

    public List<Classifier.Recognition> recognize(Bitmap origin, BG color) {
        return recognize(origin, getRois(origin, color));
    }

    public void close() {
        if (mBitmap != null && !mBitmap.isRecycled()) {
            mBitmap.recycle();
        }
    }

    private Pair<Scalar, Scalar> getBounding(final BG color) {
        switch (color) {
            case BLACK:
                return new Pair<>(new Scalar(0, 0, 0), new Scalar(180, 255, 220));
            case WHITE:
                return new Pair<>(new Scalar(0, 0, 46), new Scalar(180, 43, 255));
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

    private String md5(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        byte[] bitmapBytes = baos.toByteArray();
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            m.update(bitmapBytes, 0, bitmapBytes.length);
            return new BigInteger(1, m.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return String.valueOf(SystemClock.currentThreadTimeMillis() / 1000 / 60);
        }
    }

    private void saveFile(Bitmap bitmap, String dir, String file) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(mContext.getExternalFilesDir(dir), file));
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private int getScreenOrientation(Context context) {
        switch (((Activity) context).getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private Matrix getTransformationMatrix(
            final int srcWidth,
            final int srcHeight,
            final int dstWidth,
            final int dstHeight,
            final int applyRotation,
            final boolean maintainAspectRatio) {
        final Matrix matrix = new Matrix();
        if (applyRotation != 0) {
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f);
            matrix.postRotate(applyRotation);
        }
        final boolean transpose = (Math.abs(applyRotation) + 90) % 180 == 0;
        final int inWidth = transpose ? srcHeight : srcWidth;
        final int inHeight = transpose ? srcWidth : srcHeight;
        if (inWidth != dstWidth || inHeight != dstHeight) {
            final float scaleFactorX = dstWidth / (float) inWidth;
            final float scaleFactorY = dstHeight / (float) inHeight;
            if (maintainAspectRatio) {
                final float scaleFactor = Math.max(scaleFactorX, scaleFactorY);
                matrix.postScale(scaleFactor, scaleFactor);
            } else {
                matrix.postScale(scaleFactorX, scaleFactorY);
            }
        }
        if (applyRotation != 0) {
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f);
        }
        return matrix;
    }

    private static boolean isInit;

    static {
        if (!isInit) {
            OpenCVLoader.initDebug();
            isInit = true;
        }
    }
}
