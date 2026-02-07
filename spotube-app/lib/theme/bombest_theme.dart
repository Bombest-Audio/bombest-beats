import 'package:flutter/material.dart' hide ColorScheme, ThemeMode, Colors;
import 'package:shadcn_flutter/shadcn_flutter.dart';
import 'package:shadcn_flutter/shadcn_flutter_extension.dart';

class BombestTheme {
    static const primary = Color(0xFFE90060);
    static const dark = Color(0xFF0B0E23);
    static const accent = Color(0xFF1A2040);
    static const surface = Color(0xFF121730);
    static const onPrimary = Color(0xFFFFFFFF); // Explicit white since we hid Colors

    static ColorScheme colors(ThemeMode mode) {
        if (mode == ThemeMode.light) {
            return LegacyColorSchemes.lightSlate().copyWith(
                primary: () => primary,
                secondary: () => accent,
                background: () => const Color(0xFFFFFFFF),
            );
        } else {
            return LegacyColorSchemes.darkSlate().copyWith(
                primary: () => primary,
                secondary: () => accent,
                background: () => dark,
            );
        }
    }
}
