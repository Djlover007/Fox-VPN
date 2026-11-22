#!/usr/bin/env python3
"""Baut Fox VPN in ics-openvpn ein (Variante skeletonOvpn2).

Aufruf: python3 fox/patch.py <pfad-zu-ics-openvpn> <versionCode>
"""
import pathlib
import shutil
import sys

fox = pathlib.Path(__file__).resolve().parent
ics = pathlib.Path(sys.argv[1]).resolve()
run = int(sys.argv[2]) if len(sys.argv) > 2 else 1


def replace_once(text: str, old: str, new: str, what: str) -> str:
    if text.count(old) != 1:
        sys.exit(f"Patch fehlgeschlagen ({what}): Stelle {old!r} {text.count(old)}x gefunden")
    return text.replace(old, new)


# 1) Quellcode, Ressourcen und Manifest in die Skeleton-Variante kopieren
skel = ics / "main/src/skeleton"
shutil.copytree(fox / "java", skel / "java", dirs_exist_ok=True)
shutil.copytree(fox / "res", skel / "res", dirs_exist_ok=True)
shutil.copy(fox / "AndroidManifest.xml", skel / "AndroidManifest.xml")

# 2) Compose-Plugin im Versionskatalog und im Root-Projekt
toml = ics / "gradle/libs.versions.toml"
t = toml.read_text()
t = replace_once(
    t, "[plugins]\n",
    '[plugins]\nkotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }\n',
    "toml plugins")
toml.write_text(t)

root = ics / "build.gradle.kts"
t = root.read_text()
t = replace_once(
    t, "    alias(libs.plugins.kotlin.android) apply false\n",
    "    alias(libs.plugins.kotlin.android) apply false\n    alias(libs.plugins.kotlin.compose) apply false\n",
    "root plugins")
root.write_text(t)

# 3) App-Modul anpassen
mod = ics / "main/build.gradle.kts"
t = mod.read_text()
t = replace_once(
    t, "    alias(libs.plugins.android.application)\n",
    "    alias(libs.plugins.android.application)\n    alias(libs.plugins.kotlin.compose)\n",
    "module plugins")
t = replace_once(t, "        aidl = true\n", "        aidl = true\n        compose = true\n", "buildFeatures")
t = replace_once(
    t, "        minSdk = 23\n",
    '        applicationId = "com.djlover007.foxvpn"\n        minSdk = 26\n'
    '        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }\n',
    "defaultConfig")
t = replace_once(t, "        versionCode = 225\n", f"        versionCode = {run}\n", "versionCode")
t = replace_once(t, '        versionName = "0.7.69"\n', f'        versionName = "2.0.{run}"\n', "versionName")
t = replace_once(t, '            versionNameSuffix = "-o2"\n', "", "versionNameSuffix")
t += '''

// ---- Fox VPN ----
dependencies {
    add("skeletonImplementation", platform("androidx.compose:compose-bom:2025.06.00"))
    add("skeletonImplementation", "androidx.compose.ui:ui")
    add("skeletonImplementation", "androidx.compose.foundation:foundation")
    add("skeletonImplementation", "androidx.compose.material3:material3")
    add("skeletonImplementation", "androidx.compose.material:material-icons-extended")
    add("skeletonImplementation", "androidx.activity:activity-compose:1.10.1")
    add("skeletonImplementation", "androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    add("skeletonImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
'''
mod.write_text(t)
print("Fox VPN eingebaut, versionCode", run)
