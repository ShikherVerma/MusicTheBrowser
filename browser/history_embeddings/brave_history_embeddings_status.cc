/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/history_embeddings/brave_history_embeddings_status.h"

#include <memory>

#include "chrome/browser/history_embeddings/history_embeddings_utils.h"
#include "chrome/browser/profiles/profile.h"

namespace history_embeddings {

namespace {

constexpr char kBraveHistoryEmbeddingsStatusKey[] =
    "brave_history_embeddings_status";

}  // namespace

BraveHistoryEmbeddingsStatus::BraveHistoryEmbeddingsStatus(bool enabled)
    : enabled_(enabled) {}

// static
void BraveHistoryEmbeddingsStatus::CreateForProfile(Profile* profile) {
  if (profile->GetUserData(kBraveHistoryEmbeddingsStatusKey)) {
    return;
  }
  // Object cleanup is handled by SupportsUserData
  profile->SetUserData(
      kBraveHistoryEmbeddingsStatusKey,
      std::make_unique<BraveHistoryEmbeddingsStatus>(
          IsHistoryEmbeddingsEnabledForProfile(profile)));
}

// static
BraveHistoryEmbeddingsStatus* BraveHistoryEmbeddingsStatus::GetForProfile(
    Profile* profile) {
  CreateForProfile(profile);
  return static_cast<BraveHistoryEmbeddingsStatus*>(
      profile->GetUserData(kBraveHistoryEmbeddingsStatusKey));
}

bool BraveHistoryEmbeddingsStatus::IsEnabled() const {
  return enabled_;
}

}  // namespace history_embeddings

// Forward declared by the chromium_src overrides that need the captured
// setting, so neither carries a Brave header: the passage embedder gate in
// //chrome/browser/passage_embeddings and brave://history's data source in
// //chrome/browser/ui/webui/history.
bool BraveHistoryEmbeddingsEnabledAtStartup(Profile* profile) {
  return history_embeddings::BraveHistoryEmbeddingsStatus::GetForProfile(
             profile)
      ->IsEnabled();
}
