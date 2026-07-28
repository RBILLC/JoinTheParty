#!/usr/bin/env python3
"""SCAF-04: generate DesignTokens.swift / DesignTokens.kt from design/tokens.json.

One source of truth for the Billet system (ui-ux-design-system.md §7): hexes,
type ramp, spacing, motion constants, and haptic patterns stay byte-identical
across both shells. Widgets import the generated file and never hardcode a
value.

Usage:  python tools/design-tokens/generate.py
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOKENS = ROOT / "design" / "tokens.json"
SWIFT_OUT = ROOT / "ios" / "JoinTheParty" / "DesignTokens" / "DesignTokens.swift"
KOTLIN_OUT = (
    ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "jointheparty"
    / "app" / "ui" / "theme" / "DesignTokens.kt"
)

HEADER = "GENERATED from design/tokens.json — do not edit. Run tools/design-tokens/generate.py."


def hex_rgb(value: str) -> int:
    return int(value.lstrip("#"), 16)


def swift(tokens: dict) -> str:
    lines: list[str] = []
    a = lines.append
    a(f"// {HEADER}")
    a(f"// System: {tokens['meta']['system']} v{tokens['meta']['version']}")
    a("")
    a("import SwiftUI")
    a("")
    a("enum DT {")
    a("    // MARK: Color")
    a("    enum Colors {")
    for name, value in tokens["color"].items():
        a(f"        static let {name} = Color(dtHex: 0x{hex_rgb(value):06X})")
    a("    }")
    a("")
    a("    // MARK: Type")
    a(f"    static let fontFamily = \"{tokens['type']['family']}\"")
    a("    struct TextToken {")
    a("        let size: CGFloat")
    a("        let weight: Font.Weight")
    a("        let trackingPct: CGFloat")
    a("        let lineHeight: CGFloat")
    a("        let tabular: Bool")
    a("        let uppercase: Bool")
    a("    }")
    a("    enum Type {")
    weight_map = {300: ".light", 400: ".regular", 500: ".medium", 600: ".semibold", 700: ".bold"}
    for name, st in tokens["type"]["styles"].items():
        a(
            f"        static let {name} = TextToken("
            f"size: {st['size']}, weight: {weight_map[st['weight']]}, "
            f"trackingPct: {st.get('trackingPct', 0)}, "
            f"lineHeight: {st.get('lineHeight', 1.2)}, "
            f"tabular: {str(st.get('tabular', False)).lower()}, "
            f"uppercase: {str(st.get('uppercase', False)).lower()})"
        )
    a("    }")
    a("")
    a("    // MARK: Space & shape")
    a("    enum Space {")
    for name, v in tokens["space"].items():
        a(f"        static let {name}: CGFloat = {v}")
    a("    }")
    a("    enum Shape {")
    for name, v in tokens["shape"].items():
        a(f"        static let {name}: CGFloat = {v}")
    a("    }")
    a("")
    a("    // MARK: Motion")
    a("    enum Motion {")
    for name, v in tokens["motion"].items():
        a(f"        static let {name}: Double = {v}")
    a("    }")
    a("")
    a("    // MARK: Haptics")
    a("    struct HapticToken { let intensity: Double; let sharpness: Double }")
    a("    enum Haptics {")
    for name, h in tokens["haptics"].items():
        a(
            f"        static let {name} = HapticToken("
            f"intensity: {h['intensity']}, sharpness: {h['sharpness']})"
        )
    a("    }")
    a("")
    a("    // MARK: Controls")
    a("    enum Wheel {")
    for name, v in tokens["wheel"].items():
        a(f"        static let {name}: Double = {v}")
    a("    }")
    a("    enum Meter {")
    for name, v in tokens["meter"].items():
        a(f"        static let {name}: Double = {v}")
    a("    }")
    a("    enum Calibration {")
    for name, v in tokens["calibration"].items():
        a(f"        static let {name}: Double = {v}")
    a("    }")
    a("}")
    a("")
    a("extension Color {")
    a("    init(dtHex: UInt32) {")
    a("        self.init(")
    a("            .sRGB,")
    a("            red: Double((dtHex >> 16) & 0xFF) / 255.0,")
    a("            green: Double((dtHex >> 8) & 0xFF) / 255.0,")
    a("            blue: Double(dtHex & 0xFF) / 255.0,")
    a("            opacity: 1.0")
    a("        )")
    a("    }")
    a("}")
    a("")
    return "\n".join(lines)


def kotlin(tokens: dict) -> str:
    lines: list[str] = []
    a = lines.append
    a(f"// {HEADER}")
    a(f"// System: {tokens['meta']['system']} v{tokens['meta']['version']}")
    a("")
    a("package com.jointheparty.app.ui.theme")
    a("")
    a("import androidx.compose.ui.graphics.Color")
    a("import androidx.compose.ui.unit.dp")
    a("")
    a("object DT {")
    a("    object Colors {")
    for name, value in tokens["color"].items():
        a(f"        val {name} = Color(0xFF{hex_rgb(value):06X})")
    a("    }")
    a("")
    a(f"    const val FONT_FAMILY = \"{tokens['type']['family']}\"")
    a("")
    a("    data class TextToken(")
    a("        val sizeSp: Float,")
    a("        val weight: Int,")
    a("        val trackingPct: Float,")
    a("        val lineHeight: Float,")
    a("        val tabular: Boolean,")
    a("        val uppercase: Boolean,")
    a("    )")
    a("    object Type {")
    for name, st in tokens["type"]["styles"].items():
        a(
            f"        val {name} = TextToken("
            f"{st['size']}f, {st['weight']}, "
            f"{st.get('trackingPct', 0)}f, {st.get('lineHeight', 1.2)}f, "
            f"{str(st.get('tabular', False)).lower()}, "
            f"{str(st.get('uppercase', False)).lower()})"
        )
    a("    }")
    a("")
    a("    object Space {")
    for name, v in tokens["space"].items():
        a(f"        val {name} = {v}.dp")
    a("    }")
    a("    object Shape {")
    for name, v in tokens["shape"].items():
        a(f"        val {name} = {v}.dp")
    a("    }")
    a("")
    a("    object Motion {")
    for name, v in tokens["motion"].items():
        a(f"        const val {name} = {float(v)}f")
    a("    }")
    a("")
    a("    data class HapticToken(val intensity: Float, val sharpness: Float)")
    a("    object Haptics {")
    for name, h in tokens["haptics"].items():
        a(f"        val {name} = HapticToken({h['intensity']}f, {h['sharpness']}f)")
    a("    }")
    a("")
    a("    object Wheel {")
    for name, v in tokens["wheel"].items():
        a(f"        const val {name} = {float(v)}f")
    a("    }")
    a("    object Meter {")
    for name, v in tokens["meter"].items():
        a(f"        const val {name} = {float(v)}f")
    a("    }")
    a("    object Calibration {")
    for name, v in tokens["calibration"].items():
        a(f"        const val {name} = {float(v)}f")
    a("    }")
    a("}")
    a("")
    return "\n".join(lines)


def main() -> None:
    tokens = json.loads(TOKENS.read_text(encoding="utf-8"))
    SWIFT_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    SWIFT_OUT.write_text(swift(tokens), encoding="utf-8", newline="\n")
    KOTLIN_OUT.write_text(kotlin(tokens), encoding="utf-8", newline="\n")
    print(f"wrote {SWIFT_OUT.relative_to(ROOT)}")
    print(f"wrote {KOTLIN_OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
