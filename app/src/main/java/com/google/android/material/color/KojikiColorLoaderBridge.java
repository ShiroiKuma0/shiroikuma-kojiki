/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.material.color;

import android.content.Context;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;
import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.util.Map;

/**
 * Fork (白い熊 考直): the one class the fork keeps in Material's package. Material's colour-table
 * creator (it builds an in-memory resource table that overrides colour resources by id) is
 * package-private; this hands it to customui/KojikiPalette.
 */
@Keep
public final class KojikiColorLoaderBridge {
    private KojikiColorLoaderBridge() {}

    /** A loader that gives every colour resource id in the map its new ARGB value, or null. */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.R)
    public static ResourcesLoader create(Context context, Map<Integer, Integer> colors) {
        return ColorResourcesLoaderCreator.create(context, colors);
    }
}
