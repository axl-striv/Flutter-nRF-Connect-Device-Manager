import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mcumgr_flutter/mcumgr_flutter.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('mcumgr_flutter/method_channel');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'initialization failure reaches caller instead of returning a dead manager',
    () async {
      final calls = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        throw PlatformException(
          code: 'WrongArguments',
          message: 'Bluetooth is powered off',
        );
      });
      await expectLater(
        FirmwareUpdateManagerFactory().getUpdateManager('left'),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.message,
            'message',
            'Bluetooth is powered off',
          ),
        ),
      );
      expect(calls, ['initializeUpdateManager']);
    },
  );
}
