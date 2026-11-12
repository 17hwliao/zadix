"""Exercise the two installed debug APKs on an explicitly selected Android emulator."""
import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


APP = "com.ahuamiao.adprotection"
FIXTURE = APP + ".fixture"


class Emulator:
    def __init__(self, adb, serial):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Select a local Android emulator, not a phone")
        self.prefix = [adb, "-s", serial]
        self.dump_number = 0
        if self.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise ValueError("The target did not identify itself as an emulator")

    def shell(self, *args):
        return subprocess.run(self.prefix + ["shell", *args], check=True, capture_output=True,
                              encoding="utf-8", timeout=25).stdout

    def nodes(self):
        for _ in range(3):
            self.dump_number += 1
            path = f"/data/local/tmp/adkiller-smoke-{self.dump_number}.xml"
            dump = self.shell(f"rm -f {path}; uiautomator dump {path} >/dev/null && cat {path}; rm -f {path}")
            start = dump.find("<?xml")
            if start >= 0:
                return list(ET.fromstring(dump[start:]).iter("node"))
            time.sleep(0.55)
        raise AssertionError("The emulator UI did not settle long enough to read the current window")

    def tap(self, node, long_press=False):
        a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds", "")))
        x, y = str((a + c) // 2), str((b + d) // 2)
        if long_press:
            self.shell("input", "swipe", x, y, x, y, "750")
        else:
            self.shell("input", "tap", x, y)

    def home_app(self):
        self.shell("cmd", "statusbar", "collapse")
        output = self.shell("am", "start", "-W", "-f", "0x14000000", "-n", APP + "/.MainActivity")
        assert "Status: ok" in output, output
        time.sleep(0.65)

    def focus(self):
        return next((line.strip() for line in self.shell("dumpsys", "window").splitlines()
                     if "mCurrentFocus=Window" in line), "")

    def choose_in_app(self, enabled):
        self.home_app()
        text = "开启实时跳过" if enabled else "暂停实时跳过"
        button = next((n for n in self.nodes() if n.get("package") == APP and n.get("text") == text), None)
        if button is not None:
            self.tap(button)
            time.sleep(0.6)
        self.expect_service(enabled)

    def expect_service(self, enabled):
        # UiAutomation snapshots can briefly suppress other services. Check service state after the dump exits.
        for _ in range(8):
            state = self.shell("dumpsys", "accessibility")
            bound = next((line for line in state.splitlines() if "Bound services:" in line and "实时跳过" in line), "")
            if bound and ("TYPE_WINDOW_CONTENT_CHANGED" in bound) == enabled:
                return
            time.sleep(0.45)
        raise AssertionError(f"Service did not acknowledge enabled={enabled}")

    def tile(self, long_press=False):
        self.shell("cmd", "statusbar", "expand-settings")
        time.sleep(0.65)
        for attempt in range(5):
            nodes = self.nodes()
            entry = next((n for n in nodes if n.get("package") == "com.android.systemui"
                          and n.get("clickable") == "true"
                          and n.get("content-desc", "").startswith("实时跳过")), None)
            if entry is not None:
                time.sleep(0.6)
                self.tap(entry, long_press)
                time.sleep(0.65)
                self.shell("cmd", "statusbar", "collapse")
                return
            pager = next((n for n in nodes if n.get("resource-id") == "com.android.systemui:id/qs_pager"), None)
            if pager is None:
                break
            left, top, right, bottom = map(int, re.findall(r"\d+", pager.get("bounds", "")))
            width, height = right - left, bottom - top
            y = str(top + round(height * .792))
            self.shell("input", "swipe", str(left + round(width * .892)), y,
                       str(left + round(width * .073)), y, str(160 + 65 * attempt))
            time.sleep(1.35)
        raise AssertionError("Add the 实时跳过 tile from the App before running this test")

    def scenario(self, name, wait=0.65):
        response = self.shell("am", "start", "-W", "-f", "0x10008000", "-n", FIXTURE + "/.FixtureActivity",
                              "--es", "scenario", name)
        assert "Status: ok" in response, response
        time.sleep(wait)

    def result(self, name, count, result=None):
        nodes = self.nodes()
        nodes = [n for n in nodes if n.get("package") == FIXTURE]
        counts = [n.get("text") for n in nodes if n.get("resource-id", "").endswith("/action_count")]
        assert counts and all(c == count for c in counts), (name, counts)
        ids = {n.get("resource-id", "").split("/")[-1] for n in nodes}
        assert "result_unexpected" not in ids, (name, ids)
        assert result is None or result in ids, (name, result, ids)
        print(f"{name}: {count}" + (f", {result}" if result else ""), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) /
                                            "Android/Sdk/platform-tools/adb.exe"))
    args = parser.parse_args()
    device = Emulator(args.adb, args.serial)
    assert "LiveAccessibility" in device.shell("settings", "get", "secure", "enabled_accessibility_services"), (
        "Use the emulator Settings screen to enable the service first"
    )
    zero = "accessibility=0, manual=0"
    auto = "accessibility=1, manual=0"
    device.choose_in_app(False)
    device.scenario("ad")
    device.result("App pause", zero, "ad_marker")
    device.choose_in_app(True)
    device.shell("input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.65)
    focus = device.focus()
    assert focus and APP + ".MainActivity" not in focus, ("The main screen did not leave the foreground", focus)
    device.scenario("ad")
    device.result("App resume with main screen left", auto, "result_auto")
    for scenario in ("normal", "sensitive", "ambiguous"):
        device.scenario(scenario)
        device.result(scenario, zero)
    device.scenario("repeat", wait=2.2)
    device.result("repeat", auto, "result_auto")

    device.tile()
    device.expect_service(False)
    device.scenario("ad")
    device.result("tile pause", zero, "ad_marker")
    device.scenario("countdown", wait=2.2)
    device.result("natural countdown while paused", zero, "result_natural")
    device.scenario("manual")
    close = next(n for n in device.nodes() if n.get("resource-id") == FIXTURE + ":id/ad_close")
    device.tap(close)
    time.sleep(0.45)
    device.result("manual close while paused", "accessibility=0, manual=1", "result_manual")

    device.scenario("switch")
    device.result("switched to dialog", zero, "result_switch")
    device.tile()
    device.expect_service(True)
    device.result("tile resume with dialog in front", zero, "result_switch")
    device.scenario("ad")
    device.result("tile resume with current ad", auto, "result_auto")
    device.tile(long_press=True)
    assert APP + ".MainActivity" in device.focus(), "Long press did not open settings"
    print("tile long press: opened settings", flush=True)


if __name__ == "__main__":
    main()
