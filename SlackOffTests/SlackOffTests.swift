//
//  SlackOffTests.swift
//  SlackOffTests
//
//  Created by mac on 2026/9/4.
//

import Testing
@testable import SlackOff

struct SlackOffTests {
    @Test func titleIsNotEmpty() {
        #expect(!ContentView.title.isEmpty)
    }

    @Test func subtitleIsNotEmpty() {
        #expect(!ContentView.subtitle.isEmpty)
    }
}
