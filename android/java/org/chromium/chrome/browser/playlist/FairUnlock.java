/* Copyright 2026 The Music Browser Authors. MPL-2.0. */
package org.chromium.chrome.browser.playlist;

import android.app.Activity;
import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

import java.util.Collections;
import java.util.List;

/** Music Browser: one non-consumable, using only Google Play, never Brave's receipt server. */
public final class FairUnlock {
    public static final String PRODUCT_ID = "fair_music_browser_unlock";
    private static final String CACHE_KEY = "fair.unlimited_songs";
    private static final String REFUSAL_KEY = "fair.last_unlock_refusal";
    private static FairUnlock sInstance;
    private final BillingClient mClient;
    private final MutableLiveData<Integer> mChanges = new MutableLiveData<>(0);
    private ProductDetails mProduct;
    private boolean mUnlocked;
    private boolean mJustUnlocked;
    private boolean mConnecting;
    private boolean mRestoreRequested;
    private boolean mBusy;
    private String mError;

    private FairUnlock(Context context) {
        mUnlocked = ChromeSharedPreferences.getInstance().readBoolean(CACHE_KEY, false);
        mClient = BillingClient.newBuilder(context.getApplicationContext())
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .setListener((result, purchases) -> {
                    mBusy = false;
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                            && purchases != null) {
                        acceptPurchases(purchases, false);
                    } else if (result.getResponseCode()
                            != BillingClient.BillingResponseCode.USER_CANCELED) {
                        mError = "The purchase couldn't be completed. Try again later.";
                    }
                    changed();
                }).build();
        connect();
    }

    public static FairUnlock get(Context context) {
        if (sInstance == null) sInstance = new FairUnlock(context);
        return sInstance;
    }

    public boolean isUnlocked() { return mUnlocked; }
    public boolean hasUnlockNotice() { return mJustUnlocked; }
    public void clearUnlockNotice() { mJustUnlocked = false; }
    public boolean isBusy() { return mBusy || mConnecting; }
    public String error() { return mError; }
    public LiveData<Integer> changes() { return mChanges; }
    public String price() {
        if (mProduct == null || mProduct.getOneTimePurchaseOfferDetails() == null) return null;
        return mProduct.getOneTimePurchaseOfferDetails().getFormattedPrice();
    }
    private void changed() { mChanges.setValue(mChanges.getValue() + 1); }

    public void connect() {
        if (mConnecting) return;
        if (mClient.isReady()) {
            queryPrice();
            queryOwned();
            return;
        }
        mConnecting = true;
        mError = null;
        changed();
        mClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                mConnecting = false;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryPrice();
                    queryOwned();
                } else {
                    mError = "Can't reach Google Play. Try again later.";
                    mBusy = false;
                }
                changed();
            }

            @Override
            public void onBillingServiceDisconnected() {
                mConnecting = false;
                mBusy = false;
                mError = "Can't reach Google Play. Try again later.";
                // Keep the cached entitlement offline. An explicit retry reconnects.
                changed();
            }
        });
    }

    private void queryPrice() {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build();
        mClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product)).build(), (result, details) -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        for (ProductDetails found : details.getProductDetailsList()) {
                            if (PRODUCT_ID.equals(found.getProductId())) mProduct = found;
                        }
                    }
                    if (price() == null) mError = "Can't reach Google Play. Try again later.";
                    changed();
                });
    }

    private void queryOwned() {
        mClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build(), (result, purchases) -> {
                    mBusy = false;
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        acceptPurchases(purchases, true);
                        if (mRestoreRequested && !mUnlocked) {
                            mError = "No purchase found for this Google account.";
                        }
                    } else if (mRestoreRequested) {
                        mError = "Can't restore purchases right now. Try again later.";
                    }
                    mRestoreRequested = false;
                    changed();
                });
    }

    private void acceptPurchases(List<Purchase> purchases, boolean fullQuery) {
        boolean found = false;
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(PRODUCT_ID)) continue;
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                mError = "Purchase awaiting approval. Your songs are still available.";
                continue;
            }
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            found = true;
            if (!purchase.isAcknowledged()) {
                mClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken()).build(), result -> {
                            // A failed acknowledgement is retried on the next owned-purchase query.
                            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                                mError = "Purchase saved. Reopen the app online to finish confirmation.";
                                changed();
                            }
                        });
            }
        }
        if (found || fullQuery) {
            if (found && !mUnlocked) mJustUnlocked = true;
            mUnlocked = found;
            ChromeSharedPreferences.getInstance().writeBoolean(CACHE_KEY, mUnlocked);
        }
    }

    public void purchase(Activity activity) {
        if (mProduct == null || isBusy() || mUnlocked) return;
        mBusy = true;
        mError = null;
        BillingFlowParams.ProductDetailsParams.Builder productBuilder =
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(mProduct);
        var offer = mProduct.getOneTimePurchaseOfferDetails();
        if (offer != null && offer.getOfferToken() != null) {
            productBuilder.setOfferToken(offer.getOfferToken());
        }
        BillingFlowParams.ProductDetailsParams product = productBuilder.build();
        BillingResult result = mClient.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(product)).build());
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            mBusy = false;
            mError = "The purchase couldn't be opened. Try again later.";
        }
        changed();
    }

    public void restore() {
        mRestoreRequested = true;
        mBusy = true;
        mError = null;
        connect();
    }

    public boolean shouldPresentAfterRefusedSave() {
        if (mUnlocked) return false;
        long now = System.currentTimeMillis();
        long last = ChromeSharedPreferences.getInstance().readLong(REFUSAL_KEY, 0);
        if (last != 0 && now - last < 24L * 60 * 60 * 1000) return false;
        ChromeSharedPreferences.getInstance().writeLong(REFUSAL_KEY, now);
        return true;
    }
}
