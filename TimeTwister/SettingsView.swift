//
//  SettingsView.swift
//  TimeTwister
//
//  Created by dumbspacecookie on 4/23/26.
//

import SwiftUI
import TimeTwisterCore

struct SettingsView: View {
    @State private var selected: [TimeZone] = UserPreferences.shared.targetZones
    @State private var showingPicker = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("TimeTwister converts time references like \"5pm CT\" into a multi-timezone stamp before you send. Pick the timezones you want every message to include.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Your timezones") {
                    ForEach(selected, id: \.identifier) { tz in
                        HStack {
                            Text(TimeZoneAlias.shortLabel(for: tz))
                                .font(.body.monospaced())
                                .frame(width: 52, alignment: .leading)
                            Text(tz.identifier)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Spacer()
                        }
                    }
                    .onDelete { indexSet in
                        selected.remove(atOffsets: indexSet)
                        persist()
                    }
                    .onMove { from, to in
                        selected.move(fromOffsets: from, toOffset: to)
                        persist()
                    }

                    Button {
                        showingPicker = true
                    } label: {
                        Label("Add timezone", systemImage: "plus.circle")
                    }
                }

                Section("Preview") {
                    let sample = DetectedTime(
                        hour: 17, minute: 0,
                        timeZone: TimeZone(identifier: "America/Chicago") ?? .current,
                        hadExplicitZone: true,
                        range: NSRange(location: 0, length: 0),
                        originalText: "5pm CT"
                    )
                    Text(TimeConverter.renderStamp(for: sample, targets: selected))
                        .font(.callout.monospaced())
                }

                Section("Setup") {
                    Link("Enable the keyboard in Settings",
                         destination: URL(string: UIApplication.openSettingsURLString)!)
                    Text("Settings → General → Keyboard → Keyboards → Add New Keyboard → TimeTwister → turn on Allow Full Access.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("TimeTwister")
            .toolbar { EditButton() }
            .sheet(isPresented: $showingPicker) {
                TimeZonePicker { tz in
                    if !selected.contains(where: { $0.identifier == tz.identifier }) {
                        selected.append(tz)
                        persist()
                    }
                    showingPicker = false
                }
            }
        }
    }

    private func persist() {
        UserPreferences.shared.targetZones = selected
    }
}

/// Minimal searchable picker over all known IANA zones.
struct TimeZonePicker: View {
    var onPick: (TimeZone) -> Void
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List(filtered, id: \.self) { id in
                Button(id) {
                    if let tz = TimeZone(identifier: id) { onPick(tz) }
                }
                .foregroundStyle(.primary)
            }
            .searchable(text: $query)
            .navigationTitle("Add timezone")
        }
    }

    private var filtered: [String] {
        let all = TimeZone.knownTimeZoneIdentifiers.sorted()
        guard !query.isEmpty else { return all }
        let q = query.lowercased()
        return all.filter { $0.lowercased().contains(q) }
    }
}

#Preview {
    SettingsView()
}
