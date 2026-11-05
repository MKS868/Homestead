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
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * Replacement icons for Amazon system apps whose own are unusable on a TV: none at all (the
 * system then draws Android's robot), Android's template robot, or a 48 px image. Checked on
 * a Fire TV Stick 4K Max, Fire OS 8.1.5.3, by reading each app's resources. Drawn in code,
 * like ShortcutIcons, so they are sharp at any size: a colour gradient with a white line icon.
 */
final class AppArt {

    /** Package, ShortcutIcons glyph, gradient top-left colour, bottom-right colour. */
    private static final Object[][] REPLACED = {
        { "com.amazon.whasettings", "settings", 0xFF4A6FA5, 0xFF223A60 },        // no icon
        { "com.amazon.tv.ftvambient", "sparkle", 0xFF7B5CD6, 0xFF2C2370 },       // no icon
        { "com.amazon.ftv.profilepicker", "account", 0xFFE0823F, 0xFF8A3A16 },   // no icon
        { "com.amazon.gamehub", "gamepad", 0xFF22A97C, 0xFF0D5544 },             // template robot
        { "com.amazon.smarthomemapviewapp", "smarthome", 0xFF1BB4E8, 0xFF0A5788 }, // 48 px
    };

    private static final int GENERIC_TOP = 0xFF5A6675;
    private static final int GENERIC_BOTTOM = 0xFF2A313A;

    private AppArt() { }

    /** The replacement icon for this package, px square, or null when it keeps its own. */
    static Bitmap icon(String pkg, int px) {
        for (int i = 0; i < REPLACED.length; i++) {
            if (REPLACED[i][0].equals(pkg)) {
                return draw((String) REPLACED[i][1], (Integer) REPLACED[i][2],
                        (Integer) REPLACED[i][3], px);
            }
        }
        return null;
    }

    /** For an app that ships no icon at all, instead of Android's robot. */
    static Bitmap generic(int px) {
        return draw("applications", GENERIC_TOP, GENERIC_BOTTOM, px);
    }

    private static Bitmap draw(String glyph, int top, int bottom, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, px, px, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, px, px, bg);
        int g = Math.round(px * 0.56f);
        Bitmap line = ShortcutIcons.render(glyph, g);
        c.drawBitmap(line, (px - g) / 2f, (px - g) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        line.recycle();
        return b;
    }
}
