"""python3 -m unittest scripts/test_device_detector_scan.py: reading cards from a uiautomator dump."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import device_detector_scan as scan  # noqa: E402


def node(bounds, text="", clickable=False, scrollable=False, children="", desc=""):
    return (
        f'<node text="{text}" content-desc="{desc}" clickable="{str(clickable).lower()}" '
        f'scrollable="{str(scrollable).lower()}" bounds="{bounds}">{children}</node>'
    )


# Duck Detector's layout, cut down: a scrolling list of clickable cards, a header card with the
# scan time and build stamp, a toolbar button above the list and a card cut by the list's bottom.
SCREEN = "<hierarchy>" + node(
    "[0,0][1904,3040]",
    children=node(
        "[1783,75][1895,187]", clickable=True, children=node("[1816,142][1863,155]", desc="Telegram")
    )
    + node(
        "[0,200][1904,2815]",
        scrollable=True,
        children=node(
            "[37,365][1867,789]",
            clickable=True,
            children=node("[251,418][374,475]", "Danger")
            + node("[121,854][749,898]", "Scanned at 2026-11-07 18:23:30 · Total time 14s")
            + node("[126,570][1830,608]", "2026.11.07-d60597cac452 (1105)")
            + node("[275,954][312,1024]", "4")
            + node("[258,1029][355,1067]", "Danger"),
        )
        + node(
            "[37,1132][1867,1394]",
            clickable=True,
            children=node("[74,1175][1721,1219]", "Bootloader")
            + node("[74,1239][1830,1296]", "6 critical boot integrity signal(s)")
            # a clickable block inside a card is its own card
            + node(
                "[74,1300][900,1390]",
                clickable=True,
                children=node("[80,1310][800,1380]", "Details"),
            ),
        )
        + node(
            "[37,2756][1867,2815]",
            clickable=True,
            children=node("[205,2793][432,2815]", "Virtualization"),
        ),
    ),
) + "</hierarchy>"


class CardsTest(unittest.TestCase):
    def test_cards_join_their_lines_without_noise(self):
        cards = scan.cards(SCREEN, scan.DETECTORS[0].noise)
        self.assertIn("Danger | 4 | Danger", cards, "scan time and build stamp are left out")
        self.assertIn("Bootloader | 6 critical boot integrity signal(s)", cards)
        self.assertIn("Details", cards, "a nested clickable block is a card of its own")

    def test_cards_cut_by_the_list_edge_or_outside_it_are_left_out(self):
        cards = scan.cards(SCREEN, scan.DETECTORS[0].noise)
        self.assertFalse(any("Virtualization" in c for c in cards), "cut by the bottom edge")
        self.assertNotIn("Telegram", cards, "toolbar above the list")

    def test_cards_are_in_screen_order(self):
        cards = scan.cards(SCREEN, scan.DETECTORS[0].noise)
        self.assertEqual(cards[0], "Danger | 4 | Danger")
        self.assertLess(cards.index("Bootloader | 6 critical boot integrity signal(s)"), cards.index("Details"))

    def test_without_a_scrolling_list_every_clickable_block_counts(self):
        xml = "<hierarchy>" + node("[0,0][100,100]", "OK", clickable=True) + "</hierarchy>"
        self.assertEqual(scan.cards(xml), ["OK"])

    def test_text_outside_clickable_blocks_is_a_card_per_parent(self):
        # Luna 1.4.4.6 on a TB323FU: the verdict is plain text, only "Thanks" and "GitHub" are buttons
        luna = "<hierarchy>" + node(
            "[0,0][3040,1904]",
            children=node(
                "[0,91][3040,241]",
                children=node("[56,134][270,197]", "Luna 1.4.4.6")
                + node("[2797,110][2909,222]", "Thanks", clickable=True)
                + node("[2909,110][3021,222]", "GitHub", clickable=True),
            )
            + node(
                "[0,150][3040,1904]",
                children=node("[1146,360][1894,533]", "Trust Phone")
                + node("[1452,542][1588,623]", "8/8&#10;********")
                + node("[1168,903][1872,1151]", "* LENOVO | Android 16&#10;Detection time: 2026/11/08 16:36")
                + node("[1438,1193][1602,1257]", "Thanks"),
            ),
        ) + "</hierarchy>"
        cards = scan.cards(luna, scan.DETECTORS[1].noise)
        self.assertEqual(cards, ["Thanks", "GitHub", "Trust Phone | 8/8 ******** | Thanks"])
        # Duck's counts stay next to their labels: swapped counts are a different card
        tiles = "<hierarchy>" + node(
            "[0,0][3040,1904]",
            children=node("[345,879][869,911]", "Scanned at 2026-11-08 16:43:39 · Total time 14s")
            + "".join(node(f"[{x},973][{x + 40},1070]", t) for x, t in [(600, "0"), (700, "Danger"), (1280, "2"), (1360, "Warning")]),
        ) + "</hierarchy>"
        self.assertEqual(scan.cards(tiles), ["0 | Danger | 2 | Warning"])
        # text inside a clickable block stays in that block's card only
        self.assertNotIn("Bootloader", scan.cards(SCREEN, scan.DETECTORS[0].noise))

    def test_common_noise(self):
        for line in ["Scanned 18:23", "2026-11-07", "Total time 14s", "Thời gian 3 giây", "耗时 2 秒", "12.5 ms"]:
            self.assertTrue(scan._noisy(line, []), line)
        for line in ["6 critical boot integrity signal(s)", "Trust Phone 8/8", "Found property(1)"]:
            self.assertFalse(scan._noisy(line, []), line)


class BusyTest(unittest.TestCase):
    def test_running_scans_are_not_settled(self):
        duck, _, chunqiu = scan.DETECTORS
        self.assertTrue(scan.busy(["0 | Danger | 2 | Warning | 18 | Ready | 2 | Pending"], duck.busy))
        self.assertFalse(scan.busy(["0 | Danger | 2 | Warning | 20 | Ready | 0 | Pending"], duck.busy))
        self.assertTrue(scan.busy(["Local checks complete; verifying online | Completed 160 / 160"], chunqiu.busy))
        self.assertFalse(scan.busy(["System compromised", "Online Check"], chunqiu.busy))
        self.assertFalse(scan.busy(["anything"], []))


class CompareTest(unittest.TestCase):
    def test_merge_keeps_order_and_drops_repeats(self):
        self.assertEqual(scan.merge(["a", "b"], ["b", "c", "a", "d"]), ["a", "b", "c", "d"])

    def test_compare(self):
        base = ["Summary | 0 | Danger", "ADB Runtime | ADB enabled | Warning", "SU | No root indicators | Info"]
        now = ["Summary | 1 | Danger", "ADB Runtime | ADB enabled | Warning", "SU | No root indicators | Info"]
        self.assertEqual(scan.compare(base, now), (["Summary | 1 | Danger"], ["Summary | 0 | Danger"]))
        self.assertEqual(scan.compare(base, list(reversed(base))), ([], []), "order alone is no change")


class ResolveTest(unittest.TestCase):
    def test_exact_package_or_one_pattern_hit(self):
        duck, luna, _ = scan.DETECTORS
        self.assertEqual(scan.resolve(duck, ["com.eltavine.duckdetector"]), "com.eltavine.duckdetector")
        self.assertIsNone(scan.resolve(duck, ["com.other"]))
        self.assertEqual(scan.resolve(luna, ["a.b", "com.x.Luna"]), "com.x.Luna")
        self.assertIsNone(scan.resolve(luna, ["luna.one", "luna.two"]), "two matches: refuse to guess")


if __name__ == "__main__":
    unittest.main()
