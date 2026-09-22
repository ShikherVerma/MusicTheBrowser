// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

// Viewer page bundle, served from chrome-untrusted://view.<uuid>.leo-workspace.
// The page cannot reach the workspace folder itself (its origin holds none of
// the workspace's File System Access grants), and it cannot register a service
// worker (chrome-untrusted origins cannot, and its worker is registered
// browser-side in LeoWorkspaceViewUI), so this bundle is the relay: it carries
// the service worker's READ_FILE requests to the parent workspace frame and
// the parent's replies back to the worker. The bridge contract is in
// message_handler.ts; the URL-space the worker serves is /files/<path>.

import {
  kReadFileRequest,
  kReadFileResponse,
  workspaceOrigin,
} from './message_handler'

// Forwards worker↔parent messages:
// - a READ_FILE request arriving from the service worker goes to the parent
//   frame, addressed to its origin;
// - a READ_FILE reply arriving from the parent frame (checked against its
//   origin) goes back to the worker that is controlling this page.
export function installParentRelay(
  parentOrigin = workspaceOrigin(window.location.origin),
) {
  if (!parentOrigin) {
    console.error(
      '[leo-workspace-view] no parent origin to forward file reads to',
    )
    return
  }
  if (!navigator.serviceWorker) {
    console.error('[leo-workspace-view] navigator.serviceWorker is unavailable')
    return
  }

  navigator.serviceWorker.addEventListener('message', (event) => {
    const data = event.data as Record<string, unknown> | null
    if (
      typeof data !== 'object'
      || data === null
      || data.type !== kReadFileRequest
    ) {
      return
    }
    window.parent.postMessage(data, parentOrigin)
  })

  window.addEventListener('message', (event) => {
    if (event.origin !== parentOrigin) {
      return
    }
    const data = event.data as Record<string, unknown> | null
    if (
      typeof data !== 'object'
      || data === null
      || data.type !== kReadFileResponse
    ) {
      return
    }
    navigator.serviceWorker.controller?.postMessage(data)
  })
}

function initialize() {
  console.log('[leo-workspace-view] bundle loaded at', window.location.origin)
  installParentRelay()
}

document.addEventListener('DOMContentLoaded', initialize)
