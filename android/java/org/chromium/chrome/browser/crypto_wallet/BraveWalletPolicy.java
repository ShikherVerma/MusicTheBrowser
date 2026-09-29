/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.crypto_wallet;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;

/** Provides policy state for Brave Wallet. */
@NullMarked
public class BraveWalletPolicy {
    /** Returns true if Wallet is disabled by policy for the given profile. */
    public static boolean isDisabledByPolicy(@Nullable Profile profile) {
        // Music Browser: Wallet hidden entirely (native brave_wallet::IsAllowed is false too,
        // so the wallet services must never be bound from Java).
        return true;
    }
}
