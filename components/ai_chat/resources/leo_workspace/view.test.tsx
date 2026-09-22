// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import { installParentRelay } from './view'
import {
  kReadFileRequest,
  kReadFileResponse,
  workspaceOrigin,
} from './message_handler'

// The page's own origin, and the workspace frame that embeds it.
const kViewerOrigin = 'chrome-untrusted://view.a-uuid.leo-workspace'
const kParentOrigin = 'chrome-untrusted://a-uuid.leo-workspace'

let serviceWorkerListeners: Map<string, EventListener>
let controller: { postMessage: jest.Mock }
let windowListeners: Map<string, EventListener>

/**
 * Stands in for navigator.serviceWorker, capturing the relay's listeners; one
 * is added per message type, and a leaked one would answer later tests.
 */
function install() {
  windowListeners = new Map()
  const addWindowListener = jest.spyOn(window, 'addEventListener')
  installParentRelay(kParentOrigin)
  for (const [type, listener] of addWindowListener.mock.calls) {
    if (!windowListeners.has(type)) {
      windowListeners.set(type, listener as EventListener)
    }
  }
  addWindowListener.mockRestore()
}

/**
 * Installs the relay over a mocked service worker container.
 */
function installWithServiceWorker() {
  serviceWorkerListeners = new Map()
  controller = { postMessage: jest.fn() }
  const container = {
    addEventListener: jest.fn((type: string, listener: EventListener) => {
      if (!serviceWorkerListeners.has(type)) {
        serviceWorkerListeners.set(type, listener)
      }
    }),
    controller,
  }
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: container,
  })
  install()
}

afterEach(() => {
  windowListeners?.get('message')
    && window.removeEventListener('message', windowListeners.get('message')!)
  delete (navigator as { serviceWorker?: unknown }).serviceWorker
})

/** Dispatches a message as if it came from |origin|. */
function postWindowMessage(data: unknown, origin: string) {
  windowListeners.get('message')!(new MessageEvent('message', { data, origin }))
}

function postWorkerMessage(data: unknown) {
  serviceWorkerListeners.get('message')!(
    new MessageEvent('message', { data, origin: kViewerOrigin }),
  )
}

describe('installParentRelay', () => {
  beforeEach(() => {
    jest.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('forwards a worker request to the parent frame by its origin', () => {
    installWithServiceWorker()
    const post = jest.spyOn(window.parent, 'postMessage')
    const request = { type: kReadFileRequest, requestId: '0', path: 'a.txt' }
    postWorkerMessage(request)
    expect(post.mock.calls).toEqual([[request, kParentOrigin]])
  })

  it('drops a worker message that is not a READ_FILE request', () => {
    installWithServiceWorker()
    const post = jest.spyOn(window.parent, 'postMessage')
    postWorkerMessage({ type: kReadFileResponse, requestId: '0', file: {} })
    expect(post).not.toHaveBeenCalled()
  })

  it('forwards a parent reply to the controlling worker', () => {
    installWithServiceWorker()
    const reply = { type: kReadFileResponse, requestId: '0', file: {} }
    postWindowMessage(reply, kParentOrigin)
    expect(controller.postMessage.mock.calls).toEqual([[reply]])
  })

  it('drops a reply from a non-parent origin', () => {
    installWithServiceWorker()
    postWindowMessage(
      { type: kReadFileResponse, requestId: '0', file: {} },
      'chrome-untrusted://view.b-uuid.leo-workspace',
    )
    expect(controller.postMessage).not.toHaveBeenCalled()
  })

  it('drops a parent message that is not a READ_FILE reply', () => {
    installWithServiceWorker()
    postWindowMessage({ type: kReadFileRequest, path: 'a.txt' }, kParentOrigin)
    expect(controller.postMessage).not.toHaveBeenCalled()
  })

  it('tolerates a reply while no worker controls the page', () => {
    installWithServiceWorker()
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: { addEventListener: jest.fn() },
    })
    expect(() =>
      postWindowMessage(
        { type: kReadFileResponse, requestId: '0', file: {} },
        kParentOrigin,
      ),
    ).not.toThrow()
  })

  it('installs nothing when the page has no parent workspace', () => {
    // A parent origin is required to forward to; anything else is refused.
    installParentRelay('')
    expect(console.error).toHaveBeenCalledWith(
      expect.stringContaining('no parent origin'),
    )
  })

  it('installs nothing when the service worker container is unavailable', () => {
    install()
    expect(console.error).toHaveBeenCalledWith(
      expect.stringContaining('navigator.serviceWorker is unavailable'),
    )
  })
})

describe('workspaceOrigin', () => {
  it.each([
    [kViewerOrigin, kParentOrigin],
    [
      'chrome-untrusted://view.view.a-uuid.leo-workspace',
      'chrome-untrusted://view.a-uuid.leo-workspace',
    ],
  ])('strips the viewer prefix from %s', (origin, expected) => {
    expect(workspaceOrigin(origin)).toBe(expected)
  })

  it.each([['null'], [''], ['https://view.a-uuid.leo-workspace']])(
    'returns empty for %p, which must not be trusted',
    (origin) => {
      expect(workspaceOrigin(origin)).toBe('')
    },
  )
})
