//
//  ContentView.swift
//  SlackOff
//
//  Created by mac on 2026/9/4.
//

import SwiftUI

struct ContentView: View {
    static let title = "SlackOff"
    static let subtitle = "SwiftUI starter project"

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "cup.and.saucer.fill")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
            Text(Self.title)
                .font(.largeTitle.bold())
            Text(Self.subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
