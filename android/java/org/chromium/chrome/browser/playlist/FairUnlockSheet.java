/* Copyright 2026 The Music Browser Authors. MPL-2.0. */
package org.chromium.chrome.browser.playlist;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.lifecycle.Observer;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.chromium.chrome.R;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;

/** Music Browser: the explicit About path is always available; only auto-refusals are throttled. */
public final class FairUnlockSheet {
    private static WeakReference<BottomSheetDialog> sOpen = new WeakReference<>(null);

    public static void show(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            current = ((ContextWrapper) current).getBaseContext();
        }
        if (!(current instanceof Activity)) return;
        Activity activity = (Activity) current;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        FairUnlock unlock = FairUnlock.get(activity);
        if (unlock.isUnlocked()) return;
        BottomSheetDialog previous = sOpen.get();
        if (previous != null && previous.isShowing()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(R.layout.fair_unlock_sheet);
        Button buy = dialog.findViewById(R.id.fair_unlock_buy);
        Button restore = dialog.findViewById(R.id.fair_unlock_restore);
        TextView count = dialog.findViewById(R.id.fair_unlock_count);
        TextView error = dialog.findViewById(R.id.fair_unlock_error);
        FairSaveCount saves = FairSaveCount.get(activity);
        Runnable render = () -> {
            if (unlock.isUnlocked()) { dialog.dismiss(); return; }
            NumberFormat format = NumberFormat.getIntegerInstance();
            count.setText(activity.getString(R.string.fair_free_songs,
                    format.format(saves.count()), format.format(saves.limit())));
            String price = unlock.price();
            buy.setText(price == null
                    ? (unlock.isBusy() ? activity.getString(R.string.fair_loading_price)
                                       : activity.getString(R.string.fair_price_unavailable))
                    : activity.getString(R.string.fair_unlock_price, price));
            buy.setEnabled(price != null && !unlock.isBusy());
            buy.setAlpha(buy.isEnabled() ? 1f : 0.5f);
            dialog.findViewById(R.id.fair_unlock_progress).setVisibility(
                    unlock.isBusy() ? View.VISIBLE : View.GONE);
            restore.setEnabled(!unlock.isBusy());
            error.setText(unlock.error());
            error.setVisibility(unlock.error() == null ? View.GONE : View.VISIBLE);
        };
        Observer<Integer> observer = value -> render.run();
        saves.changes().observeForever(observer);
        unlock.changes().observeForever(observer);
        dialog.setOnDismissListener(ignored -> {
            saves.changes().removeObserver(observer);
            unlock.changes().removeObserver(observer);
            sOpen.clear();
        });
        buy.setOnClickListener(view -> unlock.purchase(activity));
        restore.setOnClickListener(view -> unlock.restore());
        dialog.findViewById(R.id.fair_unlock_later).setOnClickListener(view -> dialog.dismiss());
        dialog.show();
        sOpen = new WeakReference<>(dialog);
        View sheet = dialog.findViewById(R.id.design_bottom_sheet);
        if (sheet != null) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(activity.getColor(R.color.container_background));
            float radius = 8 * activity.getResources().getDisplayMetrics().density;
            background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
            sheet.setBackground(background);
        }
        render.run();
        unlock.connect();
    }

    private FairUnlockSheet() {}
}
