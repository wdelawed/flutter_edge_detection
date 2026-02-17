/// A Flutter plugin for real-time edge detection and document scanning with advanced image processing capabilities.
library flutter_edge_detection;

import 'dart:async';

import 'package:flutter/services.dart';

/// A Flutter plugin for real-time edge detection and document scanning with advanced image processing capabilities.
class FlutterEdgeDetection {
  static const MethodChannel _channel = MethodChannel('flutter_edge_detection');

  /// Call this method to scan the object edge in live camera.
  ///
  /// [saveTo] is the file path where the cropped image will be saved.
  /// [canUseGallery] determines if the user can switch to gallery mode.
  /// [androidScanTitle] is the title for the scan screen on Android. If null, localized native default is used.
  /// [androidCropTitle] is the title for the crop button on Android. If null, localized native default is used.
  /// [androidCropBlackWhiteTitle] is the title for the black/white filter button on Android. If null, localized native default is used.
  /// [androidCropReset] is the title for the reset button on Android. If null, localized native default is used.
  /// [androidAutoCapture] enables automatic capture mode on Android.
  /// [androidAutoCaptureMinGoodFrames] controls how many good preview frames are required before auto capture.
  /// [androidAutoCaptureTextNoPassport] is shown when no passport is detected in the overlay. If null, localized native default is used.
  /// [androidAutoCaptureTextHoldStill] is shown when passport is detected and user should hold still. If null, localized native default is used.
  /// [androidAutoCaptureTextCapturing] is shown while image is being captured. If null, localized native default is used.
  /// [androidAutoCapturePreviewButtonTextColor] controls preview CTA button text color (hex, e.g. `#FFFFFF`).
  /// [androidAutoCapturePreviewButtonTextSize] controls preview CTA button text size in sp.
  /// [androidAutoCapturePreviewButtonHorizontalPadding] controls preview CTA button horizontal padding in dp.
  /// [androidAutoCapturePreviewButtonVerticalPadding] controls preview CTA button vertical padding in dp.
  /// [androidAutoCapturePreviewButtonBackgroundColor] controls preview CTA button background color (hex).
  /// [androidAutoCapturePreviewButtonBorderRadius] controls preview CTA button corner radius in dp.
  /// [androidAutoCapturePreviewRetakeButtonText] overrides Retake/Retry button label.
  /// [androidAutoCapturePreviewNextButtonText] overrides Next button label.
  /// [androidAutoCapturePreviewRetakeButtonTextColor] overrides Retake button text color.
  /// [androidAutoCapturePreviewRetakeButtonTextSize] overrides Retake button text size in sp.
  /// [androidAutoCapturePreviewRetakeButtonHorizontalPadding] overrides Retake button horizontal padding in dp.
  /// [androidAutoCapturePreviewRetakeButtonVerticalPadding] overrides Retake button vertical padding in dp.
  /// [androidAutoCapturePreviewRetakeButtonBackgroundColor] overrides Retake button background color (hex).
  /// [androidAutoCapturePreviewRetakeButtonBorderRadius] overrides Retake button corner radius in dp.
  /// [androidAutoCapturePreviewNextButtonTextColor] overrides Next button text color.
  /// [androidAutoCapturePreviewNextButtonTextSize] overrides Next button text size in sp.
  /// [androidAutoCapturePreviewNextButtonHorizontalPadding] overrides Next button horizontal padding in dp.
  /// [androidAutoCapturePreviewNextButtonVerticalPadding] overrides Next button vertical padding in dp.
  /// [androidAutoCapturePreviewNextButtonBackgroundColor] overrides Next button background color (hex).
  /// [androidAutoCapturePreviewNextButtonBorderRadius] overrides Next button corner radius in dp.
  ///
  /// Returns `true` if the operation was successful, `false` otherwise.
  static Future<bool> detectEdge(
    String saveTo, {
    bool canUseGallery = true,
    String? androidScanTitle,
    String? androidCropTitle,
    String? androidCropBlackWhiteTitle,
    String? androidCropReset,
    bool androidAutoCapture = false,
    int androidAutoCaptureMinGoodFrames = 2,
    String? androidAutoCaptureTextNoPassport,
    String? androidAutoCaptureTextHoldStill,
    String? androidAutoCaptureTextCapturing,
    String androidAutoCapturePreviewButtonTextColor = '#FFFFFFFF',
    double androidAutoCapturePreviewButtonTextSize = 16.0,
    double androidAutoCapturePreviewButtonHorizontalPadding = 16.0,
    double androidAutoCapturePreviewButtonVerticalPadding = 10.0,
    String androidAutoCapturePreviewButtonBackgroundColor = '#73000000',
    double androidAutoCapturePreviewButtonBorderRadius = 12.0,
    String? androidAutoCapturePreviewRetakeButtonText,
    String? androidAutoCapturePreviewNextButtonText,
    String? androidAutoCapturePreviewRetakeButtonTextColor,
    double? androidAutoCapturePreviewRetakeButtonTextSize,
    double? androidAutoCapturePreviewRetakeButtonHorizontalPadding,
    double? androidAutoCapturePreviewRetakeButtonVerticalPadding,
    String? androidAutoCapturePreviewRetakeButtonBackgroundColor,
    double? androidAutoCapturePreviewRetakeButtonBorderRadius,
    String? androidAutoCapturePreviewNextButtonTextColor,
    double? androidAutoCapturePreviewNextButtonTextSize,
    double? androidAutoCapturePreviewNextButtonHorizontalPadding,
    double? androidAutoCapturePreviewNextButtonVerticalPadding,
    String? androidAutoCapturePreviewNextButtonBackgroundColor,
    double? androidAutoCapturePreviewNextButtonBorderRadius,
  }) async {
    try {
      final bool? result = await _channel.invokeMethod('edge_detect', {
        'save_to': saveTo,
        'can_use_gallery': canUseGallery,
        'scan_title': androidScanTitle,
        'crop_title': androidCropTitle,
        'crop_black_white_title': androidCropBlackWhiteTitle,
        'crop_reset_title': androidCropReset,
        'auto_capture': androidAutoCapture,
        'auto_capture_min_good_frames': androidAutoCaptureMinGoodFrames,
        'auto_capture_text_no_passport': androidAutoCaptureTextNoPassport,
        'auto_capture_text_hold_still': androidAutoCaptureTextHoldStill,
        'auto_capture_text_capturing': androidAutoCaptureTextCapturing,
        'auto_capture_preview_button_text_color':
            androidAutoCapturePreviewButtonTextColor,
        'auto_capture_preview_button_text_size':
            androidAutoCapturePreviewButtonTextSize,
        'auto_capture_preview_button_horizontal_padding':
            androidAutoCapturePreviewButtonHorizontalPadding,
        'auto_capture_preview_button_vertical_padding':
            androidAutoCapturePreviewButtonVerticalPadding,
        'auto_capture_preview_button_background_color':
            androidAutoCapturePreviewButtonBackgroundColor,
        'auto_capture_preview_button_border_radius':
            androidAutoCapturePreviewButtonBorderRadius,
        'auto_capture_preview_retake_button_text':
            androidAutoCapturePreviewRetakeButtonText,
        'auto_capture_preview_next_button_text':
            androidAutoCapturePreviewNextButtonText,
        'auto_capture_preview_retake_button_text_color':
            androidAutoCapturePreviewRetakeButtonTextColor,
        'auto_capture_preview_retake_button_text_size':
            androidAutoCapturePreviewRetakeButtonTextSize,
        'auto_capture_preview_retake_button_horizontal_padding':
            androidAutoCapturePreviewRetakeButtonHorizontalPadding,
        'auto_capture_preview_retake_button_vertical_padding':
            androidAutoCapturePreviewRetakeButtonVerticalPadding,
        'auto_capture_preview_retake_button_background_color':
            androidAutoCapturePreviewRetakeButtonBackgroundColor,
        'auto_capture_preview_retake_button_border_radius':
            androidAutoCapturePreviewRetakeButtonBorderRadius,
        'auto_capture_preview_next_button_text_color':
            androidAutoCapturePreviewNextButtonTextColor,
        'auto_capture_preview_next_button_text_size':
            androidAutoCapturePreviewNextButtonTextSize,
        'auto_capture_preview_next_button_horizontal_padding':
            androidAutoCapturePreviewNextButtonHorizontalPadding,
        'auto_capture_preview_next_button_vertical_padding':
            androidAutoCapturePreviewNextButtonVerticalPadding,
        'auto_capture_preview_next_button_background_color':
            androidAutoCapturePreviewNextButtonBackgroundColor,
        'auto_capture_preview_next_button_border_radius':
            androidAutoCapturePreviewNextButtonBorderRadius,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      throw EdgeDetectionException(
        code: e.code,
        message: e.message ?? 'Unknown error occurred',
        details: e.details,
      );
    }
  }

  /// Call this method to scan the object edge from a gallery image.
  ///
  /// [saveTo] is the file path where the cropped image will be saved.
  /// [androidCropTitle] is the title for the crop button on Android. If null, localized native default is used.
  /// [androidCropBlackWhiteTitle] is the title for the black/white filter button on Android. If null, localized native default is used.
  /// [androidCropReset] is the title for the reset button on Android. If null, localized native default is used.
  ///
  /// Returns `true` if the operation was successful, `false` otherwise.
  static Future<bool> detectEdgeFromGallery(
    String saveTo, {
    String? androidCropTitle,
    String? androidCropBlackWhiteTitle,
    String? androidCropReset,
  }) async {
    try {
      final bool? result = await _channel.invokeMethod('edge_detect_gallery', {
        'save_to': saveTo,
        'crop_title': androidCropTitle,
        'crop_black_white_title': androidCropBlackWhiteTitle,
        'crop_reset_title': androidCropReset,
        'from_gallery': true,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      throw EdgeDetectionException(
        code: e.code,
        message: e.message ?? 'Unknown error occurred',
        details: e.details,
      );
    }
  }
}

/// Exception thrown when edge detection operations fail.
class EdgeDetectionException implements Exception {
  /// The error code.
  final String code;

  /// The error message.
  final String message;

  /// Additional error details.
  final dynamic details;

  const EdgeDetectionException({
    required this.code,
    required this.message,
    this.details,
  });

  @override
  String toString() => 'EdgeDetectionException($code, $message)';
}
