// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

// The service worker's half of the file bridge (sw.ts): fetches for
// chrome-untrusted://view.<uuid>.leo-workspace/files/<path> are turned into
// READ_FILE requests (see message_handler.ts) relayed to the parent workspace
// frame, and the replies are matched back to the fetches that asked for them.

import { kReadFileRequest, kReadFileResponse } from './message_handler'

// The URL path under the viewer origin that serves workspace files.
export const kFilesPrefix = '/files/'

export interface ReadFileRequest {
  type: typeof kReadFileRequest
  requestId: string
  path: string
}

// The reply the parent frame sends for a request (see message_handler.ts).
export interface FilePayload {
  requestId: string
  // A File (i.e. a Blob), so arbitrary binary content survives and a large file
  // isn't copied into the message.
  file?: Blob
  error?: string
}

// What the service worker's fetch handler turns into a Response. Kept as a
// plain descriptor rather than a Response so tests don't need one.
export interface FileResponseDescriptor {
  status: number
  contentType: string
  body: Blob | string
}

// Extracts the workspace-relative path from a fetch URL, or null for URLs that
// are not file paths. Percent-encoding is undone so spaces etc. survive; a
// malformed encoding yields null rather than a partially decoded path.
export function filePathFromURL(url: URL): string | null {
  if (!url.pathname.startsWith(kFilesPrefix)) {
    return null
  }
  try {
    const path = decodeURIComponent(url.pathname.slice(kFilesPrefix.length))
    return path === '' ? null : path
  } catch {
    return null
  }
}

// The parent frame's error strings name the failure mode: the fake workspace
// errors carry a DOM-ish name prefix, so it is used to pick a status.
function statusForError(error: string): number {
  if (error.startsWith('NotFoundError')) {
    return 404
  }
  if (error.startsWith('NotAllowedError')) {
    return 403
  }
  return 500
}

const kOctetStream = 'application/octet-stream'

// Matches READ_FILE replies to the fetches that requested them. Replies that
// match no pending request (duplicates, stale ones from an older worker) are
// dropped; a request whose parent never replies keeps its fetch pending.
export class FileGate {
  #pending = new Map<string, (payload: FilePayload) => void>()
  #nextRequestId = 0

  // Sends a READ_FILE request through |send| and resolves with the reply. The
  // request is sent before waiting, and |send| may deliver it to several
  // clients; the first matching reply wins and the rest are dropped.
  request(path: string, send: (request: ReadFileRequest) => void) {
    const requestId = String(this.#nextRequestId++)
    const { promise, resolve } = Promise.withResolvers<FilePayload>()
    this.#pending.set(requestId, resolve)
    send({ type: kReadFileRequest, requestId, path })
    return promise
  }

  // Accepts a reply from the parent frame. Returns whether it matched a
  // pending request.
  onResponse(data: unknown): boolean {
    const payload = data as Partial<FilePayload> & { type?: unknown }
    if (
      payload?.type !== kReadFileResponse
      || typeof payload.requestId !== 'string'
    ) {
      return false
    }
    const resolve = this.#pending.get(payload.requestId)
    if (!resolve) {
      return false
    }
    this.#pending.delete(payload.requestId)
    resolve({
      requestId: payload.requestId,
      file: payload.file,
      error: payload.error,
    })
    return true
  }

  // Maps a reply onto what the fetch handler serves. A reply without a file
  // and without an error is malformed, not a success.
  descriptorFor(payload: FilePayload): FileResponseDescriptor {
    if (payload.error !== undefined) {
      return {
        status: statusForError(payload.error),
        contentType: 'text/plain',
        body: payload.error,
      }
    }
    if (!payload.file) {
      return {
        status: 500,
        contentType: 'text/plain',
        body: 'READ_FILE reply had neither a file nor an error',
      }
    }
    return {
      status: 200,
      contentType: payload.file.type || kOctetStream,
      body: payload.file,
    }
  }
}
