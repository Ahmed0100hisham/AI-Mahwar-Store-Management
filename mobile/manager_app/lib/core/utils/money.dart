/// Preserve the server's exact three-decimal KWD representation without double arithmetic.
String formatKwd(String value) {
  if (!RegExp(r'^-?\d+\.\d{3}$').hasMatch(value)) {
    throw const FormatException('Invalid KWD decimal');
  }
  final negative = value.startsWith('-');
  final parts = (negative ? value.substring(1) : value).split('.');
  final whole = parts[0].replaceAllMapped(
    RegExp(r'\B(?=(\d{3})+(?!\d))'),
    (_) => ',',
  );
  return '${negative ? '-' : ''}$whole.${parts[1]} د.ك';
}
