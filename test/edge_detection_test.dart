import 'package:flutter/services.dart';
import 'package:flutter_edge_detection/flutter_edge_detection.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('flutter_edge_detection');
  final log = <MethodCall>[];

  setUp(() {
    log.clear();
    FlutterEdgeDetection.debugTargetPlatformOverride = TargetPlatform.android;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      log.add(methodCall);
      switch (methodCall.method) {
        case 'edge_detect':
          return true;
        case 'edge_detect_gallery':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    FlutterEdgeDetection.debugTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('FlutterEdgeDetection', () {
    test('detectEdge calls correct method with parameters', () async {
      const saveTo = '/test/path/result.jpg';
      const canUseGallery = true;
      const scanTitle = 'Custom Scan';
      const cropTitle = 'Custom Crop';
      const blackWhiteTitle = 'Custom BW';
      const resetTitle = 'Custom Reset';

      final result = await FlutterEdgeDetection.detectEdge(
        saveTo,
        canUseGallery: canUseGallery,
        androidScanTitle: scanTitle,
        androidCropTitle: cropTitle,
        androidCropBlackWhiteTitle: blackWhiteTitle,
        androidCropReset: resetTitle,
      );

      expect(log, hasLength(1));
      expect(log.first.method, 'edge_detect');
      final arguments = Map<String, dynamic>.from(log.first.arguments as Map);
      expect(arguments['save_to'], saveTo);
      expect(arguments['can_use_gallery'], canUseGallery);
      expect(arguments['scan_title'], scanTitle);
      expect(arguments['crop_title'], cropTitle);
      expect(arguments['crop_black_white_title'], blackWhiteTitle);
      expect(arguments['crop_reset_title'], resetTitle);
      expect(arguments['auto_capture'], false);
      expect(arguments['auto_capture_min_good_frames'], 2);
      expect(result, true);
    });

    test('detectEdgeFromGallery calls correct method with parameters', () async {
      const saveTo = '/test/path/result.jpg';
      const cropTitle = 'Custom Crop';
      const blackWhiteTitle = 'Custom BW';
      const resetTitle = 'Custom Reset';

      final result = await FlutterEdgeDetection.detectEdgeFromGallery(
        saveTo,
        androidCropTitle: cropTitle,
        androidCropBlackWhiteTitle: blackWhiteTitle,
        androidCropReset: resetTitle,
      );

      expect(log, hasLength(1));
      expect(log.first.method, 'edge_detect_gallery');
      final arguments = Map<String, dynamic>.from(log.first.arguments as Map);
      expect(arguments['save_to'], saveTo);
      expect(arguments['crop_title'], cropTitle);
      expect(arguments['crop_black_white_title'], blackWhiteTitle);
      expect(arguments['crop_reset_title'], resetTitle);
      expect(arguments['from_gallery'], true);
      expect(result, true);
    });

    test('detectEdge throws unimplemented on iOS without invoking channel', () async {
      FlutterEdgeDetection.debugTargetPlatformOverride = TargetPlatform.iOS;

      await expectLater(
        () => FlutterEdgeDetection.detectEdge('/test/path/result.jpg'),
        throwsA(
          isA<EdgeDetectionException>()
              .having((e) => e.code, 'code', 'unimplemented')
              .having(
                (e) => e.message,
                'message',
                'flutter_edge_detection is only implemented for Android.',
              ),
        ),
      );

      expect(log, isEmpty);
    });
  });
}
