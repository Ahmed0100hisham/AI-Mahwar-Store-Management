import 'package:flutter/material.dart';

import 'app/manager_app.dart';
import 'core/config/app_config.dart';
import 'core/network/api_transport.dart';
import 'core/storage/token_vault.dart';
import 'features/auth/data/auth_repository.dart';
import 'features/auth/state/auth_controller.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    final config = AppConfig.fromEnvironment();
    final auth = AuthController(
      AuthRepository(DioApiTransport(config)),
      SecureTokenVault(config.baseUrl),
    );
    runApp(ManagerApp(auth: auth));
    auth.restore();
  } on FormatException catch (error) {
    runApp(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: Scaffold(
            body: Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Text(
                  error.message.toString(),
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
