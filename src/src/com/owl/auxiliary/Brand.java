package com.owl.auxiliary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.IOException;
import java.io.InputStream;

/** 宿主内使用模块自带图标，避免读取宿主图标或依赖跨包可见性。 */
final class Brand {
    static final String NAME = "重生猫头鹰";
    private static final String LOGO = "/res/drawable-nodpi-v4/reborn_owl.png";
    private static final int LOGO_SAMPLE_SIZE = 4;

    private Brand() {}

    static Drawable logo(Context context) {
        try (InputStream source = Brand.class.getResourceAsStream(LOGO)) {
            if (source == null) { throw new IllegalStateException("模块图标资源缺失"); }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = LOGO_SAMPLE_SIZE;
            Bitmap bitmap = BitmapFactory.decodeStream(source, null, options);
            if (bitmap == null) { throw new IllegalStateException("模块图标解码失败"); }
            return new BitmapDrawable(context.getResources(), bitmap);
        } catch (IOException error) {
            throw new IllegalStateException("模块图标读取失败", error);
        }
    }
}
