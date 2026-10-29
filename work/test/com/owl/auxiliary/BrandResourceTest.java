package com.owl.auxiliary;

import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;

/** 在 ART 中从 APK 实际资源路径解码，检查透明度及内存尺寸。 */
public final class BrandResourceTest {
    public static void main(String[] args) {
        ContextWrapper context = new ContextWrapper(null) {
            @Override
            public Resources getResources() { return Resources.getSystem(); }
        };
        Bitmap bitmap = ((BitmapDrawable) Brand.logo(context)).getBitmap();
        require(bitmap.getWidth() > 0 && bitmap.getWidth() <= 512, "图标应按小尺寸解码");
        require(bitmap.hasAlpha(), "生成图标必须保留透明通道");
        require((bitmap.getPixel(0, 0) >>> 24) == 0, "图标外部不能有白底");
        System.out.println("BRAND_RESOURCE_PASS: " + Brand.NAME + " "
                + bitmap.getWidth() + "x" + bitmap.getHeight() + " transparent");
    }

    private static void require(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }
}
