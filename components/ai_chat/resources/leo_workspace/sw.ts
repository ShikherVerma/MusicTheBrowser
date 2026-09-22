// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

// Service worker for the viewer origin
// (chrome-untrusted://view.<uuid>.leo-workspace). It gives viewer documents
// URL-space for the workspace's files: a fetch for /files/<path> is relayed to
// the parent workspace frame over the READ_FILE postMessage bridge
// (message_handler.ts) and answered with the file itself. The relay that
// carries messages between the worker and the parent frame lives in the
// viewer page bundle (view.tsx).
//
// The worker is registered browser-side in LeoWorkspaceViewUI: a
// chrome-untrusted origin cannot register one from JavaScript.

import { filePathFromURL, FileGate } from './viewer_file_gate'

// The DOM lib doesn't cover service workers; declare the minimal surface used.
interface ExtendableEvent {
  waitUntil(promise: Promise<unknown>): void
}
interface FetchEvent {
  request: Request
  respondWith(response: Promise<Response> | Response): void
}
interface WorkerClient {
  postMessage(message: unknown): void
}
interface WorkerClients {
  matchAll(options?: { type?: string }): Promise<WorkerClient[]>
  claim(): Promise<void>
}
interface WorkerScope {
  skipWaiting(): Promise<void>
  clients: WorkerClients
  addEventListener(
    type: 'install' | 'activate',
    listener: (event: ExtendableEvent) => void,
  ): void
  addEventListener(
    type: 'message',
    listener: (event: MessageEvent) => void,
  ): void
  addEventListener(type: 'fetch', listener: (event: FetchEvent) => void): void
}

// The worker global is `self`, but that name belongs to the DOM Window type
// here; alias the worker surface to it.
const scope = self as unknown as WorkerScope

const gate = new FileGate()

scope.addEventListener('install', (event) => {
  event.waitUntil(scope.skipWaiting())
})

scope.addEventListener('activate', (event) => {
  event.waitUntil(scope.clients.claim())
})

scope.addEventListener('message', (event) => {
  gate.onResponse((event as MessageEvent).data)
})

// Sends |request| to every viewer page this worker controls. Several clients
// may exist (history entries, reloads); the gate drops duplicate replies.
function deliver(request: object) {
  void scope.clients.matchAll({ type: 'window' }).then((clients) => {
    for (const client of clients) {
      client.postMessage(request)
    }
  })
}

// The content is arbitrary workspace data, so refuse to sniff it into
// something executable, and sandbox any navigation that lands on it so it runs
// in an opaque origin with scripts disabled.
const kHardeningHeaders = {
  'x-content-type-options': 'nosniff',
  'content-security-policy': 'sandbox',
}

async function serveFile(path: string): Promise<Response> {
  const clients = await scope.clients.matchAll({ type: 'window' })
  if (clients.length === 0) {
    return new Response('no viewer page to ask for the file', {
      status: 503,
      headers: kHardeningHeaders,
    })
  }
  const payload = await gate.request(path, deliver)
  const descriptor = gate.descriptorFor(payload)
  return new Response(descriptor.body, {
    status: descriptor.status,
    headers: {
      'content-type': descriptor.contentType,
      ...kHardeningHeaders,
    },
  })
}

scope.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') {
    return
  }
  const path = filePathFromURL(new URL(event.request.url))
  if (path === null) {
    return
  }
  event.respondWith(serveFile(path))
})
