from samagents.filters import categorize, prefilter


def test_product_psc_is_candidate(settings):
    st, reason, cat = prefilter({"title": "Cold Weather Parkas", "psc_code": "8415", "naics_code": "315250"},
                                settings.filters, settings.categories)
    assert st == "candidate"
    assert cat == "Clothing & Footwear"


def test_construction_psc_is_excluded(settings):
    st, reason, _ = prefilter({"title": "Roof Replacement Building 400", "psc_code": "Z2AZ", "naics_code": "238160"},
                              settings.filters, settings.categories)
    assert st == "excluded"


def test_service_psc_excluded_even_without_title_keyword(settings):
    st, reason, _ = prefilter({"title": "Grounds Maintenance FY27", "psc_code": "S208", "naics_code": "541620"},
                              settings.filters, settings.categories)
    assert st == "excluded"
    assert "service" in reason


def test_missing_psc_rescued_by_title(settings):
    st, reason, cat = prefilter({"title": "Purchase of Hiking Backpacks and Sleeping Bags", "psc_code": None,
                                 "naics_code": "339920"}, settings.filters, settings.categories)
    assert st == "candidate"
    assert cat == "Outdoor & Tactical Gear"


def test_missing_psc_without_signal_excluded(settings):
    st, _, _ = prefilter({"title": "Quarterly Review", "psc_code": None, "naics_code": ""},
                         settings.filters, settings.categories)
    assert st == "excluded"


def test_construction_naics_excluded_even_with_product_psc(settings):
    st, reason, _ = prefilter({"title": "Lumber and Fasteners", "psc_code": "5510", "naics_code": "236220"},
                              settings.filters, settings.categories)
    assert st == "excluded"
    assert "NAICS" in reason


def test_categorize_falls_back_to_last(settings):
    assert categorize("5510", "Lumber", settings.categories) == "Other Merchandise"
    assert categorize("8530", "Hygiene kits", settings.categories) == "Health & Beauty Aids"
    assert categorize(None, "Sunglasses for lifeguards", settings.categories) == "Accessories & Personal Items"
