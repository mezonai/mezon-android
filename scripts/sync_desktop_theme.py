#!/usr/bin/env python3
"""Generate the Android palette from a pinned mezon-desktop commit."""

import argparse
import re
import subprocess
from pathlib import Path
from decimal import Decimal, ROUND_HALF_UP

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/java/com/mezon/mobile/core/theme/DesktopThemePalette.generated.kt"
THEMES = {
    "dark": "dark",
    "light": "light",
    "sunrise": "sunrise",
    "redDark": "redDark",
    "purple_haze": "purpleHaze",
    "abyss_dark": "abyssDark",
    "sunset": "sunset",
    "cisher": "cisher",
    "berrynade": "berrynade",
}

# Android Black keeps the pre-sync Android values for the shared palette keys.
ANDROID_DARK_COLORS = {
    "bg_active_friend_list": 0xFF4F378B,
    "bg_active_member_channel": 0xFF4F378B,
    "bg_button_primary": 0xFF5A62F4,
    "bg_icon_theme": 0xFF334155,
    "bg_input_secondary": 0xFF1C1D23,
    "bg_markdown_code": 0xFF383A43,
    "bg_mention_evryone": 0xFF3C4C43,
    "bg_primary": 0xFF383A43,
    "bg_secondary": 0xFF1C1B1F,
    "bg_surface": 0xFF26272F,
    "bg_theme_direct_message": 0xFF1C1B1F,
    "border_primary": 0xFF2A2D31,
    "button_theme_primary": 0xFF5A62F4,
    "color_mention_evryone": 0xFF009C67,
    "color_mention_hover": 0xFF3297FF,
    "color_text_active_button": 0xFF939BF6,
    "mention_color": 0xFF3297FF,
    "mention_primary": 0xFF3B426E,
    "text_secondary": 0xFFE6E1E5,
    "text_theme_message": 0xFFE6E1E5,
    "text_theme_primary": 0xFFCAC4D0,
    "theme_setting_nav": 0xFF212122,
    "theme_setting_primary": 0xFF26272F,
}
ANDROID_DARK_SEMANTIC = {
    "textPrimary": 0xFFE6E1E5,
    "textSecondary": 0xFFCAC4D0,
    "textMuted": 0xFF6B6B6B,
    "statusOnline": 0xFF43B581,
    "mentionBadge": 0xFFD30E0E,
}

NUMBER = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?"
RGBA = r"Rgba \{\s*" + r"\s*".join(fr"{key}: ({NUMBER})," for key in "rgba") + r"\s*\}"
STOPS = re.compile(fr"GradientStop \{{\s*color: {RGBA},\s*position: ({NUMBER}),\s*\}}")


MODES = {
    "dark": "DARK", "light": "LIGHT", "sunrise": "SUNRISE", "redDark": "RED_DARK",
    "purple_haze": "PURPLE_HAZE", "abyss_dark": "ABYSS", "sunset": "SUNSET",
    "cisher": "CISHER", "berrynade": "BERRYNADE",
}


def camel_case(name):
    first, *rest = name.split("_")
    return first + "".join(part.capitalize() for part in rest)


def split_arms(source, struct):
    arms = list(re.finditer(r'(?m)^\s*(?:"([^"]+)"|_) => ' + struct + r" \{", source))
    return {
        arm.group(1) or "dark": source[arm.end():arms[i + 1].start() if i + 1 < len(arms) else len(source)]
        for i, arm in enumerate(arms)
    }


def color_literal(values):
    channels = [int((Decimal(str(value)) * 255).quantize(Decimal("1"), rounding=ROUND_HALF_UP)) for value in values]
    assert all(0 <= channel <= 255 for channel in channels)
    red, green, blue, alpha = channels
    return f"0x{alpha:02X}{red:02X}{green:02X}{blue:02X}.toInt()"


def source_over(top, bottom):
    alpha = top[3] + bottom[3] * (1 - top[3])
    if alpha == 0:
        return (0, 0, 0, 0)
    rgb = [(a * top[3] + b * bottom[3] * (1 - top[3])) / alpha for a, b in zip(top[:3], bottom[:3])]
    return (*rgb, alpha)


def gradient_literal(angle, stops, overlay=None, base=None, viewport_anchored=False):
    colors = []
    positions = []
    for *channels, position in stops:
        color = tuple(map(float, channels))
        if base:
            color = source_over(color, base)
        if overlay:
            color = source_over(overlay, color)
        colors.append(color_literal(color))
        positions.append(position + "f")
    assert len(colors) >= 2
    return (
        f"ThemeGradient({angle}f, intArrayOf({', '.join(colors)}), "
        f"floatArrayOf({', '.join(positions)}), {str(viewport_anchored).lower()})"
    )


def optional_rgba(body, field):
    match = re.search(fr"{field}: Some\({RGBA}\)", body)
    return tuple(map(float, match.groups())) if match else None


def rgb_literal(value, alpha):
    return color_literal([*(f"{int(channel) / 255:.10g}" for channel in value), alpha])


def generate(revision, tokens_source, surfaces_source, semantic_source, presence_source):
    fields = re.findall(r"pub (\w+): Rgba,", tokens_source)
    tokens = {
        name: {key: (r, g, b, a) for key, r, g, b, a in re.findall(r"(\w+): " + RGBA, body)}
        for name, body in split_arms(tokens_source, "Self").items()
    }
    assert set(tokens) == set(THEMES)
    assert len(fields) == len(set(fields)) and all(set(table) == set(fields) for table in tokens.values())
    assert len({camel_case(field) for field in fields}) == len(fields)

    surface_arms = split_arms(surfaces_source, "SurfaceGradients")
    named_stops = {
        name: STOPS.findall(body)
        for name, body in re.findall(r"const (\w+): &\[GradientStop\] = &\[(.*?)\n\];", surfaces_source, re.S)
    }

    def surface_gradient(theme, field, *, fill=False):
        body = re.search(fr"\b{field}: Some\(SurfaceGradient \{{(.*?)\n            \}}\),", surface_arms.get(theme, ""), re.S)
        if body is None:
            return "null"
        body = body.group(1)
        anchored = re.search(r"viewport_anchored: (true|false)", body).group(1) == "true"
        # Desktop fill() uses the solid token for viewport-anchored surfaces.
        if fill and anchored:
            return "null"
        angle = re.search(fr"angle: ({NUMBER}),", body).group(1)
        named = re.search(r"stops: (\w+),", body)
        stops = named_stops[named.group(1)] if named else STOPS.findall(body)
        return gradient_literal(angle, stops, optional_rgba(body, "overlay"), optional_rgba(body, "base"), anchored)

    preview_body = surfaces_source.split("fn preview_surface(", 1)[1].split("ThemeSurface::new", 1)[0]
    previews = {}
    for match in re.finditer(
        fr'(?:"([^"]+)"|_) => \(\s*rgba\((\d+), (\d+), (\d+)\),\s*'
        fr'(?:None|Some\(\(({NUMBER}), (\w+), (?:None|Some\(rgba_alpha\((\d+), (\d+), (\d+), ({NUMBER})\)\))\)\)),?\s*\)',
        preview_body,
    ):
        name, r, g, b, angle, stop_key, or_, og, ob, oa = match.groups()
        overlay = (int(or_) / 255, int(og) / 255, int(ob) / 255, float(oa)) if oa else None
        gradient = gradient_literal(angle, named_stops[stop_key], overlay) if angle else "null"
        previews[name or "dark"] = (rgb_literal((r, g, b), "1"), gradient)
    assert set(previews) == set(THEMES), f"Preview coverage: {list(previews)}"

    semantic_fields = {
        "text_primary": "textPrimary",
        "text_secondary": "textSecondary",
        "text_muted": "textMuted",
        "status_online": "statusOnline",
        "mention_badge": "mentionBadge",
    }
    semantic_bases = {}
    for base in ("light", "dark"):
        body = re.search(fr"fn {base}_base\(\) -> Self \{{(.*?)\n    \}}", semantic_source, re.S).group(1)
        values = {}
        for field, swift in semantic_fields.items():
            r, g, b, a = re.search(fr"{field}: rgba\((\d+), (\d+), (\d+), ({NUMBER})\)", body).groups()
            values[swift] = rgb_literal((r, g, b), a)
        semantic_bases[base] = values

    online_rgba = re.search(r"DmAvatarPresence::Online => Some\(rgba\(0x([a-fA-F\d_]+)\)\)", presence_source).group(1)
    online = bytes.fromhex(online_rgba.replace("_", ""))
    assert len(online) == 4
    lines = [
        "// Generated by scripts/sync_desktop_theme.py.",
        f"// Source: mezon-desktop {revision}.",
        "package com.mezon.mobile.core.theme",
        "",
        "import com.mezon.mobile.ui.theme.ThemeMode",
        "",
        "enum class DesktopThemeToken {",
        *(f"    {field.upper()}," for field in fields),
        "}",
        "",
        "class DesktopThemePalette private constructor(",
        "    private val colors: IntArray,",
        "    val semantic: SemanticColors,",
        "    val previewColor: Int,",
        "    val previewGradient: ThemeGradient?,",
        "    val conversationGradient: ThemeGradient?,",
        "    val chatGradient: ThemeGradient?,",
        "    val composerGradient: ThemeGradient?,",
        "    val primaryFillGradient: ThemeGradient?,",
        "    internal val pollFieldBackground: Int = colors[DesktopThemeToken.BG_THEME_DIRECT_MESSAGE.ordinal],",
        ") {",
        "    operator fun get(token: DesktopThemeToken): Int = colors[token.ordinal]",
        "",
        "    data class SemanticColors(",
        *(f"        val {field}: Int," for field in semantic_fields.values()),
        "    )",
        "",
        "    companion object {",
        f"        val onlinePresenceBadge: Int = {rgb_literal(online[:3], str(online[3] / 255))}",
        "",
    ]
    for base, values in semantic_bases.items():
        lines += [f"        private val {base}Semantic = SemanticColors("]
        lines += [f"            {field} = {value}," for field, value in values.items()]
        lines += ["        )", ""]
    for name, swift in THEMES.items():
        bg = tokens[name]["bg_secondary"]
        luminance = sum(float(value) * weight for value, weight in zip(bg, (0.299, 0.587, 0.114)))
        semantic = "lightSemantic" if luminance > 0.5 else "darkSemantic"
        preview_color, preview_gradient = previews[name]
        lines += [f"        private val {swift} = DesktopThemePalette(", "            colors = intArrayOf("]
        lines += [f"                {color_literal(tokens[name][field])}, // {field}" for field in fields]
        lines += [
            "            ),",
            f"            semantic = {semantic},",
            f"            previewColor = {preview_color},",
            f"            previewGradient = {preview_gradient},",
            f"            conversationGradient = {surface_gradient(name, 'direct_message')},",
            f"            chatGradient = {surface_gradient(name, 'secondary')},",
            f"            composerGradient = {surface_gradient(name, 'surface', fill=True)},",
            f"            primaryFillGradient = {surface_gradient(name, 'primary', fill=True)}",
            "        )",
            "",
        ]
    assert ANDROID_DARK_COLORS.keys() <= set(fields)
    assert ANDROID_DARK_SEMANTIC.keys() == set(semantic_fields.values())
    lines += [
        "        // Keep the desktop palette available for surfaces that match desktop.",
        "        private val androidDark = DesktopThemePalette(",
        "            colors = dark.colors.copyOf().apply {",
        *(
            f"                this[DesktopThemeToken.{field.upper()}.ordinal] = 0x{color:08X}.toInt()"
            for field, color in ANDROID_DARK_COLORS.items()
        ),
        "            },",
        "            semantic = SemanticColors(",
        *(f"                {field} = 0x{color:08X}.toInt()," for field, color in ANDROID_DARK_SEMANTIC.items()),
        "            ),",
        "            previewColor = 0xFF111827.toInt(),",
        "            previewGradient = null,",
        "            conversationGradient = null,",
        "            chatGradient = null,",
        "            composerGradient = null,",
        "            primaryFillGradient = null,",
        f"            pollFieldBackground = 0x{ANDROID_DARK_COLORS['bg_primary']:08X}.toInt(),",
        "        )",
        "",
        "        internal fun forDesktopTheme(mode: ThemeMode): DesktopThemePalette = when (mode) {",
        "            ThemeMode.DARK -> dark",
        "            else -> forTheme(mode)",
        "        }",
        "",
    ]
    lines += ["        fun forTheme(mode: ThemeMode): DesktopThemePalette = when (mode) {"]
    lines += ["            ThemeMode.DARK -> androidDark"]
    lines += [f"            ThemeMode.{MODES[name]} -> {swift}" for name, swift in THEMES.items() if name != "dark"]
    lines += [
        '            ThemeMode.SYSTEM -> error("Resolve System appearance before selecting a palette")',
        "        }",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("desktop", type=Path, help="Path to the mezon-desktop Git checkout")
    parser.add_argument("--ref", required=True, help="Desktop commit to import")
    parser.add_argument("--check", action="store_true", help="Check without writing Kotlin")
    args = parser.parse_args()

    def git(*command):
        return subprocess.check_output(["git", "-C", str(args.desktop), *command], text=True)

    revision = git("rev-parse", "--verify", args.ref + "^{commit}").strip()
    sources = [git("show", f"{revision}:crates/mezon-theme/src/{name}.rs") for name in ("tokens", "surfaces", "lib")]
    presence = git("show", f"{revision}:crates/mezon-ui/src/util/user_status.rs")
    result = generate(revision, *sources, presence)
    if args.check:
        if OUTPUT.read_text() != result:
            raise SystemExit(f"{OUTPUT.name} is out of sync with desktop {revision}")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(result)


if __name__ == "__main__":
    main()
