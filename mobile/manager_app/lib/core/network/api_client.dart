import '../errors/app_failure.dart';
import 'api_transport.dart';

abstract interface class SessionCredentials {
  String? get accessToken;
  int get sessionEpoch;
  Future<void> refreshAfterUnauthorized(String failedToken);
  Future<void> terminate();
}

class ApiClient {
  ApiClient(this.transport, this.credentials);
  final ApiTransport transport;
  final SessionCredentials credentials;
  Future<Object?> send(String method, String path, {Object? body}) async {
    final epoch = credentials.sessionEpoch;
    for (var attempt = 0; attempt < 2; attempt++) {
      final token = credentials.accessToken;
      if (token == null || epoch != credentials.sessionEpoch) {
        throw AppFailure.signedOut;
      }
      try {
        final result = await transport.send(
          method,
          path,
          body: body,
          accessToken: token,
        );
        if (epoch != credentials.sessionEpoch) {
          throw AppFailure.signedOut;
        }
        return result;
      } on AppFailure catch (failure) {
        if (failure.status != 401) {
          rethrow;
        }
        if (epoch != credentials.sessionEpoch) {
          throw AppFailure.signedOut;
        }
        if (attempt == 1) {
          await credentials.terminate();
          throw AppFailure.signedOut;
        }
        await credentials.refreshAfterUnauthorized(token);
      }
    }
    throw AppFailure.signedOut;
  }
}
