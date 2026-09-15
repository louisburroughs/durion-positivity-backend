"""Pins the SDK seeder's reference data in the alpha packs, by the keys the seeder looks up.

durion-positivity-sdk's sdk-seeder used to create its own site, employees, parts and services
through the API. The packs now carry those records, and the seeder finds each by its natural key
instead of creating it: the site by code, an employee by employee number, a product by SKU, a
service by name. That reuse is only as good as the rows. A mistyped SKU, employee number or site
code is not a failed load -- the pack still loads cleanly -- it is a miss, and the seeder quietly
creates a parallel record next to the one the pack made, which is the duplication these rows exist
to remove. Aggregate row counts and taxonomy checks cannot see that, so this pins the values.

The expected values mirror sdk-seeder's bootstraps and must move with them:
  packages/sdk-seeder/src/bootstrap/LocationBootstrap.ts  (site code, name, bays)
  packages/sdk-seeder/src/bootstrap/PeopleBootstrap.ts    (EMPLOYEE_SEEDS)
  packages/sdk-seeder/src/bootstrap/CatalogBootstrap.ts   (SERVICE_SEEDS, PRODUCT_SEEDS, buildProducts)

Stdlib only: no build, no network, no gateway.
"""

import csv
import pathlib
import unittest

_ALPHA = pathlib.Path(__file__).resolve().parents[1] / "fixtures" / "seed" / "alpha"

_SITE_CODE = "ATX-RIV-001"
_SITE_NAME = "Riverside Auto Service"
_BAYS = {"Bay 1": "GENERAL_SERVICE", "Bay 2": "GENERAL_SERVICE", "Bay 3": "TIRE_SERVICE"}

# EMPLOYEE_SEEDS: employeeNumber -> (firstName, lastName, preferredName, hireDate, role)
_EMPLOYEES = {
    "EMP-T001": ("James", "Rivera", "James", "2022-01-10", "TECHNICIAN"),
    "EMP-T002": ("Marcus", "Bennett", "Marcus", "2022-04-18", "TECHNICIAN"),
    "EMP-T003": ("Elena", "Torres", "Elena", "2023-02-06", "TECHNICIAN"),
    "EMP-SW001": ("Olivia", "Price", "Olivia", "2021-09-13", "SERVICE_WRITER"),
    "EMP-SW002": ("Daniel", "Kim", "Daniel", "2023-07-24", "SERVICE_WRITER"),
    "EMP-M001": ("Michelle", "Carter", "Michelle", "2020-05-04", "MANAGER"),
    "EMP-P001": ("Avery", "Collins", "Avery", "2022-11-14", "PARTS_CLERK"),
}

# PRODUCT_SEEDS: (baseName, prefix, count, startingPrice), plus the taxonomy the pack maps each
# family to (sdk-seeder's own category labels are not catalog names).
_PRODUCT_FAMILIES = [
    ("Oil Filter", "OF", 5, 12.49, "Filters", "Oil Filters"),
    ("Brake Pad Set", "BP", 6, 74.99, "Brake System", "Brake Pads & Shoes"),
    ("Battery", "BAT", 3, 129.99, "Electrical System", "Batteries"),
    ("Engine Air Filter", "AF", 4, 19.99, "Filters", "Air Filters"),
    ("Wiper Blade", "WB", 4, 16.99, "Body & Lighting", "Wiper Blades"),
    ("Spark Plug", "SP", 4, 8.99, "Engine Parts", "Spark Plugs & Ignition"),
    ("Cabin Air Filter", "CAF", 4, 21.99, "Filters", "Cabin Air Filters"),
]

# SERVICE_SEEDS names; the seeder resolves a service by exact name.
_SERVICE_NAMES = [
    "Oil Change - Full Synthetic",
    "Brake Pad Replacement - Front",
    "Brake Pad Replacement - Rear",
    "Tire Rotation",
    "Wheel Alignment - 4-Wheel",
    "Coolant System Flush",
    "Battery Replacement",
    "Air Filter Replacement",
    "Spark Plug Replacement",
    "Wiper Blade Replacement",
    "Cabin Air Filter Replacement",
    "Transmission Service",
]


def _rows(relative):
    with open(_ALPHA / relative, newline="") as fh:
        return list(csv.DictReader(fh))


def _expected_products():
    """buildProducts: sku PREFIX-nnn, mpn PREFIXM-nnn, name 'Base n', price start + i * 1.75."""
    for base, prefix, count, start, category, subcategory in _PRODUCT_FAMILIES:
        for index in range(count):
            sequence = f"{index + 1:03d}"
            yield {
                "sku": f"{prefix}-{sequence}",
                "mpn": f"{prefix}M-{sequence}",
                "name": f"{base} {index + 1}",
                "price": round(start + index * 1.75, 2),
                "categoryName": category,
                "subcategoryName": subcategory,
            }


class SeederSiteTest(unittest.TestCase):
    def test_theSiteIsLoadedUnderTheCodeTheSeederLooksUp(self):
        sites = [r for r in _rows("location/locations.csv") if r["code"] == _SITE_CODE]
        self.assertEqual(len(sites), 1, f"exactly one {_SITE_CODE} row")
        self.assertEqual(sites[0]["name"], _SITE_NAME)

    def test_theSeedersBaysAreLoadedAtTheSiteWithTheirTypes(self):
        bays = {r["name"]: r["bayType"] for r in _rows("location/bays.csv") if r["locationCode"] == _SITE_CODE}
        for name, bay_type in _BAYS.items():
            self.assertEqual(bays.get(name), bay_type, f"{_SITE_CODE} {name}")

    def test_theSiteDeclaresDefaults(self):
        self.assertIn(_SITE_CODE, {r["locationCode"] for r in _rows("location/site-defaults.csv")})


class SeederPeopleTest(unittest.TestCase):
    def test_everySeederEmployeeIsLoadedUnderItsNumberWithItsValues(self):
        employees = {r["employeeNumber"]: r for r in _rows("people/employees.csv")}
        for number, (first, last, preferred, hired, _) in _EMPLOYEES.items():
            with self.subTest(employeeNumber=number):
                self.assertIn(number, employees)
                row = employees[number]
                self.assertEqual(
                    (row["firstName"], row["lastName"], row["preferredName"], row["hireDate"]),
                    (first, last, preferred, hired),
                )

    def test_everySeederEmployeeHasOnePrimaryAssignmentAtTheSiteInItsRole(self):
        # The seeder treats an ACTIVE primary assignment at the site in its role as present, and
        # creates one otherwise; a second primary elsewhere would not stop that.
        assignments = _rows("people/staffing-assignments.csv")
        for number, (*_, role) in _EMPLOYEES.items():
            with self.subTest(employeeNumber=number):
                mine = [a for a in assignments if a["employeeNumber"] == number]
                self.assertEqual(
                    [(a["locationCode"], a["role"], a["primary"]) for a in mine],
                    [(_SITE_CODE, role, "true")],
                )


class SeederCatalogTest(unittest.TestCase):
    def test_everySeederProductIsLoadedUnderItsSkuWithItsValues(self):
        products = {r["sku"]: r for r in _rows("catalog/products.csv")}
        expected = list(_expected_products())
        self.assertEqual(len(expected), 30)
        for product in expected:
            with self.subTest(sku=product["sku"]):
                self.assertIn(product["sku"], products)
                row = products[product["sku"]]
                for field in ("mpn", "name", "categoryName", "subcategoryName"):
                    self.assertEqual(row[field], product[field], field)

    def test_everySeederProductIsPricedAtTheSeedersPrice(self):
        prices = {r["sku"]: r for r in _rows("price/base-prices.csv")}
        for product in _expected_products():
            with self.subTest(sku=product["sku"]):
                self.assertIn(product["sku"], prices)
                self.assertAlmostEqual(float(prices[product["sku"]]["msrp"]), product["price"], places=2)
                self.assertEqual(prices[product["sku"]]["currency"], "USD")

    def test_everySeederServiceIsLoadedUnderItsExactName(self):
        names = [r["name"] for r in _rows("catalog/tier0-services.csv")]
        for name in _SERVICE_NAMES:
            with self.subTest(service=name):
                self.assertEqual(names.count(name), 1, "exactly one service with this name")


if __name__ == "__main__":
    unittest.main()
