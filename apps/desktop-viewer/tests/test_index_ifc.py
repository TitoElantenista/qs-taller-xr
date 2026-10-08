from qs_desktop_viewer.catalog import UNASSIGNED_POSITION_NUMBER
from qs_desktop_viewer.index_ifc import _business_position_or_unassigned


def test_business_position_classification() -> None:
    assert _business_position_or_unassigned("123456.7") == "123456.7"
    assert _business_position_or_unassigned("731006/0(?)") == "731006/0(?)"
    assert _business_position_or_unassigned("A.662") == UNASSIGNED_POSITION_NUMBER
    assert _business_position_or_unassigned("Q.0(?)") == UNASSIGNED_POSITION_NUMBER
    assert _business_position_or_unassigned("") == UNASSIGNED_POSITION_NUMBER
    assert _business_position_or_unassigned(None) == UNASSIGNED_POSITION_NUMBER
