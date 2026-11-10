package com.vmlive.networktoolkit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;

/** Verifies that Android resolves and renders the actual manifest launcher asset. */
@SuppressWarnings("deprecation")
public final class AppBrandingTest extends InstrumentationTestCase {
    public void testAdaptiveLauncherHasVisibleBlueAndWhiteNetworkMark() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        Drawable icon = context.getApplicationInfo().loadIcon(context.getPackageManager());
        assertTrue("Android 8+ launcher must resolve an adaptive icon", icon instanceof AdaptiveIconDrawable);
        Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 192, 192); icon.draw(new Canvas(bitmap));
        int blue = 0, white = 0;
        for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) {
            int p = bitmap.getPixel(x, y);
            if (Color.alpha(p) < 200) continue;
            if (Color.blue(p) > 160 && Color.red(p) < 100) blue++;
            if (Color.red(p) > 220 && Color.green(p) > 220 && Color.blue(p) > 220) white++;
        }
        assertTrue("Blue background must be visible", blue > 1000);
        assertTrue("White network mark must survive Android masking", white > 150);
        try (FileOutputStream out = new FileOutputStream(new File(context.getCacheDir(), "launcher-preview.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }
}
