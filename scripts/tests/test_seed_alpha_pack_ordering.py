"""Pins the pack order the dependent packs rely on in seed-alpha.py.

STAFFING_ASSIGNMENT and MECHANIC_SKILL both name a person that people/employees.csv creates, so
they have to run after PERSON. Until #1987 they also lost a race against replication when they ran
too soon after it: pos-people gated an assignment on its ext_people_contact_person replica rather
than on the employee row it owns, and pos-shop-manager refused a skills write outright while the
staffing assignment that creates the mechanic was still in flight. Both services now answer for
themselves -- pos-people from its own employee row, pos-shop-manager after a bounded wait and with
a retryable 503 where it genuinely cannot tell -- so the driver no longer sleeps or retries, and
this ordering is all that remains of the dependency.

Stdlib only: no build, no network, no gateway.
"""

import importlib.util
import pathlib
import unittest

_DRIVER_PATH = pathlib.Path(__file__).resolve().parents[1] / "seed-alpha.py"

# Packs that name a person people/employees.csv created, and the pack that creates them.
_PERSON_DEPENDENT_PACKS = ("STAFFING_ASSIGNMENT", "MECHANIC_SKILL")
_PERSON_PACK = "PERSON"


def _load_driver():
    spec = importlib.util.spec_from_file_location("seed_alpha_ordering", _DRIVER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed_alpha = _load_driver()


class PackOrderingTest(unittest.TestCase):
    def test_everyPersonDependentPackIsActuallyLoaded(self):
        # A pack named here but absent from PACK_FILES would make the ordering check vacuous.
        loaded = {domain for _, domain in seed_alpha.PACK_FILES}
        for domain in _PERSON_DEPENDENT_PACKS:
            self.assertIn(domain, loaded)

    def test_eachPersonDependentPackRunsAfterPerson(self):
        order = [domain for _, domain in seed_alpha.PACK_FILES]
        person = order.index(_PERSON_PACK)
        for domain in _PERSON_DEPENDENT_PACKS:
            self.assertGreater(
                order.index(domain), person, f"{domain} must load after {_PERSON_PACK}"
            )


class NoReplicationWorkaroundTest(unittest.TestCase):
    """#1987 retired the driver-side workaround; a reintroduction should be deliberate."""

    def test_theDriverNoLongerSleepsForReplication(self):
        self.assertFalse(hasattr(seed_alpha, "REPLICATION_SENSITIVE_PACKS"))
        self.assertFalse(hasattr(seed_alpha, "loaded_across_attempts"))


if __name__ == "__main__":
    unittest.main()
