// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import { runInNewContext } from 'node:vm'
import {
  FileGate,
  filePathFromURL,
  type FilePayload,
  type ReadFileRequest,
} from './viewer_file_gate'
import { kReadFileRequest, kReadFileResponse } from './message_handler'

// The shared test setup replaces the global decodeURIComponent with a stub that
// always returns 'test' (components/test/testPolyfills.ts). Path parsing is
// exactly about undoing percent-encoding, so put a real one back. Each test
// file gets a fresh jsdom environment, so this stays local to this file.
global.decodeURIComponent = runInNewContext('decodeURIComponent')

describe('filePathFromURL', () => {
  it.each([
    ['/files/notes.txt', 'notes.txt'],
    ['/files/src/main.ts', 'src/main.ts'],
    // Percent-encoding is undone, so spaces and non-ASCII names survive.
    ['/files/a%20b%20-%20na%C3%AFve.txt', 'a b - naïve.txt'],
  ])('reads the file path out of %s', (pathname, expected) => {
    expect(
      filePathFromURL(new URL(`chrome-untrusted://v.example${pathname}`)),
    ).toBe(expected)
  })

  it.each([
    ['/files', 'no trailing slash'],
    ['/files/', 'an empty path'],
    ['/other/notes.txt', 'a non-file path'],
    ['/files/a%ZZ.txt', 'a malformed escape'],
  ])('rejects %s (%s)', (pathname) => {
    expect(
      filePathFromURL(new URL(`chrome-untrusted://v.example${pathname}`)),
    ).toBeNull()
  })
})

describe('FileGate', () => {
  let gate: FileGate
  let sent: ReadFileRequest[]
  let send: (request: ReadFileRequest) => void

  beforeEach(() => {
    gate = new FileGate()
    sent = []
    send = (request) => sent.push(request)
  })

  it('sends a request and completes it with the matching reply', async () => {
    const request = gate.request('notes.txt', send)
    expect(sent).toEqual([
      { type: kReadFileRequest, requestId: '0', path: 'notes.txt' },
    ])

    expect(
      gate.onResponse({
        type: kReadFileResponse,
        requestId: '0',
        file: { name: 'notes.txt' },
      }),
    ).toBe(true)

    expect(await request).toEqual({
      requestId: '0',
      file: { name: 'notes.txt' },
    })
  })

  it('numbers request ids so replies cannot be confused', async () => {
    const first = gate.request('a.txt', send)
    const second = gate.request('b.txt', send)
    expect(sent.map((request) => request.requestId)).toEqual(['0', '1'])
    expect(sent[0].type).toBe(kReadFileRequest)

    gate.onResponse({
      type: kReadFileResponse,
      requestId: '1',
      file: { name: 'b.txt' },
    })
    expect(await second).toEqual({ requestId: '1', file: { name: 'b.txt' } })

    gate.onResponse({
      type: kReadFileResponse,
      requestId: '0',
      error: 'later answer for the first request',
    })
    expect(await first).toEqual({
      requestId: '0',
      error: 'later answer for the first request',
    })
  })

  it('drops replies that match no pending request', async () => {
    expect(gate.onResponse({ type: kReadFileResponse, requestId: '0' })).toBe(
      false,
    )
    const request = gate.request('notes.txt', send)
    // A duplicate or stale reply is ignored, and the request stays pending.
    expect(
      gate.onResponse({
        type: kReadFileResponse,
        requestId: '0',
        file: { name: 'notes.txt' },
      }),
    ).toBe(true)
    expect(
      gate.onResponse({
        type: kReadFileResponse,
        requestId: '0',
        file: { name: 'notes.txt' },
      }),
    ).toBe(false)
    expect(sent).toHaveLength(1)
    await expect(request).resolves.toMatchObject({
      file: { name: 'notes.txt' },
    })
  })

  it.each([[null], [undefined], ['READ_FILE'], [{ path: 'notes.txt' }]])(
    'drops the unrecognised payload %p',
    (data) => {
      expect(gate.onResponse(data)).toBe(false)
    },
  )

  describe('descriptorFor', () => {
    it('serves a file with its own media type', () => {
      const payload: FilePayload = {
        requestId: '0',
        file: { type: 'image/png' } as unknown as Blob,
      }
      expect(gate.descriptorFor(payload)).toEqual({
        status: 200,
        contentType: 'image/png',
        body: payload.file,
      })
    })

    it('falls back to an opaque media type for an untyped file', () => {
      const payload: FilePayload = { requestId: '0', file: {} as Blob }
      expect(gate.descriptorFor(payload).contentType).toBe(
        'application/octet-stream',
      )
    })

    it('serves parent frame errors with a matching status', () => {
      expect(
        gate.descriptorFor({
          requestId: '0',
          error: 'NotFoundError: nope.txt',
        }),
      ).toEqual({
        status: 404,
        contentType: 'text/plain',
        body: 'NotFoundError: nope.txt',
      })
      expect(
        gate.descriptorFor({
          requestId: '0',
          error: 'NotAllowedError: denied',
        }).status,
      ).toBe(403)
      expect(
        gate.descriptorFor({ requestId: '0', error: 'kaboom' }).status,
      ).toBe(500)
    })

    it('treats a reply with neither a file nor an error as malformed', () => {
      expect(gate.descriptorFor({ requestId: '0' })).toEqual({
        status: 500,
        contentType: 'text/plain',
        body: expect.stringContaining('neither a file nor an error'),
      })
    })
  })
})
