//
//  CountryZoneTests.swift
//  TimeTwisterCoreTests
//
//  Mirror of android/core/.../CountryZoneTest.kt. Same inputs, same expectations
//  — divergence between platforms should break this side first.
//

import XCTest
@testable import TimeTwisterCore

final class CountryZoneTests: XCTestCase {

    func testPlusPrefixedNumberResolvesToZone() {
        XCTAssertEqual(CountryZone.zone(forPhone: "+44 7700 900123"),
                       TimeZone(identifier: "Europe/London"))
        XCTAssertEqual(CountryZone.zone(forPhone: "+81 3 1234 5678"),
                       TimeZone(identifier: "Asia/Tokyo"))
        XCTAssertEqual(CountryZone.zone(forPhone: "+972 50 123 4567"),
                       TimeZone(identifier: "Asia/Jerusalem"))
    }

    func testZeroZeroPrefixedNumberResolves() {
        XCTAssertEqual(CountryZone.zone(forPhone: "0033 6 12 34 56 78"),
                       TimeZone(identifier: "Europe/Paris"))
    }

    func testNanpDefaultsToUsEastern() {
        // Caribbean and Canada also live in +1, but US Eastern is the safest dominant
        // default. User can refine the suggestion before saving.
        XCTAssertEqual(CountryZone.zone(forPhone: "+1 (555) 123-4567"),
                       TimeZone(identifier: "America/New_York"))
    }

    func testLongestPrefixWinsOverShorter() {
        XCTAssertEqual(CountryZone.zone(forPhone: "+972..."),
                       TimeZone(identifier: "Asia/Jerusalem"))
        XCTAssertEqual(CountryZone.zone(forPhone: "+381 64 1234567"),
                       TimeZone(identifier: "Europe/Belgrade"))
    }

    func testBareNationalNumberReturnsNil() {
        // No country prefix → we don't guess the user's own zone here.
        XCTAssertNil(CountryZone.zone(forPhone: "555-1234"))
        XCTAssertNil(CountryZone.zone(forPhone: "07700 900123"))
    }

    func testUnmappedOrGarbledNumberReturnsNil() {
        XCTAssertNil(CountryZone.zone(forPhone: ""))
        XCTAssertNil(CountryZone.zone(forPhone: "+"))
        XCTAssertNil(CountryZone.zone(forPhone: "+999 9 12345"))
    }

    func testIso2LookupHandlesCommonCountries() {
        XCTAssertEqual(CountryZone.zone(forCountry: "CA"),
                       TimeZone(identifier: "America/Toronto"))
        XCTAssertEqual(CountryZone.zone(forCountry: "in"),
                       TimeZone(identifier: "Asia/Kolkata"))
        XCTAssertNil(CountryZone.zone(forCountry: "ZZ"))
    }
}
