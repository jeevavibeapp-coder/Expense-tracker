"""Adaptive confidence thresholds for the merchant engine.

The engine scores a merchant match 0–100 and compares it against two fixed
thresholds: auto-save at 80, ask-to-confirm at 50. Those numbers were chosen
once, by hand, for an imagined average user — and they are the single control
over the two failure modes that actually annoy people:

* threshold too low  -> wrong merchants get saved silently, and the user only
  finds out when a report looks wrong;
* threshold too high -> everything lands in the review queue, which is how
  this app ended up asking about 209 transactions.

The evidence needed to tune them is already recorded. Every time the engine
auto-applies a merchant and the user then changes it, ``learning`` records a
correction; every time they accept, a confirmation. The ratio between those
is a direct measurement of whether the current threshold is too generous for
*this* user's data.

So: measure the observed error rate, and move the thresholds against it.

Two properties are non-negotiable:

* **Bounded.** Adaptation moves within a fixed band. An unbounded feedback
  loop could drive the auto threshold to 0 (silently mis-saving everything)
  or 100 (never auto-saving again), and both are worse than a mediocre fixed
  value.
* **Explicit user settings win.** If someone has set their own thresholds in
  Settings, that is a decision, not a default to second-guess.
"""
from __future__ import annotations

import sqlite3
from typing import Optional

CALIBRATION_VERSION = "2026.07.1"

# Below this many decisions the correction rate is noise, not signal.
MIN_OBSERVATIONS = 20

# The band adaptation may move within. The defaults (80/50) sit inside it, so
# a user with typical behaviour stays where the hand-tuned values put them.
AUTO_MIN, AUTO_MAX = 70, 92
CONFIRM_MIN, CONFIRM_MAX = 40, 62

# Error rates that define "well calibrated". Between these the thresholds are
# left alone — constant small adjustments would make the app's behaviour feel
# unpredictable for no measured benefit.
TARGET_LOW, TARGET_HIGH = 0.05, 0.15

# How far one full step moves a threshold.
STEP = 8


def observed_error_rate(conn: sqlite3.Connection,
                        user_id: str) -> Optional[tuple[float, int]]:
    """Return ``(correction_rate, observations)`` or None if too little data.

    A "correction" is the user overriding a merchant the engine chose. That
    is the only unambiguous signal that a threshold let through a match it
    should not have.
    """
    row = conn.execute(
        "SELECT COALESCE(SUM(confirmation_count),0), COALESCE(SUM(correction_count),0) "
        "FROM learning WHERE user_id=?", (user_id,)).fetchone()
    confirmations, corrections = int(row[0]), int(row[1])
    total = confirmations + corrections
    if total < MIN_OBSERVATIONS:
        return None
    return corrections / total, total


def thresholds(conn: sqlite3.Connection, user_id: str,
               base_auto: int, base_confirm: int,
               user_set: bool = False) -> dict:
    """Adapt the two thresholds to this user's measured error rate.

    Returns the values to use plus why, so the Settings screen can explain
    itself instead of silently disagreeing with what the user last saw.
    """
    result = {"auto": int(base_auto), "confirm": int(base_confirm),
              "adapted": False, "reason": "defaults",
              "error_rate": None, "observations": 0,
              "version": CALIBRATION_VERSION}
    if user_set:
        result["reason"] = "user_set"
        return result

    measured = observed_error_rate(conn, user_id)
    if measured is None:
        result["reason"] = "insufficient_history"
        return result

    rate, observations = measured
    result["error_rate"] = round(rate, 3)
    result["observations"] = observations

    if rate > TARGET_HIGH:
        # Too many wrong auto-saves: demand more evidence before acting.
        # Scale the step with how far past target we are, but cap it so one
        # bad week cannot swing the behaviour to an extreme.
        severity = min(2.0, (rate - TARGET_HIGH) / TARGET_HIGH)
        shift = int(round(STEP * (1 + severity)))
        result["auto"] = min(AUTO_MAX, int(base_auto) + shift)
        result["confirm"] = min(CONFIRM_MAX, int(base_confirm) + shift // 2)
        result["adapted"] = True
        result["reason"] = "high_correction_rate"
    elif rate < TARGET_LOW:
        # The engine is nearly always right for this user, so the review queue
        # is mostly pointless friction. Let more through automatically.
        result["auto"] = max(AUTO_MIN, int(base_auto) - STEP)
        result["confirm"] = max(CONFIRM_MIN, int(base_confirm) - STEP // 2)
        result["adapted"] = True
        result["reason"] = "low_correction_rate"
    else:
        result["reason"] = "well_calibrated"

    # Invariant: an auto threshold at or below the confirm threshold would
    # collapse the two decisions into one and make "confirm" unreachable.
    if result["auto"] <= result["confirm"]:
        result["auto"] = result["confirm"] + 10
    return result


def describe(state: dict) -> str:
    """One line for the Settings screen."""
    if state["reason"] == "user_set":
        return "Using your own thresholds."
    if state["reason"] == "insufficient_history":
        return "Learning from your corrections — using the defaults for now."
    pct = int(round((state["error_rate"] or 0) * 100))
    if state["reason"] == "high_correction_rate":
        return (f"You corrected {pct}% of {state['observations']} matches, "
                f"so SpendWise now asks before saving more of them.")
    if state["reason"] == "low_correction_rate":
        return (f"You corrected only {pct}% of {state['observations']} matches, "
                f"so SpendWise saves more of them automatically.")
    return (f"Well calibrated — {pct}% of {state['observations']} matches "
            f"needed a correction.")
