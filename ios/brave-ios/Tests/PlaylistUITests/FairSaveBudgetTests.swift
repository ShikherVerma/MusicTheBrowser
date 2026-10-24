// Copyright 2026 The Music Browser Authors. MPL-2.0.

import Playlist
import XCTest

final class FairSaveBudgetTests: XCTestCase {
  func testOnlyCreatedSongsConsumeCapacity() {
    var budget = FairSaveBudget(count: 0)
    XCTAssertTrue(budget.reserve(id: "failed", limit: 3, unlocked: false))
    budget.finish(id: "failed", created: false)
    XCTAssertEqual(budget.count, 0)
    for id in ["one", "two", "three"] {
      XCTAssertTrue(budget.reserve(id: id, limit: 3, unlocked: false))
      budget.finish(id: id, created: true)
    }
    XCTAssertEqual(budget.count, 3)
    XCTAssertFalse(budget.reserve(id: "four", limit: 3, unlocked: false))
  }

  func testInFlightSavesReserveTheLastSlot() {
    var budget = FairSaveBudget(count: 2)
    XCTAssertTrue(budget.reserve(id: "third", limit: 3, unlocked: false))
    XCTAssertFalse(budget.reserve(id: "fourth", limit: 3, unlocked: false))
    budget.finish(id: "third", created: false)
    XCTAssertTrue(budget.reserve(id: "fourth", limit: 3, unlocked: false))
  }

  func testRepeatedCallbacksNeverCountTwice() {
    var budget = FairSaveBudget(count: 0)
    XCTAssertTrue(budget.reserve(id: "same", limit: 3, unlocked: false))
    XCTAssertFalse(budget.reserve(id: "same", limit: 3, unlocked: false))
    budget.finish(id: "same", created: true)
    budget.finish(id: "same", created: true)
    XCTAssertEqual(budget.count, 1)
  }

  func testUnlockLiftsTheCountLimitNotDeduplication() {
    var budget = FairSaveBudget(count: 1_000)
    XCTAssertFalse(budget.reserve(id: "new", limit: 1_000, unlocked: false))
    XCTAssertTrue(budget.reserve(id: "new", limit: 1_000, unlocked: true))
    XCTAssertFalse(budget.reserve(id: "new", limit: 1_000, unlocked: true))
    budget.finish(id: "new", created: true)
    XCTAssertEqual(budget.count, 1_001)
  }

  func testLibraryFloorDoesNotReturnCapacity() {
    var budget = FairSaveBudget(count: 1_200)
    XCTAssertEqual(budget.count, 1_200)
    XCTAssertFalse(budget.reserve(id: "after-deletion", limit: 1_000, unlocked: false))
    budget.finish(id: "not-reserved", created: true)
    XCTAssertEqual(budget.count, 1_200)
  }
}
