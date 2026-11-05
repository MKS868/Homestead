/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Art painted into a circle or a rounded rectangle with smooth edges. A view clipped to a
 * round outline is cut without anti-aliasing on Fire OS's Android versions, which left the
 * round icons' edges stepped; painting through a shader with an anti-aliased paint does not.
 */
final class RoundArt extends Drawable {

    /** The corner radius that draws a circle (an oval in a non-square box). */
    static final float OVAL = -1f;

    private final Bitmap art;
    private final float radius;
    private final boolean stretch;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF box = new RectF();

    /**
     * @param radius corner radius in px, or OVAL
     * @param stretch fill the box exactly, as ImageView's FIT_XY; else cover it, centred,
     *        cropping what is left over, as CENTER_CROP
     */
    RoundArt(Bitmap art, float radius, boolean stretch) {
        this.art = art;
        this.radius = radius;
        this.stretch = stretch;
        paint.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    @Override protected void onBoundsChange(Rect b) {
        box.set(b);
        float sx = b.width() / (float) art.getWidth();
        float sy = b.height() / (float) art.getHeight();
        if (stretch) {
            matrix.setScale(sx, sy);
            matrix.postTranslate(b.left, b.top);
        } else {
            float s = Math.max(sx, sy);
            matrix.setScale(s, s);
            matrix.postTranslate(b.left + (b.width() - art.getWidth() * s) / 2f,
                    b.top + (b.height() - art.getHeight() * s) / 2f);
        }
        paint.getShader().setLocalMatrix(matrix);
    }

    @Override public void draw(Canvas c) {
        if (radius == OVAL) c.drawOval(box, paint);
        else c.drawRoundRect(box, radius, radius, paint);
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }

    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public int getIntrinsicWidth() { return art.getWidth(); }

    @Override public int getIntrinsicHeight() { return art.getHeight(); }
}
