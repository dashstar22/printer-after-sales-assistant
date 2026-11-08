"""Fixed v2 assets with the shared default-disabled E3 entry; no new transport."""
import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
COMMON = ROOT.parent / "observation-semantics" / "adapter"
SPEC = importlib.util.spec_from_file_location("observation_v2_shared_e3", COMMON / "run_e3.py")
E3 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E3)
# The only selected asset directory is this fixed, repository-owned v2 package.
# Transport, explicit real gates, credential handling, budgets and result scoring
# remain the common implementation. No CLI asset or URL override is introduced.
E3.BASE.ASSETS = ROOT


def main(argv=None):
    return E3.main(argv)


if __name__ == "__main__":
    sys.exit(main())
