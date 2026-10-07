package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Map;

/**
 * OwnBox: overrides the values of app colour resources at runtime (custom accent colour).
 * <p>
 * Lives in Material's package to reuse its package-private {@link ColorResourcesLoaderCreator}, the same
 * ResourcesLoader-based mechanism Material uses for HarmonizedColors / content-based dynamic colour. Theme
 * attributes that reference the overridden {@code @color/...} resources then resolve to the new values.
 * Only works on Android 11 (API 30) and later; callers fall back to the closest preset accent otherwise.
 */
public final class OwnBoxColorOverrides {

    private OwnBoxColorOverrides() {
    }

    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * @param colors colour resource id to ARGB value
     * @return true if the override was installed on {@code context.getResources()}
     */
    public static boolean apply(@NonNull Context context, @NonNull Map<Integer, Integer> colors) {
        if (!isAvailable() || colors.isEmpty()) return false;
        return Api30Impl.apply(context, colors);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static final class Api30Impl {
        private Api30Impl() {
        }

        static boolean apply(@NonNull Context context, @NonNull Map<Integer, Integer> colors) {
            try {
                ResourcesLoader loader = ColorResourcesLoaderCreator.create(context, colors);
                if (loader == null) return false;
                context.getResources().addLoaders(loader);
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
