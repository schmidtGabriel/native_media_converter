import Flutter
import UIKit
import XCTest


@testable import native_media_converter

// This demonstrates a simple unit test of the Swift portion of this plugin's implementation.
//
// See https://developer.apple.com/documentation/xctest for more information about using XCTest.

class RunnerTests: XCTestCase {

  func testPluginInitialization() {
    let plugin = NativeMediaConverterPlugin()
    XCTAssertNotNil(plugin)
  }

}
