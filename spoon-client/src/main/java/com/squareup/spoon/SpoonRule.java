package com.squareup.spoon;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static android.content.Context.MODE_WORLD_READABLE;
import static android.graphics.Bitmap.CompressFormat.PNG;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Environment.getExternalStorageDirectory;
import static com.squareup.spoon.Chmod.chmodPlusR;
import static com.squareup.spoon.Chmod.chmodPlusRWX;
import static com.squareup.spoon.internal.Constants.NAME_SEPARATOR;
import static com.squareup.spoon.internal.Constants.SPOON_SCREENSHOTS;

/**
 * A test rule which captures screenshots and associates them with the test class and test method
 * for Spoon.
 *
 * <pre><code>
 * &#064;Rule public final SpoonRule spoon = new SpoonRule();
 * &#064;Rule public final ActivityTestRule&lt;MyActivity> activityRule = // ...
 *
 * &#064;Test public void methodUnderTest() {
 *   MyActivity activity = activityRule.getActivity();
 *   spoon.screenshot(activity, "start");
 *   // ...
 * }
 * </code></pre>
 */
public final class SpoonRule implements TestRule {
  private static final String EXTENSION = ".png";
  private static final String TAG = "Spoon";
  private static final Pattern TAG_VALIDATION = Pattern.compile("[a-zA-Z0-9_-]+");
  private static final Object LOCK = new Object();

  /** Holds a set of directories that have been cleared for this test */
  private static final Set<String> clearedOutputDirectories = new LinkedHashSet<>();

  private String className;
  private String methodName;

  @Override public Statement apply(Statement base, Description description) {
    className = description.getClassName();
    methodName = description.getMethodName();
    return base; // Pass-through. We're just here to capture the description information.
  }

  public File screenshot(Activity activity, String tag) {
    if (!TAG_VALIDATION.matcher(tag).matches()) {
      throw new IllegalArgumentException("Tag must match " + TAG_VALIDATION.pattern() + ".");
    }
    try {
      File screenshotDirectory =
          obtainScreenshotDirectory(activity.getApplicationContext(), className, methodName);
      String screenshotName = System.currentTimeMillis() + NAME_SEPARATOR + tag + EXTENSION;
      File screenshotFile = new File(screenshotDirectory, screenshotName);
      takeScreenshot(screenshotFile, activity);
      Log.d(TAG, "Captured screenshot '" + tag + "'.");
      return screenshotFile;
    } catch (Exception e) {
      throw new RuntimeException("Unable to capture screenshot.", e);
    }
  }

  private static void takeScreenshot(File file, final Activity activity) throws IOException {
    View view = activity.getWindow().getDecorView();
    if (view.getWidth() == 0 || view.getHeight() == 0) {
      throw new IOException("Your view has no height or width. Are you sure "
          + activity.getClass().getSimpleName()
          + " is the currently displayed activity?");
    }
    final Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), ARGB_8888);

    if (Looper.myLooper() == Looper.getMainLooper()) {
      // On main thread already, Just Do It™.
      drawDecorViewToBitmap(activity, bitmap);
    } else {
      // On a background thread, post to main.
      final CountDownLatch latch = new CountDownLatch(1);
      activity.runOnUiThread(new Runnable() {
        @Override public void run() {
          try {
            drawDecorViewToBitmap(activity, bitmap);
          } finally {
            latch.countDown();
          }
        }
      });
      try {
        latch.await();
      } catch (InterruptedException e) {
        String msg = "Unable to get screenshot " + file.getAbsolutePath();
        Log.e(TAG, msg, e);
        throw new RuntimeException(msg, e);
      }
    }

    OutputStream fos = null;
    try {
      fos = new BufferedOutputStream(new FileOutputStream(file));
      bitmap.compress(PNG, 100 /* quality */, fos);

      chmodPlusR(file);
    } finally {
      bitmap.recycle();
      if (fos != null) {
        fos.close();
      }
    }
  }

  private static void drawDecorViewToBitmap(Activity activity, Bitmap bitmap) {
    Canvas canvas = new Canvas(bitmap);
    activity.getWindow().getDecorView().draw(canvas);
  }

  private static File obtainScreenshotDirectory(Context context, String testClassName,
      String testMethodName) throws IllegalAccessException {
    return filesDirectory(context, SPOON_SCREENSHOTS, testClassName, testMethodName);
  }

  private static File filesDirectory(Context context, String directoryType, String testClassName,
      String testMethodName) throws IllegalAccessException {
    File directory;
    if (SDK_INT >= LOLLIPOP) {
      // Use external storage.
      directory = new File(getExternalStorageDirectory(), "app_" + directoryType);
    } else {
      // Use internal storage.
      directory = context.getDir(directoryType, MODE_WORLD_READABLE);
    }

    synchronized (LOCK) {
      if (!clearedOutputDirectories.contains(directoryType)) {
        deletePath(directory, false);
        clearedOutputDirectories.add(directoryType);
      }
    }

    File dirClass = new File(directory, testClassName);
    File dirMethod = new File(dirClass, testMethodName);
    createDir(dirMethod);
    return dirMethod;
  }

  private static void createDir(File dir) throws IllegalAccessException {
    File parent = dir.getParentFile();
    if (!parent.exists()) {
      createDir(parent);
    }
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IllegalAccessException("Unable to create output dir: " + dir.getAbsolutePath());
    }
    chmodPlusRWX(dir);
  }

  private static void deletePath(File path, boolean inclusive) {
    if (path.isDirectory()) {
      File[] children = path.listFiles();
      if (children != null) {
        for (File child : children) {
          deletePath(child, true);
        }
      }
    }
    if (inclusive) {
      path.delete();
    }
  }
}
