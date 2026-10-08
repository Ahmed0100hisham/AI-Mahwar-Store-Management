import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../core/theme/app_theme.dart';
import '../features/auth/presentation/login_screen.dart';
import '../features/auth/presentation/change_password_screen.dart';
import '../features/auth/state/auth_controller.dart';
import '../features/dashboard/presentation/manager_shell.dart';
import '../shared/widgets/states.dart';

class ManagerApp extends StatefulWidget {
  const ManagerApp({super.key, required this.auth, this.monitorSession = true});
  final AuthController auth;
  final bool monitorSession;
  @override
  State<ManagerApp> createState() => _ManagerAppState();
}

class _ManagerAppState extends State<ManagerApp> with WidgetsBindingObserver {
  Timer? _timer;
  final _navigator = GlobalKey<NavigatorState>();
  AuthStatus? _previous;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.auth.addListener(_guardRoutes);
    if (widget.monitorSession) {
      _timer = Timer.periodic(
        const Duration(seconds: 60),
        (_) => widget.auth.checkSession(),
      );
    }
  }

  void _guardRoutes() {
    final next = widget.auth.status;
    if (_previous != next &&
        (next == AuthStatus.signedOut || next == AuthStatus.restricted)) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          _navigator.currentState?.popUntil((route) => route.isFirst);
        }
      });
    }
    _previous = next;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      widget.auth.checkSession();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.auth.removeListener(_guardRoutes);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: _navigator,
    debugShowCheckedModeBanner: false,
    title: 'مساحة الإدارة',
    locale: const Locale('ar'),
    supportedLocales: const [Locale('ar'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: managerTheme(Brightness.light),
    darkTheme: managerTheme(Brightness.dark),
    themeMode: ThemeMode.system,
    home: ListenableBuilder(
      listenable: widget.auth,
      builder: (context, _) => switch (widget.auth.status) {
        AuthStatus.restoring => const Scaffold(body: BusyView()),
        AuthStatus.signedOut => LoginScreen(auth: widget.auth),
        AuthStatus.restricted => ChangePasswordScreen(
          auth: widget.auth,
          restricted: true,
        ),
        AuthStatus.authenticated => ManagerShell(auth: widget.auth),
      },
    ),
  );
}
