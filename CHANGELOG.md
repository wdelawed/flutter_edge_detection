# Changelog

## [Unreleased]

- Remove the native iOS plugin implementation and make iOS calls throw `EdgeDetectionException(code: 'unimplemented', ...)`.
- Localize Android plugin UI strings for English and Arabic.
- Use localized native defaults for Android titles/instructions when text parameters are omitted from Flutter.
- Keep Android preview text overrides available from Flutter:
  - `androidAutoCapturePreviewRetakeButtonText`
  - `androidAutoCapturePreviewNextButtonText`

## [1.0.2] - 2024-06-19

- Update dependencies to latest versions:
  - image_picker: ^1.1.2
  - path_provider: ^2.1.5
  - permission_handler: ^12.0.1
- Ensure compatibility with latest stable Dart and Flutter SDKs
- Fix version solving issues for example app

## [1.0.0+1] - 2024-12-19

### Initial Release

This is the first release of the `flutter_edge_detection` package, a modern Flutter plugin for real-time edge detection and document scanning.

### Features

- 📱 **Cross-platform**: Works on both Android and iOS
- 📷 **Camera Integration**: Live camera scanning with edge detection
- 🖼️ **Gallery Support**: Process images from device gallery
- ✂️ **Smart Cropping**: Automatic edge detection and cropping
- 🎨 **Image Enhancement**: Black and white filter options
- 🔧 **Customizable UI**: Customizable button titles and labels
- 🛡️ **Error Handling**: Comprehensive error handling with custom exceptions

### Technical Requirements

- Flutter: >=3.27.0
- Dart: >=3.0.0
- Android: API level 21+ (Android 5.0+)
- iOS: 13.0+

### Dependencies

- image_picker: ^1.0.7
- path_provider: ^2.1.2
- permission_handler: ^11.3.0

## [1.0.0] - 2024-12-19

### Initial Release

This is the first release of the `flutter_edge_detection` package, a modern Flutter plugin for real-time edge detection and document scanning.

### Features

- 📱 **Cross-platform**: Works on both Android and iOS
- 📷 **Camera Integration**: Live camera scanning with edge detection
- 🖼️ **Gallery Support**: Process images from device gallery
- ✂️ **Smart Cropping**: Automatic edge detection and cropping
- 🎨 **Image Enhancement**: Black and white filter options
- 🔧 **Customizable UI**: Customizable button titles and labels
- 🛡️ **Error Handling**: Comprehensive error handling with custom exceptions

### Technical Requirements

- Flutter: >=3.27.0
- Dart: >=3.0.0
- Android: API level 21+ (Android 5.0+)
- iOS: 13.0+

### Dependencies

- image_picker: ^1.0.7
- path_provider: ^2.1.2
- permission_handler: ^11.3.0
